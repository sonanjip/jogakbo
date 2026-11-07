package io.github.taehunkim.jogakbo.search

import io.github.taehunkim.jogakbo.ml.Quant

/** A piece of one note that can be searched: a stored chunk (with its vector) or a freshly cut piece (without). */
class Passage(val ordinal: Int, val title: String?, val text: String, val embedding: ByteArray?, val summary: Boolean)

/** A passage with its score. */
class PassageHit(val passage: Passage, val score: Float)

/**
 * Ranks the passages of ONE note against a question, so a chat model reads only the relevant parts of a long note.
 * Raw cosine (one note has no baseline to judge against), or word counts when there are no usable vectors.
 */
object WithinNote {
    fun rank(passages: List<Passage>, qVec: FloatArray?, query: String, limit: Int): List<PassageHit> {
        if (qVec != null) {
            val scored = passages.mapNotNull { p -> p.embedding?.takeIf { Quant.dim(it) == qVec.size }?.let { PassageHit(p, Quant.dot(qVec, it)) } }
            if (scored.isNotEmpty()) return scored.sortedByDescending { it.score }.take(limit)
        }
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(8)
        if (words.isEmpty()) return emptyList()
        return passages.mapNotNull { p ->
            val hay = p.text.lowercase()
            val n = words.count { it in hay }
            if (n == 0) null else PassageHit(p, n.toFloat() / words.size + if (query.lowercase().trim() in hay) 1f else 0f)
        }.sortedByDescending { it.score }.take(limit)
    }
}
