package io.github.taehunkim.jogakbo.ml

/** Produces L2-normalized text vectors (OndolSaem's embedding model). */
interface Embedder : AutoCloseable {
    val modelId: String

    /** Text to be indexed (a chunk of a note). Prefix handling is internal. */
    suspend fun embedDocument(text: String, title: String? = null): FloatArray

    /** User search query. Prefix handling is internal. */
    suspend fun embedQuery(text: String): FloatArray

    override fun close() {}
}

/**
 * The model has a hard 1024-token limit and no tokenizer is exposed, so an overlong input is only
 * discovered when the call fails ("input sequence length (N) exceeds ..."). Retry with a shorter
 * prefix of [text] (60% each time, at most [attempts] shortenings) before giving up.
 */
suspend fun <T> retryShorter(text: String, attempts: Int = 5, block: suspend (String) -> T): T {
    var t = text
    repeat(attempts) {
        try { return block(t) } catch (e: Exception) {
            if (e.message?.contains("exceeds", ignoreCase = true) != true) throw e
            t = t.take((t.length * 0.6).toInt()).trimEnd()
        }
    }
    return block(t)
}
