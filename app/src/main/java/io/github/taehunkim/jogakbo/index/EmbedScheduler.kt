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
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.notes.Timing
import io.github.taehunkim.jogakbo.service.NotesEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * When notes are summarized (new notes' automatic summaries), embedded and tagged (Settings): right after a change
 * (debounced), once a day at a set time, while the phone charges and is idle, or only with "Embed now". Like Nunsori's
 * automatic indexing, without a file trigger. A note can choose otherwise for itself ([Timing]): a note that asks for
 * "now" is handled right after a change in any mode, and in [EmbedMode.IMMEDIATE] a note that asks for "later" waits
 * for "Embed now".
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
            EmbedMode.IMMEDIATE -> runNow(delaySec = 5, auto = true) // catch up on what changed while the app was not running
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

    /**
     * A note changed: after [DEBOUNCE_SEC] without further changes, handle the notes whose timing is "now" (all of them
     * in [EmbedMode.IMMEDIATE], only the ones that asked for it in the other modes).
     */
    fun onNoteChanged() {
        scope.launch {
            runNow(DEBOUNCE_SEC, auto = true)
            // other apps hear about changes now; the new text is searchable once embedded
            NotesEvents.notifyChanged(context)
        }
    }

    /**
     * "Embed now" ([auto] false: every waiting note, summaries included), or the pass after a change ([auto] true: the
     * notes whose timing is "now").
     */
    fun runNow(delaySec: Long = 0, auto: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<EmbedWorker>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setInputData(workDataOf(EmbedWorker.KEY_SOURCE to if (auto) "auto" else "now"))
            .apply { if (!auto) addTag(NOW) }
            .build()
        wm.enqueueUniqueWork(if (auto) AUTO else NOW, ExistingWorkPolicy.REPLACE, req)
    }

    companion object {
        const val NOW = "embed-now"
        const val AUTO = "embed-auto"
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

/** Makes the waiting summaries, then embeds and tags the notes that need it. */
class EmbedWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as JogakboApp).container
        return c.processingLock.withLock { runPass(c) }
    }

    private suspend fun runPass(c: io.github.taehunkim.jogakbo.AppContainer): Result {
        val source = inputData.getString(KEY_SOURCE)
        val idleRun = source == "idle"
        val auto = source == "auto"
        val defaultNow = c.settings.embedMode.first() == EmbedMode.IMMEDIATE
        // after a change only the notes that are to be handled now; the other passes take everything
        val accept = { n: NoteEntity -> !auto || Timing.isNow(n, defaultNow) }
        if (auto && !defaultNow && !forcedNotesWaiting(c)) return Result.success()
        val idle = io.github.taehunkim.ondolsaem.sdk.IdleState(applicationContext)
        if (idleRun && !idle.isIdle(c.settings.idlePreset.first())) return Result.success()
        // an idle pass stops when the phone is used again; the rest waits for the next idle check
        val keepGoing = { !idleRun || !idle.screenOn() }
        fun info(done: Int, total: Int) = Notifications.foregroundInfo(
            Notifications.ID_EMBED,
            Notifications.progress(applicationContext, id, applicationContext.getString(R.string.job_embedding), null, done, total, null),
        )
        // summaries first: they are part of what is embedded and tagged. "Summarize, index and tag now" (the user asked)
        // also tries the summaries that failed before; the other passes take the waiting ones.
        val dao = c.db.noteDao()
        var summarized = 0
        var summariesBlocked = false
        if (!auto) {
            val ids = dao.deferredSummaries() + if (source == "now") dao.failedSummaries() else emptyList()
            for ((i, id) in ids.withIndex()) {
                if (!keepGoing()) break
                val title = dao.get(id)?.title.orEmpty()
                report(STAGE_SUMMARY, i, ids.size, title, id)
                runCatching { setForeground(summaryInfo(title, i, ids.size)) }
                val ok = try { c.summaryJob.run(id, keepWaiting = true) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { true }
                // the language model is out of reach: the rest would fail the same way; they wait for the next pass
                if (!ok) { summariesBlocked = true; break }
                if (dao.get(id)?.summaryState == io.github.taehunkim.jogakbo.data.db.JobState.DONE) summarized++
            }
        }
        report(STAGE_INDEX, 0, 0, "")
        var indexingNoteId = 0L
        val pass = c.indexer.embedPending({ d, t -> runBlocking { report(STAGE_INDEX, d, t, "", indexingNoteId) }; if (t > 3) runCatching { runBlocking { setForeground(info(d, t)) } } }, keepGoing, accept, onNote = { noteId -> indexingNoteId = noteId; runBlocking { report(STAGE_INDEX, 0, 0, "", noteId) } })
        if (pass.embedded > 0 || summarized > 0) NotesEvents.notifyChanged(applicationContext)
        // tags follow the same timing; a missing decision model does not make the pass retry
        report(STAGE_TAGS, 0, 0, "")
        val tagged = if (c.settings.autoTag.first() && keepGoing()) runCatching { c.tagger.tagPending(keepGoing = keepGoing, accept = accept, onNote = { noteId -> runBlocking { report(STAGE_TAGS, 0, 0, "", noteId) } }) }.getOrNull() else null
        if ((tagged?.tagged ?: 0) > 0 && pass.embedded == 0 && summarized == 0) NotesEvents.notifyChanged(applicationContext)
        val out = workDataOf(OUT_SUMMARIZED to summarized, OUT_EMBEDDED to pass.embedded, OUT_TAGGED to (tagged?.tagged ?: 0), OUT_BLOCKED to summariesBlocked)
        if (idleRun && !keepGoing()) return Result.success(out)
        // a summary that could not start waits and the next idle or scheduled pass tries it again; "now" says so instead
        val again = pass.noModel || pass.left > 0 || (summariesBlocked && source != "now")
        return if (again) Result.retry() else Result.success(out)
    }

    private suspend fun report(stage: String, done: Int, total: Int, note: String, noteId: Long = 0) {
        runCatching { setProgress(workDataOf(PROGRESS_STAGE to stage, PROGRESS_DONE to done, PROGRESS_TOTAL to total, PROGRESS_NOTE to note, PROGRESS_NOTE_ID to noteId)) }
    }

    private fun summaryInfo(title: String, done: Int, total: Int) = Notifications.foregroundInfo(
        Notifications.ID_EMBED,
        Notifications.progress(applicationContext, id, applicationContext.getString(R.string.job_summarizing), title, done, total, null),
    )

    /** True when a note that asked for "now" still needs embedding or tags (so an automatic pass has work). */
    private suspend fun forcedNotesWaiting(c: io.github.taehunkim.jogakbo.AppContainer): Boolean {
        val dao = c.db.noteDao()
        val embedId = c.settings.embeddingModelId.first()
        val waiting = HashSet<Long>()
        if (embedId.isNotEmpty()) waiting += dao.needingEmbedding(NoteIndexer.key(embedId))
        val decisionId = c.settings.decisionModelId.first()
        val tagCount = c.db.tagDao().tags().size
        if (c.settings.autoTag.first() && decisionId.isNotEmpty() && tagCount > 0) waiting += c.db.tagDao().needingTags()
        return waiting.any { id -> dao.get(id)?.let { Timing.override(it) == true } == true }
    }

    override suspend fun getForegroundInfo() = Notifications.foregroundInfo(
        Notifications.ID_EMBED, Notifications.progress(applicationContext, id, applicationContext.getString(R.string.job_embedding), null, 0, 0, null),
    )

    companion object {
        const val KEY_SOURCE = "source"
        // progress (what the pass is doing) and result (what it did), read by Settings
        const val PROGRESS_STAGE = "stage"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
        const val PROGRESS_NOTE = "note"
        const val PROGRESS_NOTE_ID = "noteId"
        const val STAGE_SUMMARY = "summary"
        const val STAGE_INDEX = "index"
        const val STAGE_TAGS = "tags"
        const val OUT_SUMMARIZED = "summarized"
        const val OUT_EMBEDDED = "embedded"
        const val OUT_TAGGED = "tagged"
        const val OUT_BLOCKED = "blocked"
    }
}
