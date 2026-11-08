package io.github.taehunkim.jogakbo.notes

import org.json.JSONObject
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.data.db.NoteDao
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.fetch.YouTubeFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Starts and cancels the background work on notes (WorkManager in the app, direct calls in tests). */
interface NoteJobs {
    fun fetchSource(noteId: Long, kind: NoteKind)
    fun summarize(noteId: Long)
    fun cancel(noteId: Long)
    /** A note's text changed: embed it when the user's setting says so. */
    fun noteChanged()
}

/** Creating, editing and deleting notes. Background work is handed to [jobs]. */
class NoteRepository(
    private val context: Context,
    private val dao: NoteDao,
    private val jobs: NoteJobs,
    /** Whether Settings say "now" for summaries, search index and tags (a note can choose otherwise, see [Timing]). */
    private val defaultNow: suspend () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val audioDir: File get() = File(context.filesDir, "audio").apply { mkdirs() }

    fun audioFile(note: NoteEntity): File? = note.audioFile?.let { File(audioDir, it) }?.takeIf { it.exists() }

    val docDir: File get() = File(context.filesDir, "docs").apply { mkdirs() }

    /** The attached original of a document note (its name is in `sourceMeta`), if it is still there. */
    fun docFile(note: NoteEntity): File? = Documents.fileName(note)?.let { File(docDir, it) }?.takeIf { it.exists() }

    /** A new memo. [summarize]: also make a summary (with [prompt]). [timing]: this note's own choice of now or later. */
    suspend fun createMemo(title: String, body: String, summarize: Boolean = false, prompt: String? = null, timing: Boolean? = null): Long {
        val at = clock()
        val id = dao.insert(
            NoteEntity(
                uuid = UUID.randomUUID().toString(), kind = NoteKind.MEMO, title = titleFor(title, body, at), body = body,
                summaryPrompt = prompt?.trim()?.ifEmpty { null }, sourceMeta = Timing.withOverride(null, timing),
                createdAt = at, updatedAt = at,
            ),
        )
        if (summarize) autoSummary(id, prompt)
        jobs.noteChanged()
        return id
    }

    suspend fun updateMemo(id: Long, title: String, body: String) {
        val n = dao.get(id) ?: return
        val now = clock()
        dao.update(n.copy(title = titleFor(title, body, n.createdAt), body = body, updatedAt = now))
        jobs.noteChanged()
    }

    /** The user edited the summary: kept until they ask for a new one. */
    suspend fun updateSummary(id: Long, summary: String) {
        val n = dao.get(id) ?: return
        val text = summary.trim()
        dao.update(
            n.copy(
                summary = text.ifEmpty { null }, summaryEdited = text.isNotEmpty(),
                summaryState = if (text.isEmpty()) JobState.NONE else JobState.DONE, summaryError = null, updatedAt = clock(),
            ),
        )
        jobs.noteChanged()
    }

    /** A note from a web page or a YouTube video; its content is fetched in the background. */
    suspend fun addLink(url: String, prompt: String? = null, summarize: Boolean? = null, timing: Boolean? = null): Long {
        val clean = normalizeUrl(url)
        val kind = if (YouTubeFetcher.isYouTube(clean)) NoteKind.YOUTUBE else NoteKind.LINK
        val now = clock()
        val id = dao.insert(
            NoteEntity(
                uuid = UUID.randomUUID().toString(), kind = kind, title = clean, sourceUrl = clean,
                summaryPrompt = prompt?.trim()?.ifEmpty { null }, sourceState = JobState.QUEUED,
                // the source job starts the summary when it is done (the setting decides unless [summarize] is given)
                sourceMeta = Timing.withOverride(summarize?.let { """{"summarize":$it}""" }, timing),
                createdAt = now, updatedAt = now,
            ),
        )
        jobs.fetchSource(id, kind)
        return id
    }

    /** A note from an audio file: copied into the app (for playback), then transcribed in the background. */
    suspend fun addAudio(uri: Uri, prompt: String? = null, summarize: Boolean? = null, timing: Boolean? = null): Long {
        val uuid = UUID.randomUUID().toString()
        val name = displayName(uri) ?: "audio"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 } ?: "m4a"
        val fileName = "$uuid.$ext"
        withContext(Dispatchers.IO) {
            val target = File(audioDir, fileName)
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: throw java.io.IOException("cannot open $uri")
            } catch (e: Exception) { target.delete(); throw e }
        }
        val now = clock()
        val id = dao.insert(
            NoteEntity(
                uuid = uuid, kind = NoteKind.AUDIO, title = name.substringBeforeLast('.').ifBlank { name }, audioFile = fileName,
                summaryPrompt = prompt?.trim()?.ifEmpty { null }, sourceState = JobState.QUEUED,
                sourceMeta = Timing.withOverride(summarize?.let { """{"summarize":$it}""" }, timing),
                createdAt = now, updatedAt = now,
            ),
        )
        jobs.fetchSource(id, NoteKind.AUDIO)
        return id
    }

    /**
     * A note from a document (txt, md, PDF, docx, pptx, xlsx, odf): the file is copied into the app (so the original
     * stays openable), its text is read in the background and becomes the original of the note.
     */
    suspend fun addDocument(uri: Uri, prompt: String? = null, summarize: Boolean? = null, timing: Boolean? = null): Long {
        val uuid = UUID.randomUUID().toString()
        val name = displayName(uri) ?: "document"
        val ext = Documents.extensionOf(name, context.contentResolver.getType(uri))
        val fileName = "$uuid.$ext"
        val size = withContext(Dispatchers.IO) {
            val target = File(docDir, fileName)
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: throw java.io.IOException("cannot open $uri")
                target.length()
            } catch (e: Exception) { target.delete(); throw e }
        }
        val now = clock()
        val meta = JSONObject(Timing.withOverride(summarize?.let { """{"summarize":$it}""" }, timing) ?: "{}")
            .put(Documents.KEY_FILE, fileName).put(Documents.KEY_NAME, name).put(Documents.KEY_SIZE, size)
        val id = dao.insert(
            NoteEntity(
                uuid = uuid, kind = NoteKind.DOCUMENT, title = name.substringBeforeLast('.').ifBlank { name }.take(MAX_TITLE),
                summaryPrompt = prompt?.trim()?.ifEmpty { null }, sourceState = JobState.QUEUED, sourceMeta = meta.toString(),
                createdAt = now, updatedAt = now,
            ),
        )
        jobs.fetchSource(id, NoteKind.DOCUMENT)
        return id
    }

    /** A note for a recording that is about to start (RecordingService fills it). */
    suspend fun newRecording(title: String): NoteEntity {
        val now = clock()
        val uuid = UUID.randomUUID().toString()
        val note = NoteEntity(
            uuid = uuid, kind = NoteKind.AUDIO, title = title, audioFile = "$uuid.m4a", sourceState = JobState.RUNNING,
            sourceMeta = """{"recording":true}""", createdAt = now, updatedAt = now,
        )
        return note.copy(id = dao.insert(note))
    }

    /** The summary a new note asked for: started now, or kept waiting for the time chosen in Settings. */
    private suspend fun autoSummary(id: Long, prompt: String?) {
        val n = dao.get(id) ?: return
        if (Timing.isNow(n, defaultNow())) { requestSummary(id, prompt); return }
        dao.update(n.copy(summaryPrompt = prompt?.trim()?.ifEmpty { null } ?: n.summaryPrompt, summaryState = JobState.DEFERRED, summaryError = null))
    }

    /** Make (or remake) the summary with [prompt] (null keeps the note's last one), right away: the user asked. */
    suspend fun requestSummary(id: Long, prompt: String? = null) {
        val n = dao.get(id) ?: return
        dao.update(n.copy(summaryPrompt = prompt?.trim()?.ifEmpty { null } ?: n.summaryPrompt, summaryState = JobState.QUEUED, summaryError = null))
        jobs.summarize(id)
    }

    /** Try the source again (a link that failed to load, a transcription that failed). */
    suspend fun retrySource(id: Long) {
        val n = dao.get(id) ?: return
        dao.setSourceState(id, JobState.QUEUED)
        jobs.fetchSource(id, n.kind)
    }

    suspend fun delete(id: Long) {
        val n = dao.get(id) ?: return
        jobs.cancel(id)
        audioFile(n)?.delete()
        docFile(n)?.delete()
        dao.delete(id)
        jobs.noteChanged()
    }

    /** Frees the space of a note's audio; the transcript stays. */
    suspend fun deleteAudio(id: Long) {
        val n = dao.get(id) ?: return
        audioFile(n)?.delete()
        dao.update(n.copy(audioFile = null))
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    companion object {
        const val MAX_TITLE = 80

        /** The given title, else the first line of the text (without Markdown marks), else the date. */
        fun titleFor(title: String, body: String, createdAt: Long): String {
            title.trim().takeIf { it.isNotEmpty() }?.let { return it.take(MAX_TITLE) }
            val first = body.lineSequence().map { it.trim().trimStart('#', '>', '-', '*', ' ').replace(Regex("[*_`]"), "").trim() }
                .firstOrNull { it.isNotEmpty() && it != "---" }
            return first?.take(MAX_TITLE) ?: SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))
        }

        /** Adds https:// to a bare address and drops spaces around it. */
        fun normalizeUrl(url: String): String {
            val t = url.trim()
            return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
        }

        /** The first web address in a shared text ("Title https://…"), or null. */
        fun findUrl(text: String): String? = Regex("https?://[^\\s<>\"]+").find(text)?.value?.trimEnd('.', ',', ')', ']', '>', '"', '\'')
    }
}
