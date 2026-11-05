package io.github.taehunkim.jogakbo.ml

/** A language model that writes one answer to one prompt (no history, no tools): what summaries need. */
interface TextModel {
    val modelId: String

    /** Tokens of prompt + answer the model takes (OndolSaem's setting). */
    val contextTokens: Int

    /** Writes the answer; [onToken] sees it as it streams. Fails with an exception on model errors. */
    suspend fun generate(system: String, user: String, onToken: (String) -> Unit = {}): String
}

/** The input was longer than the model's window (the host said "exceeds"). */
fun Throwable.isTooLong(): Boolean = generateSequence(this) { it.cause }.any { it.message?.contains("exceed", ignoreCase = true) == true }
