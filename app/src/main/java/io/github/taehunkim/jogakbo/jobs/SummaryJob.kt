package io.github.taehunkim.jogakbo.jobs

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.data.db.NoteDao
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.data.prefs.SettingsStore
import io.github.taehunkim.jogakbo.ml.ModelHost
import io.github.taehunkim.jogakbo.notes.Bodies
import io.github.taehunkim.jogakbo.notes.NoteJobs
import io.github.taehunkim.jogakbo.summary.SummaryRequest
import io.github.taehunkim.jogakbo.summary.Summarizer
import io.github.taehunkim.ondolsaem.sdk.HostCallException
import io.github.taehunkim.ondolsaem.sdk.HostUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.Locale

/** Makes a note's summary with OndolSaem's language model. */
class SummaryJob(
    private val context: Context,
    private val dao: NoteDao,
    private val models: ModelHost,
    private val settings: SettingsStore,
    private val jobs: NoteJobs,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Returns false when the job should be tried again later (OndolSaem or its language model not reachable). With
     * [keepWaiting] (the passes that make waiting summaries) the note then goes back to waiting instead of failing for
     * good, so the next pass tries it again.
     */
    suspend fun run(noteId: Long, progress: (done: Int, total: Int) -> Unit = { _, _ -> }, keepWaiting: Boolean = false): Boolean {
        val note = dao.get(noteId) ?: return true
        val text = input(note, dao.cues(noteId).map { Cue(it.startMs, it.endMs, it.text) })
        if (text.replace(Regex("\\s+"), "").length < MIN_CHARS) {
            dao.setSummaryState(noteId, JobState.FAILED, context.getString(R.string.summary_too_short)); return true
        }
        dao.setSummaryState(noteId, JobState.RUNNING)
        val lease = try { models.text() } catch (e: HostUnavailableException) { null } catch (e: HostCallException) { null }
        if (lease == null) {
            if (keepWaiting) { dao.setSummaryState(noteId, JobState.DEFERRED, context.getString(R.string.summary_no_model)); return false }
            dao.setSummaryState(noteId, JobState.FAILED, context.getString(R.string.summary_no_model)); return true
        }
        return try {
            lease.use { l ->
                val req = SummaryRequest(
                    kind = note.kind, title = note.title, text = text, notePrompt = note.summaryPrompt,
                    globalPrompt = settings.systemPrompt.first(), language = settings.summaryLanguage.first(),
                    appLanguage = appLanguage(),
                )
                val summary = Summarizer(l.value).summarize(req) { d, t -> progress(d, t) }
                if (summary.isBlank()) throw IllegalStateException("empty answer")
                dao.setSummary(noteId, summary, l.value.modelId, clock())
            }
            jobs.noteChanged()
            true
        } catch (e: CancellationException) {
            dao.setSummaryState(noteId, JobState.NONE); throw e
        } catch (e: HostUnavailableException) {
            if (keepWaiting) { dao.setSummaryState(noteId, JobState.DEFERRED, e.message); false }
            else { dao.setSummaryState(noteId, JobState.FAILED, e.message ?: e.javaClass.simpleName); true }
        } catch (e: Exception) {
            dao.setSummaryState(noteId, JobState.FAILED, e.message ?: e.javaClass.simpleName); true
        }
    }

    companion object {
        const val MIN_CHARS = 40

        /** What the model reads: the original, or for transcripts the plain lines (no times). */
        fun input(note: NoteEntity, cues: List<Cue>): String = when (note.kind) {
            NoteKind.MEMO, NoteKind.LINK, NoteKind.DOCUMENT, NoteKind.IMAGE -> note.body
            NoteKind.YOUTUBE -> {
                val desc = runCatching { JSONObject(note.sourceMeta ?: "{}").optString("description") }.getOrDefault("")
                listOf(desc.trim(), Bodies.transcriptText(cues)).filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { note.body }
            }
            NoteKind.AUDIO -> Bodies.transcriptText(cues).ifEmpty { note.body }
        }

        /** The app's language (per-app setting, else the phone's) in English, e.g. "Korean". */
        fun appLanguage(): String {
            val locale = AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault()
            return locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
        }
    }
}
