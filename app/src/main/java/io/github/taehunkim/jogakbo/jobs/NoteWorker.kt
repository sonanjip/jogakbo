package io.github.taehunkim.jogakbo.jobs

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.taehunkim.jogakbo.JogakboApp
import io.github.taehunkim.jogakbo.Notifications
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.notes.NoteJobs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import io.github.taehunkim.jogakbo.notes.ProcessingOutcome
import io.github.taehunkim.jogakbo.service.NotesEvents

/** One background job on one note: its original, summary, index or tags. */
class NoteWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as JogakboApp).container
        return c.processingLock.withLock { runJob(c) }
    }

    private suspend fun runJob(c: io.github.taehunkim.jogakbo.AppContainer): Result {
        val noteId = inputData.getLong(KEY_NOTE, -1).takeIf { it > 0 } ?: return Result.failure()
        val job = inputData.getString(KEY_JOB)
        val note = c.db.noteDao().get(noteId) ?: return Result.success()
        val title = applicationContext.getString(
            when {
                job == JOB_SUMMARY -> R.string.job_summarizing
                job == JOB_INDEX -> R.string.job_embedding
                job == JOB_TAGS -> R.string.embed_stage_tags
                note.kind == NoteKind.AUDIO -> R.string.job_transcribing
                note.kind == NoteKind.DOCUMENT || note.kind == NoteKind.IMAGE -> R.string.job_reading_document
                else -> R.string.job_fetching
            },
        )
        val notifId = Notifications.jobId(noteId)
        fun info(done: Int, total: Int) = Notifications.foregroundInfo(notifId, Notifications.progress(applicationContext, id, title, note.title, done, total, noteId))
        runCatching { setForeground(info(0, 0)) }
        val progress: (Int, Int) -> Unit = { d, t -> runCatching { runBlocking { setForeground(info(d, t)) } } }
        return when (job) {
            JOB_INDEX, JOB_TAGS -> {
                try {
                    val outcome = if (job == JOB_INDEX) c.indexer.embedOne(noteId) else c.tagger.tagOne(noteId, force = true)
                    when (outcome) {
                        ProcessingOutcome.DONE -> { NotesEvents.notifyChanged(applicationContext); Result.success() }
                        ProcessingOutcome.NOT_FOUND -> Result.success()
                        else -> Result.failure(workDataOf(OUT_REASON to outcome.name, OUT_AT to System.currentTimeMillis()))
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val reason = if (e is io.github.taehunkim.ondolsaem.sdk.HostUnavailableException) ProcessingOutcome.NO_MODEL.name else "FAILED"
                    Result.failure(workDataOf(OUT_REASON to reason, OUT_AT to System.currentTimeMillis()))
                }
            }
            JOB_SUMMARY -> if (c.summaryJob.run(noteId, progress)) Result.success() else Result.retry()
            else -> when (c.sourceJob.run(noteId, progress)) {
                SourceJob.Outcome.DONE -> Result.success()
                SourceJob.Outcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else {
                    c.db.noteDao().setSourceState(noteId, io.github.taehunkim.jogakbo.data.db.JobState.FAILED, c.db.noteDao().get(noteId)?.sourceError)
                    Result.success()
                }
            }
        }
    }

    override suspend fun getForegroundInfo() = Notifications.foregroundInfo(
        Notifications.jobId(inputData.getLong(KEY_NOTE, 0)),
        Notifications.progress(applicationContext, id, applicationContext.getString(R.string.job_working), null, 0, 0, null),
    )

    companion object {
        const val KEY_NOTE = "noteId"
        const val KEY_JOB = "job"
        const val JOB_SOURCE = "source"
        const val JOB_SUMMARY = "summary"
        const val JOB_INDEX = "index"
        const val JOB_TAGS = "tags"
        const val OUT_REASON = "reason"
        const val OUT_AT = "finishedAt"
        const val REQUEST_TAG_PREFIX = "note-request-"
        fun operationTag(job: String) = "note-operation-$job"
        const val MAX_ATTEMPTS = 4

        fun name(noteId: Long, job: String) = "note-$noteId-$job"
        fun tag(noteId: Long) = "note-$noteId"
    }
}

/** [NoteJobs] on WorkManager. */
class WorkJobs(private val context: Context, private val onChanged: () -> Unit) : NoteJobs {
    private val wm get() = WorkManager.getInstance(context)

    private fun enqueue(noteId: Long, job: String, network: Boolean, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val req = OneTimeWorkRequestBuilder<NoteWorker>()
            .setInputData(workDataOf(NoteWorker.KEY_NOTE to noteId, NoteWorker.KEY_JOB to job))
            .addTag(NoteWorker.tag(noteId))
            .addTag(NoteWorker.operationTag(job))
            .addTag(NoteWorker.REQUEST_TAG_PREFIX + System.currentTimeMillis())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .apply { if (network) setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()) }
            .build()
        wm.enqueueUniqueWork(NoteWorker.name(noteId, job), policy, req)
    }

    // links need the network; audio does not
    override fun fetchSource(noteId: Long, kind: NoteKind) = enqueue(noteId, NoteWorker.JOB_SOURCE, kind == NoteKind.LINK || kind == NoteKind.YOUTUBE)

    override fun summarize(noteId: Long) = enqueue(noteId, NoteWorker.JOB_SUMMARY, false)

    override fun index(noteId: Long) = enqueue(noteId, NoteWorker.JOB_INDEX, false, ExistingWorkPolicy.KEEP)

    override fun tag(noteId: Long) = enqueue(noteId, NoteWorker.JOB_TAGS, false, ExistingWorkPolicy.KEEP)

    override fun cancel(noteId: Long) { wm.cancelAllWorkByTag(NoteWorker.tag(noteId)) }

    override fun noteChanged() = onChanged()
}
