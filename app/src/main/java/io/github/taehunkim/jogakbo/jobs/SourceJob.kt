package io.github.taehunkim.jogakbo.jobs

import android.content.Context
import android.net.Uri
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.audio.AudioDecoder
import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.audio.Pcm
import io.github.taehunkim.jogakbo.data.db.CueEntity
import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.data.db.NoteDao
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.data.prefs.SettingsStore
import io.github.taehunkim.jogakbo.fetch.ArticleExtractor
import io.github.taehunkim.jogakbo.fetch.FetchException
import io.github.taehunkim.jogakbo.fetch.HttpClient
import io.github.taehunkim.jogakbo.fetch.YouTubeFetcher
import io.github.taehunkim.jogakbo.ml.ModelHost
import io.github.taehunkim.jogakbo.notes.Bodies
import io.github.taehunkim.jogakbo.notes.NoteJobs
import io.github.taehunkim.jogakbo.record.AacEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Makes the original of a note from its source: a web page's article, a video's description and subtitles, or the
 * transcript of an audio file. Then starts the summary when the settings (or the user) ask for one.
 */
class SourceJob(
    private val context: Context,
    private val dao: NoteDao,
    private val http: HttpClient,
    private val models: ModelHost,
    private val settings: SettingsStore,
    private val jobs: NoteJobs,
    private val audioDir: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Result of one run: done (or failed for good), or try again later (no network, OndolSaem busy). */
    enum class Outcome { DONE, RETRY }

    suspend fun run(noteId: Long, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Outcome {
        val note = dao.get(noteId) ?: return Outcome.DONE
        dao.setSourceState(noteId, JobState.RUNNING)
        return try {
            when (note.kind) {
                NoteKind.LINK -> link(note)
                NoteKind.YOUTUBE -> video(note)
                NoteKind.AUDIO -> audio(note, progress)
                NoteKind.MEMO -> dao.setSourceState(noteId, JobState.NONE)
            }
            afterSource(noteId)
            Outcome.DONE
        } catch (e: CancellationException) {
            dao.setSourceState(noteId, JobState.QUEUED); throw e
        } catch (e: RetryLater) {
            dao.setSourceState(noteId, JobState.QUEUED, e.message); Outcome.RETRY
        } catch (e: Exception) {
            dao.setSourceState(noteId, JobState.FAILED, e.message ?: e.javaClass.simpleName); Outcome.DONE
        }
    }

    /** Thrown for passing trouble: the worker retries with back-off. */
    class RetryLater(message: String) : Exception(message)

    /** Starts the summary of a note whose original is ready, when the settings or the user ask for one. */
    suspend fun afterSource(noteId: Long) {
        val n = dao.get(noteId) ?: return
        jobs.noteChanged()
        if (n.sourceState != JobState.DONE) return
        val asked = runCatching { JSONObject(n.sourceMeta ?: "{}").takeIf { it.has("summarize") }?.optBoolean("summarize") }.getOrNull()
        val want = asked ?: (n.summaryPrompt != null || settings.autoSummary(n.kind).first())
        if (want && n.summary == null) {
            dao.setSummaryState(noteId, JobState.QUEUED)
            jobs.summarize(noteId)
        }
    }

    private fun meta(n: NoteEntity): JSONObject = runCatching { JSONObject(n.sourceMeta ?: "{}") }.getOrDefault(JSONObject())

    private suspend fun link(n: NoteEntity) {
        val url = n.sourceUrl ?: throw FetchException("no address")
        val r = try { http.get(url) } catch (e: IOException) { throw RetryLater(e.message ?: "network") }
        if (r.code >= 500) throw RetryLater("HTTP ${r.code}")
        if (r.code >= 400) throw FetchException("HTTP ${r.code}")
        val type = r.contentType.orEmpty().lowercase()
        val now = clock()
        val m = meta(n).put("fetchedAt", now).put("finalUrl", r.url)
        val (title, body) = if (type.isEmpty() || "html" in type || "xml" in type) {
            val a = ArticleExtractor.extract(r.body, r.url)
            a.site?.let { m.put("site", it) }; a.description?.let { m.put("description", it) }; a.published?.let { m.put("published", it) }
            a.title to Bodies.article(a)
        } else if (type.startsWith("text/")) {
            (r.url.substringAfterLast('/').ifBlank { r.url }) to r.body
        } else throw FetchException(context.getString(R.string.source_not_a_page, type))
        if (body.isBlank()) throw FetchException(context.getString(R.string.source_empty_page))
        val fresh = dao.get(n.id) ?: return
        dao.update(fresh.copy(title = title.take(200), body = body, sourceMeta = m.toString(), sourceState = JobState.DONE, sourceError = null, updatedAt = now))
    }

    private suspend fun video(n: NoteEntity) {
        val url = n.sourceUrl ?: throw FetchException("no address")
        val v = try { YouTubeFetcher(http).fetch(url) } catch (e: FetchException) { throw e } catch (e: IOException) { throw RetryLater(e.message ?: "network") }
        val now = clock()
        val m = meta(n).put("fetchedAt", now).put("videoId", v.id).put("description", v.description)
        v.channel?.let { m.put("channel", it) }; v.published?.let { m.put("published", it) }
        v.captionLanguage?.let { m.put("captionLanguage", it) }; m.put("autoCaptions", v.autoCaptions)
        val body = Bodies.video(
            v, context.getString(R.string.video_no_subtitles), context.getString(R.string.video_description), context.getString(R.string.video_transcript),
        )
        dao.replaceCues(n.id, v.cues.map { CueEntity(noteId = n.id, ordinal = 0, startMs = it.startMs, endMs = it.endMs, text = it.text) })
        val fresh = dao.get(n.id) ?: return
        dao.update(
            fresh.copy(
                title = v.title.take(200), body = body, sourceMeta = m.toString(), durationMs = v.lengthSec?.times(1000),
                sourceState = JobState.DONE, sourceError = null, updatedAt = now,
            ),
        )
    }

    /** An imported file, or a recording the app was killed during (its PCM is still there). */
    private suspend fun audio(n: NoteEntity, progress: (Int, Int) -> Unit) {
        val recording = meta(n).optBoolean("recording")
        val pcm = File(context.filesDir, "rec/${n.uuid}.pcm")
        val decoded = if (recording && pcm.exists()) pcm else {
            val src = n.audioFile?.let { File(audioDir(), it) }?.takeIf { it.exists() } ?: throw FetchException(context.getString(R.string.source_audio_missing))
            val tmp = File(context.cacheDir, "decode-${n.uuid}.pcm")
            AudioDecoder.decode(context, Uri.fromFile(src), tmp)
            tmp
        }
        try {
            val duration = Pcm.byteToMs(decoded.length())
            if (recording) {
                // the app stopped while recording: the playable file is made again from the raw audio
                n.audioFile?.let { AacEncoder.encodeFile(decoded, File(audioDir(), it)) }
            }
            models.speech().use { lease ->
                val speech = lease.value
                if (!speech.canTranscribe) throw FetchException(context.getString(R.string.source_no_asr))
                // resume after the last line already saved (a long file interrupted half-way)
                val have = dao.cues(n.id)
                val from = have.lastOrNull()?.endMs ?: 0L
                var ordinal = (have.lastOrNull()?.ordinal ?: -1) + 1
                speech.transcribe(decoded, from) { c ->
                    if (c.text.isBlank()) return@transcribe
                    dao.insertCues(listOf(CueEntity(noteId = n.id, ordinal = ordinal++, startMs = c.startMs, endMs = c.endMs, text = c.text.trim())))
                    progress(c.endMs.toInt() / 1000, (duration / 1000).toInt().coerceAtLeast(1))
                }
            }
            val cues = dao.cues(n.id).map { Cue(it.startMs, it.endMs, it.text) }
            val fresh = dao.get(n.id) ?: return
            val m = meta(fresh).apply { remove("recording") }
            dao.update(
                fresh.copy(
                    body = if (cues.isEmpty()) "*${context.getString(R.string.audio_no_speech)}*" else Bodies.transcript(cues),
                    durationMs = duration, sourceMeta = m.toString(), sourceState = JobState.DONE, sourceError = null, updatedAt = clock(),
                ),
            )
        } finally {
            if (decoded != pcm) decoded.delete()
            else if ((dao.get(n.id)?.sourceState) == JobState.DONE) pcm.delete()
        }
    }
}
