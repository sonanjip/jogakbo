package io.github.taehunkim.jogakbo.io

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.taehunkim.jogakbo.JogakboApp
import io.github.taehunkim.jogakbo.Notifications
import io.github.taehunkim.jogakbo.data.prefs.SettingsStore
import io.github.taehunkim.jogakbo.index.EmbedScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Automatically exports notes to a ZIP backup once a day at a scheduled time.
 */
class AutoBackupScheduler(private val context: Context, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val wm get() = WorkManager.getInstance(context)

    fun start() {
        scope.launch {
            combine(settings.autoBackupEnabled, settings.autoBackupMinutes, settings.autoBackupChargingOnly) { en, min, ch ->
                Triple(en, min, ch)
            }.distinctUntilChanged().collect { (en, min, ch) ->
                apply(en, min, ch)
            }
        }
    }

    private fun apply(enabled: Boolean, minutes: Int, chargingOnly: Boolean) {
        if (!enabled) {
            wm.cancelUniqueWork(SCHEDULED)
            return
        }
        val delay = EmbedScheduler.delayUntil(minutes, System.currentTimeMillis())
        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(chargingOnly)
                    .build(),
            )
            .build()
        wm.enqueueUniquePeriodicWork(SCHEDULED, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Run an immediate backup right now. */
    fun runNow() {
        val req = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .addTag(NOW)
            .build()
        wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    companion object {
        const val SCHEDULED = "auto-backup-scheduled"
        const val NOW = "auto-backup-now"

        fun pruneFiles(files: List<File>, retention: Int): List<File> {
            val matching = files.filter { it.isFile && it.name.matches(Regex("^jogakbo-backup-.*\\.zip$")) }
                .sortedByDescending { it.name }
            val toDelete = matching.drop(retention.coerceAtLeast(1))
            toDelete.forEach { it.delete() }
            return toDelete
        }
    }
}

class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = (applicationContext as JogakboApp).container
        val isManual = tags.contains(AutoBackupScheduler.NOW) || inputData.getBoolean("manual", false)
        val enabled = c.settings.autoBackupEnabled.first()
        if (!enabled && !isManual) return@withContext Result.success()

        val folderUri = c.settings.autoBackupFolderUri.first()
        val retention = c.settings.autoBackupRetention.first()
        val includeAudio = c.settings.autoBackupIncludeAudio.first()
        val includeDocuments = c.settings.autoBackupIncludeDocuments.first()
        val includeSummaries = c.settings.autoBackupIncludeSummaries.first()

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        val filename = "jogakbo-backup-$timestamp.zip"

        try {
            var written = filename
            if (folderUri.isNotBlank()) {
                val uri = Uri.parse(folderUri)
                val folder = DocumentFile.fromTreeUri(applicationContext, uri)
                if (folder != null && folder.canWrite()) {
                    val file = folder.createFile("application/zip", filename)
                        ?: throw IOException("Cannot create $filename in chosen folder")
                    applicationContext.contentResolver.openOutputStream(file.uri, "w")?.use { stream ->
                        c.backup.export(stream, includeAudio, includeSummaries, includeDocuments)
                    } ?: throw IOException("Cannot write to ${file.uri}")
                    pruneTree(folder, retention)
                    written = file.name ?: filename
                } else {
                    written = writeToDefaultDir(c, filename, includeAudio, includeSummaries, includeDocuments, retention)
                }
            } else {
                written = writeToDefaultDir(c, filename, includeAudio, includeSummaries, includeDocuments, retention)
            }
            Notifications.backupDone(applicationContext, written)
            Result.success(workDataOf("filename" to written))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.e("AutoBackup", "Automatic backup failed", e)
            Result.failure(workDataOf("error" to (e.message ?: "Backup failed")))
        }
    }

    private suspend fun writeToDefaultDir(
        c: io.github.taehunkim.jogakbo.AppContainer,
        filename: String,
        includeAudio: Boolean,
        includeSummaries: Boolean,
        includeDocuments: Boolean,
        retention: Int,
    ): String {
        val dir = File(applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir, "backups").apply { mkdirs() }
        val target = File(dir, filename)
        FileOutputStream(target).use { c.backup.export(it, includeAudio, includeSummaries, includeDocuments) }
        AutoBackupScheduler.pruneFiles(dir.listFiles().orEmpty().toList(), retention)
        return target.name
    }

    private fun pruneTree(folder: DocumentFile, retention: Int) {
        val matching = folder.listFiles()
            .filter { it.name?.matches(Regex("^jogakbo-backup-.*\\.zip$")) == true }
            .sortedByDescending { it.name.orEmpty() }
        val toDelete = matching.drop(retention.coerceAtLeast(1))
        toDelete.forEach { it.delete() }
    }
}
