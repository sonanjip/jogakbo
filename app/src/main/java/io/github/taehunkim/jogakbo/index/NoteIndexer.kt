package io.github.taehunkim.jogakbo.index

import io.github.taehunkim.jogakbo.data.db.ChunkEntity
import io.github.taehunkim.jogakbo.data.db.NoteDao
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.ml.Embedder
import io.github.taehunkim.jogakbo.ml.ModelHost
import io.github.taehunkim.jogakbo.ml.Quant
import io.github.taehunkim.jogakbo.ml.retryShorter
import io.github.taehunkim.jogakbo.text.MarkdownChunker
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Cuts notes into chunks (title, summary, original) and embeds them with OndolSaem's embedding model. A note is
 * embedded again when its text changed after the last time, or the model or the chunking changed ([key]).
 */
class NoteIndexer(private val dao: NoteDao, private val models: ModelHost, private val clock: () -> Long = System::currentTimeMillis) {
    /** Outcome of a pass. */
    data class Pass(val embedded: Int, val left: Int, val noModel: Boolean = false)

    /**
     * Embeds the notes that need it ([onProgress] gets (done, total)); stops early (the rest stays pending) when
     * [keepGoing] turns false. Notes [accept] refuses are left alone and not counted as left.
     */
    suspend fun embedPending(
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        keepGoing: () -> Boolean = { true },
        accept: (NoteEntity) -> Boolean = { true },
    ): Pass {
        val lease = models.embedder(background = true) ?: return Pass(0, -1, noModel = true)
        lease.use { l ->
            val key = key(l.value.modelId)
            val ids = dao.needingEmbedding(key)
            var done = 0
            var skipped = 0
            for (id in ids) {
                coroutineContext.ensureActive()
                if (!keepGoing()) break
                val note = dao.get(id) ?: continue
                if (!accept(note)) { skipped++; continue }
                onProgress(done, ids.size - skipped)
                embed(note, l.value, key)
                done++
            }
            return Pass(done, ids.size - done - skipped)
        }
    }

    suspend fun embed(note: NoteEntity, embedder: Embedder, key: String = key(embedder.modelId)) {
        val started = clock()
        val chunks = chunks(note).mapIndexed { i, c ->
            val v = retryShorter(c.text) { t -> embedder.embedDocument(t, c.title) }
            ChunkEntity(noteId = note.id, ordinal = i, text = c.text, title = c.title, modelId = embedder.modelId, embedding = Quant.encode(v))
        }
        // embeddedAt is when we read the note: an edit during embedding makes it newer, so it is embedded again
        dao.replaceChunks(note.id, chunks, started, key)
    }

    data class Piece(val text: String, val title: String?)

    companion object {
        /** Bump when the chunks are made differently (all notes are embedded again). */
        const val NOTE_VERSION = 1

        fun key(modelId: String) = "$modelId|n$NOTE_VERSION"

        /** How many of [chunks] come from the summary (they are first). */
        fun summaryChunkCount(note: NoteEntity): Int =
            note.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { MarkdownChunker().chunk(it, note.title).chunks.size } ?: 0

        /** The summary first (one chunk, it is short), then the original in heading-aware chunks. */
        fun chunks(note: NoteEntity): List<Piece> {
            val out = ArrayList<Piece>()
            note.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
                MarkdownChunker().chunk(s, note.title).chunks.forEach { out += Piece(it.text, note.title) }
            }
            val body = note.body.trim()
            if (body.isNotEmpty()) MarkdownChunker().chunk(body, note.title).chunks.forEach { c ->
                out += Piece(c.text, c.title?.let { t -> if (t == note.title) t else "${note.title} > $t" } ?: note.title)
            }
            if (out.isEmpty()) out += Piece(note.title, note.title)
            return out
        }
    }
}
