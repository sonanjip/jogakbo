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
    /** Returns false when the job should be tried again later (OndolSaem not reachable). */
    suspend fun run(noteId: Long, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Boolean {
        val note = dao.get(noteId) ?: return true
        val text = input(note, dao.cues(noteId).map { Cue(it.startMs, it.endMs, it.text) })
        if (text.replace(Regex("\\s+"), "").length < MIN_CHARS) {
            dao.setSummaryState(noteId, JobState.FAILED, context.getString(R.string.summary_too_short)); return true
        }
        dao.setSummaryState(noteId, JobState.RUNNING)
        val lease = models.text()
        if (lease == null) {
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
        } catch (e: Exception) {
            dao.setSummaryState(noteId, JobState.FAILED, e.message ?: e.javaClass.simpleName); true
        }
    }

    companion object {
        const val MIN_CHARS = 40

        /** What the model reads: the original, or for transcripts the plain lines (no times). */
        fun input(note: NoteEntity, cues: List<Cue>): String = when (note.kind) {
            NoteKind.MEMO, NoteKind.LINK -> note.body
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
