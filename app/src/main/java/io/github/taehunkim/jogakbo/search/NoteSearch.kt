package io.github.taehunkim.jogakbo.search

import io.github.taehunkim.jogakbo.data.db.ChunkVector
import io.github.taehunkim.jogakbo.data.db.NoteDao
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.index.NoteIndexer
import io.github.taehunkim.jogakbo.ml.Quant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Filters of a search. Dates are epoch ms on the note's creation time. */
data class NoteFilter(val kinds: Set<NoteKind> = emptySet(), val fromMs: Long? = null, val toMs: Long? = null)

/** One found note: [snippet] is the best matching piece of text. */
data class NoteHit(val note: NoteEntity, val score: Double, val snippet: String, val semantic: Double, val keyword: Double)

/**
 * Finds notes by meaning (the query's embedding against every chunk, kept in memory) and by words (all words of
 * the query in the title, summary or original). A note's semantic score is its best chunk; notes far below the
 * best one are dropped, so only related notes come back.
 */
class NoteSearch(private val dao: NoteDao, private val embedQuery: suspend (String) -> Pair<String, FloatArray>?) {
    private val cacheLock = Mutex()
    private var cacheKey: String? = null
    private var cache: List<ChunkVector> = emptyList()

    suspend fun search(query: String, filter: NoteFilter = NoteFilter(), limit: Int = 30): List<NoteHit> = withContext(Dispatchers.Default) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(8)

        // words
        val keyword = HashMap<Long, Double>()
        val notes = HashMap<Long, NoteEntity>()
        for (n in dao.keywordCandidates("%" + escapeLike(words.maxByOrNull { it.length }!!) + "%", KEYWORD_CANDIDATES)) {
            val hay = (n.title + "\n" + (n.summary ?: "") + "\n" + n.body).lowercase()
            val matched = words.count { it in hay }
            if (matched == words.size) {
                keyword[n.id] = if (q.lowercase() in hay) 1.0 else 0.6 + 0.4 * matched / words.size
                notes[n.id] = n
            }
        }

        // meaning
        val semantic = HashMap<Long, Pair<Double, Long>>() // note -> (similarity, best chunk)
        val embedded = runCatching { embedQuery(q) }.getOrNull()
        if (embedded != null) {
            val (modelId, v) = embedded
            for (c in vectors(modelId)) {
                val s = Quant.dot(v, c.embedding).toDouble()
                val cur = semantic[c.noteId]
                if (cur == null || s > cur.first) semantic[c.noteId] = s to c.id
            }
        }
        val top = semantic.values.maxOfOrNull { it.first } ?: 0.0
        val floor = maxOf(MIN_SIMILARITY, top - BAND)
        val kept = semantic.filterValues { it.first >= floor }

        val ids = (keyword.keys + kept.keys)
        val missing = ids.filter { it !in notes }
        if (missing.isNotEmpty()) dao.getAll(missing).forEach { notes[it.id] = it }
        val bestChunks = dao.chunks(kept.values.map { it.second }).associateBy { it.id }
        ids.mapNotNull { id ->
            val n = notes[id] ?: return@mapNotNull null
            if (!filter.matches(n)) return@mapNotNull null
            val sem = kept[id]?.first ?: 0.0
            val semScore = if (sem > 0) (sem - floor) / maxOf(BAND, top - floor) * 0.5 + 0.5 else 0.0
            val kw = keyword[id] ?: 0.0
            val snippet = kept[id]?.second?.let { bestChunks[it]?.text } ?: keywordSnippet(n, words)
            NoteHit(n, KEYWORD_WEIGHT * kw + semScore + if (kw > 0 && sem > 0) BOTH_BONUS else 0.0, snippet(snippet), sem, kw)
        }.sortedByDescending { it.score }.take(limit)
    }

    /** What [within] found: the best passages, whether they were ranked by meaning, and how many passages the note has. */
    class Within(val hits: List<PassageHit>, val semantic: Boolean, val total: Int)

    /**
     * The passages of one [note] closest to [query]. By meaning when the note's stored chunks are current and made by the
     * installed embedding model; otherwise the note is cut afresh and ranked by words (a note not embedded yet still works).
     */
    suspend fun within(note: NoteEntity, query: String, limit: Int): Within = withContext(Dispatchers.Default) {
        val q = runCatching { embedQuery(query.trim()) }.getOrNull()
        val stored = dao.chunksOf(note.id)
        val summaryCount = NoteIndexer.summaryChunkCount(note)
        val current = q != null && stored.isNotEmpty() && (note.embeddedAt ?: 0L) >= note.updatedAt && stored.all { it.modelId == q.first }
        val passages = if (current) stored.map { Passage(it.ordinal, it.title, it.text, it.embedding, it.ordinal < summaryCount) }
        else NoteIndexer.chunks(note).mapIndexed { i, p -> Passage(i, p.title, p.text, null, i < summaryCount) }
        Within(WithinNote.rank(passages, if (current) q?.second else null, query, limit), current, passages.size)
    }

    private fun NoteFilter.matches(n: NoteEntity): Boolean =
        (kinds.isEmpty() || n.kind in kinds) && (fromMs == null || n.createdAt >= fromMs) && (toMs == null || n.createdAt < toMs)

    private suspend fun vectors(modelId: String): List<ChunkVector> = cacheLock.withLock {
        val key = modelId + "|" + dao.chunkStamp()
        if (key != cacheKey) { cache = dao.vectors(modelId); cacheKey = key }
        cache
    }

    companion object {
        const val MIN_SIMILARITY = 0.30
        const val BAND = 0.12
        const val KEYWORD_WEIGHT = 1.2
        const val BOTH_BONUS = 0.15
        const val KEYWORD_CANDIDATES = 500
        const val SNIPPET = 160

        fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        private fun keywordSnippet(n: NoteEntity, words: List<String>): String {
            for (text in listOfNotNull(n.summary, n.body)) {
                val i = text.lowercase().indexOf(words.first())
                if (i >= 0) return text.substring(maxOf(0, i - 40))
            }
            return n.summary ?: n.body
        }

        fun snippet(s: String): String {
            val flat = s.lines().joinToString(" ") { it.trim().replace(Regex("^([-*+] (\\[[ xX]\\] )?|\\d+[.)] )"), "") }
                .replace(Regex("[#*_`>|]"), "").replace(Regex("\\s+"), " ").trim()
            return if (flat.length <= SNIPPET) flat else flat.take(SNIPPET).trimEnd() + "…"
        }
    }
}
