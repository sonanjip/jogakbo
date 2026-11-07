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

/** One background job on one note: making its original from the source, or its summary. */
class NoteWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as JogakboApp).container
        val noteId = inputData.getLong(KEY_NOTE, -1).takeIf { it > 0 } ?: return Result.failure()
        val job = inputData.getString(KEY_JOB)
        val note = c.db.noteDao().get(noteId) ?: return Result.success()
        val title = applicationContext.getString(
            when {
                job == JOB_SUMMARY -> R.string.job_summarizing
                note.kind == NoteKind.AUDIO -> R.string.job_transcribing
                note.kind == NoteKind.DOCUMENT -> R.string.job_reading_document
                else -> R.string.job_fetching
            },
        )
        val notifId = Notifications.jobId(noteId)
        fun info(done: Int, total: Int) = Notifications.foregroundInfo(notifId, Notifications.progress(applicationContext, id, title, note.title, done, total, noteId))
        runCatching { setForeground(info(0, 0)) }
        val progress: (Int, Int) -> Unit = { d, t -> runCatching { runBlocking { setForeground(info(d, t)) } } }
        return when (job) {
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
        const val MAX_ATTEMPTS = 4

        fun name(noteId: Long, job: String) = "note-$noteId-$job"
        fun tag(noteId: Long) = "note-$noteId"
    }
}

/** [NoteJobs] on WorkManager. */
class WorkJobs(private val context: Context, private val onChanged: () -> Unit) : NoteJobs {
    private val wm get() = WorkManager.getInstance(context)

    private fun enqueue(noteId: Long, job: String, network: Boolean) {
        val req = OneTimeWorkRequestBuilder<NoteWorker>()
            .setInputData(workDataOf(NoteWorker.KEY_NOTE to noteId, NoteWorker.KEY_JOB to job))
            .addTag(NoteWorker.tag(noteId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .apply { if (network) setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()) }
            .build()
        wm.enqueueUniqueWork(NoteWorker.name(noteId, job), ExistingWorkPolicy.REPLACE, req)
    }

    // links need the network; audio does not
    override fun fetchSource(noteId: Long, kind: NoteKind) = enqueue(noteId, NoteWorker.JOB_SOURCE, kind == NoteKind.LINK || kind == NoteKind.YOUTUBE)

    override fun summarize(noteId: Long) = enqueue(noteId, NoteWorker.JOB_SUMMARY, false)

    override fun cancel(noteId: Long) { wm.cancelAllWorkByTag(NoteWorker.tag(noteId)) }

    override fun noteChanged() = onChanged()
}
