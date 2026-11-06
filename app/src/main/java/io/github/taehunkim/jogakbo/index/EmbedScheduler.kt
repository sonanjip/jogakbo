package io.github.taehunkim.jogakbo.index

import android.content.Context
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
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.data.prefs.EmbedMode
import io.github.taehunkim.jogakbo.data.prefs.SettingsStore
import io.github.taehunkim.jogakbo.service.NotesEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * When notes are embedded (Settings): right after a change (debounced), once a day at a set time, while the phone
 * charges and is idle, or only with "Embed now". Like Nunsori's automatic indexing, without a file trigger.
 */
class EmbedScheduler(private val context: Context, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val wm get() = WorkManager.getInstance(context)

    fun start() {
        scope.launch {
            combine(settings.embedMode, settings.scheduleMinutes, settings.scheduleChargingOnly) { m, min, ch -> Triple(m, min, ch) }
                .distinctUntilChanged().collect { (m, min, ch) -> apply(m, min, ch) }
        }
    }

    private fun apply(mode: EmbedMode, minutes: Int, chargingOnly: Boolean) {
        if (mode != EmbedMode.SCHEDULED) wm.cancelUniqueWork(SCHEDULED)
        if (mode != EmbedMode.IDLE) wm.cancelUniqueWork(IDLE)
        when (mode) {
            EmbedMode.IMMEDIATE -> runNow(delaySec = 5) // catch up on what changed while the app was not running
            EmbedMode.SCHEDULED -> {
                val req = PeriodicWorkRequestBuilder<EmbedWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(delayUntil(minutes, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(chargingOnly).build())
                    .setInputData(workDataOf(EmbedWorker.KEY_SOURCE to "scheduled"))
                    .build()
                wm.enqueueUniquePeriodicWork(SCHEDULED, ExistingPeriodicWorkPolicy.UPDATE, req)
            }
            EmbedMode.IDLE -> {
                // "Not in use" = charging and the screen off long enough for the battery level (OndolSaem SDK's
                // IdleRule, preset in Settings): a check every 15 minutes while charging, the worker returns at once
                // unless idle.
                val req = PeriodicWorkRequestBuilder<EmbedWorker>(IDLE_CHECK_MIN, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                    .setInputData(workDataOf(EmbedWorker.KEY_SOURCE to "idle"))
                    .build()
                wm.enqueueUniquePeriodicWork(IDLE, ExistingPeriodicWorkPolicy.UPDATE, req)
            }
            EmbedMode.MANUAL -> Unit
        }
    }

    /** A note changed: in [EmbedMode.IMMEDIATE], embed after [DEBOUNCE_SEC] without further changes. */
    fun onNoteChanged() {
        scope.launch {
            if (settings.embedMode.first() == EmbedMode.IMMEDIATE) runNow(DEBOUNCE_SEC)
            // other apps hear about changes now; the new text is searchable once embedded
            NotesEvents.notifyChanged(context)
        }
    }

    /** "Embed now" (and the immediate mode). */
    fun runNow(delaySec: Long = 0) {
        val req = OneTimeWorkRequestBuilder<EmbedWorker>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setInputData(workDataOf(EmbedWorker.KEY_SOURCE to "now"))
            .build()
        wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    companion object {
        const val NOW = "embed-now"
        const val SCHEDULED = "embed-scheduled"
        const val IDLE = "embed-idle"
        const val DEBOUNCE_SEC = 30L
        /** Idle checks while charging (WorkManager's shortest period). */
        const val IDLE_CHECK_MIN = 15L

        /** Milliseconds from [now] to the next [minutes] after midnight. */
        fun delayUntil(minutes: Int, now: Long): Long {
            val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
            return c.timeInMillis - now
        }
    }
}

/** Embeds the notes that need it. */
class EmbedWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as JogakboApp).container
        val idleRun = inputData.getString(KEY_SOURCE) == "idle"
        val idle = io.github.taehunkim.ondolsaem.sdk.IdleState(applicationContext)
        if (idleRun && !idle.isIdle(c.settings.idlePreset.first())) return Result.success()
        // an idle pass stops when the phone is used again; the rest waits for the next idle check
        val keepGoing = { !idleRun || !idle.screenOn() }
        fun info(done: Int, total: Int) = Notifications.foregroundInfo(
            Notifications.ID_EMBED,
            Notifications.progress(applicationContext, id, applicationContext.getString(R.string.job_embedding), null, done, total, null),
        )
        val pass = c.indexer.embedPending({ d, t -> if (t > 3) runCatching { runBlocking { setForeground(info(d, t)) } } }, keepGoing)
        if (pass.embedded > 0) NotesEvents.notifyChanged(applicationContext)
        // tags follow the same timing; a missing decision model does not make the pass retry
        val tagged = if (c.settings.autoTag.first() && keepGoing()) runCatching { c.tagger.tagPending(keepGoing = keepGoing) }.getOrNull() else null
        if ((tagged?.tagged ?: 0) > 0 && pass.embedded == 0) NotesEvents.notifyChanged(applicationContext)
        if (idleRun && !keepGoing()) return Result.success()
        return if (pass.noModel || pass.left > 0) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo() = Notifications.foregroundInfo(
        Notifications.ID_EMBED, Notifications.progress(applicationContext, id, applicationContext.getString(R.string.job_embedding), null, 0, 0, null),
    )

    companion object { const val KEY_SOURCE = "source" }
}
