package io.github.taehunkim.jogakbo.io

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.taehunkim.jogakbo.data.db.CueEntity
import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.data.db.NoteDao
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export: a ZIP with one Markdown file per note (notes/…) and, when asked, the audio files and their transcript
 * lines (audio/…). Import reads such a ZIP, or a single .md/.txt file; a note already here (same uuid) is
 * replaced only by a newer version.
 */
class Backup(
    private val context: Context,
    private val dao: NoteDao,
    private val audioDir: () -> File,
    private val onImported: () -> Unit = {},
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class ExportResult(val notes: Int, val audio: Int)
    data class ImportResult(val added: Int, val updated: Int, val skipped: Int)

    suspend fun export(uri: Uri, includeAudio: Boolean, includeSummaries: Boolean): ExportResult = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "w")?.use { export(it, includeAudio, includeSummaries) } ?: throw java.io.IOException("cannot write $uri")
    }

    suspend fun export(out: OutputStream, includeAudio: Boolean, includeSummaries: Boolean): ExportResult = withContext(Dispatchers.IO) {
        var audio = 0
        val notes = dao.all()
        ZipOutputStream(out.buffered()).use { zip ->
            val used = HashSet<String>()
            for (n in notes) {
                val audioFile = n.audioFile?.let { File(audioDir(), it) }?.takeIf { includeAudio && it.exists() }
                val audioPath = audioFile?.let { "audio/${n.uuid}.${it.extension.ifEmpty { "m4a" }}" }
                val cues = dao.cues(n.id)
                val cuesPath = if (cues.isNotEmpty()) "audio/${n.uuid}.cues.json" else null
                var name = "notes/" + NoteMarkdown.fileName(n, zone)
                while (!used.add(name)) name = name.removeSuffix(".md") + "-1.md"
                zip.putNextEntry(ZipEntry(name))
                zip.write(NoteMarkdown.write(n, zone, includeSummaries, audioPath, cuesPath).toByteArray())
                zip.closeEntry()
                if (cuesPath != null) {
                    zip.putNextEntry(ZipEntry(cuesPath))
                    zip.write(cuesJson(cues).toByteArray()); zip.closeEntry()
                }
                if (audioFile != null && audioPath != null) {
                    zip.putNextEntry(ZipEntry(audioPath))
                    audioFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); audio++
                }
            }
        }
        ExportResult(notes.size, audio)
    }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: uri.lastPathSegment ?: "notes"
        context.contentResolver.openInputStream(uri)?.use { import(it, name) } ?: throw java.io.IOException("cannot read $uri")
    }

    suspend fun import(input: InputStream, name: String): ImportResult = withContext(Dispatchers.IO) {
        val buffered = input.buffered()
        buffered.mark(4)
        val magic = ByteArray(2).also { buffered.read(it) }
        buffered.reset()
        val result = if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) importZip(buffered)
        else {
            val n = NoteMarkdown.read(buffered.readBytes().toString(Charsets.UTF_8), name)
            tally(listOf(merge(n, emptyMap())))
        }
        if (result.added + result.updated > 0) onImported()
        result
    }

    private enum class Outcome { ADDED, UPDATED, SKIPPED }

    private fun tally(o: List<Outcome>) = ImportResult(o.count { it == Outcome.ADDED }, o.count { it == Outcome.UPDATED }, o.count { it == Outcome.SKIPPED })

    private suspend fun importZip(input: InputStream): ImportResult {
        // notes first need their files: keep the small files in memory, audio in a temp folder
        val tmp = File(context.cacheDir, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val texts = LinkedHashMap<String, String>()
            val files = HashMap<String, File>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val path = e.name.replace('\\', '/').trimStart('/')
                    if (e.isDirectory || path.contains("..") || path.substringAfterLast('/').startsWith(".") || path.startsWith("__MACOSX")) continue
                    val ext = path.substringAfterLast('.', "").lowercase()
                    if (ext == "md" || ext == "markdown" || ext == "txt" || ext == "json") texts[path] = zip.readBytes().toString(Charsets.UTF_8)
                    else files[path] = File(tmp, files.size.toString()).also { f -> f.outputStream().use { zip.copyTo(it) } }
                }
            }
            val outcomes = texts.filterKeys { !it.endsWith(".json") }.map { (path, text) ->
                val p = NoteMarkdown.read(text, path)
                merge(p, mapOf("cues" to p.cuesPath?.let { texts[it] }, "audio" to null), p.audioPath?.let { files[it] })
            }
            return tally(outcomes)
        } finally { tmp.deleteRecursively() }
    }

    private suspend fun merge(p: ParsedNote, extra: Map<String, String?>, audio: File? = null): Outcome {
        val now = clock()
        val existing = p.uuid?.let { dao.byUuid(it) }
        val updated = p.updatedAt ?: now
        if (existing != null && existing.updatedAt >= updated) return Outcome.SKIPPED
        val uuid = existing?.uuid ?: p.uuid ?: UUID.randomUUID().toString()
        val audioName = audio?.let { a ->
            val ext = p.audioPath?.substringAfterLast('.', "m4a") ?: "m4a"
            "$uuid.$ext".also { a.copyTo(File(audioDir(), it), overwrite = true) }
        } ?: existing?.audioFile
        val note = NoteEntity(
            id = existing?.id ?: 0, uuid = uuid, kind = p.kind, title = p.title, body = p.body,
            summary = p.summary, summaryEdited = p.summaryEdited, summaryPrompt = p.summaryPrompt,
            summaryState = if (p.summary != null) JobState.DONE else JobState.NONE,
            sourceUrl = p.sourceUrl, sourceMeta = p.sourceMeta, sourceState = if (p.sourceUrl != null || audioName != null) JobState.DONE else JobState.NONE,
            audioFile = audioName, durationMs = p.durationMs,
            createdAt = p.createdAt ?: existing?.createdAt ?: now, updatedAt = updated,
        )
        val id = if (existing != null) { dao.update(note); existing.id } else dao.insert(note)
        extra["cues"]?.let { json -> dao.replaceCues(id, parseCues(json)) }
        return if (existing != null) Outcome.UPDATED else Outcome.ADDED
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    companion object {
        fun cuesJson(cues: List<CueEntity>): String = JSONArray().apply {
            cues.forEach { put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)) }
        }.toString()

        fun parseCues(json: String): List<CueEntity> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { i -> a.getJSONObject(i).let { CueEntity(noteId = 0, ordinal = i, startMs = it.optLong("s"), endMs = it.optLong("e"), text = it.optString("t")) } }
        }.getOrDefault(emptyList())
    }
}
