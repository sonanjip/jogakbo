package io.github.taehunkim.jogakbo.summary

import io.github.taehunkim.jogakbo.ml.TextModel
import io.github.taehunkim.jogakbo.ml.isTooLong

/**
 * Summarizes text of any length with a model whose window is [TextModel.contextTokens]. Text that fits is
 * summarized in one call; longer text is cut into parts that fit, each part becomes notes, and the notes are
 * summarized (again in parts if they are still too long).
 */
class Summarizer(private val model: TextModel) {
    /** Calls made so far and an estimate of the total (for progress). */
    fun interface Progress { fun update(done: Int, total: Int) }

    suspend fun summarize(req: SummaryRequest, progress: Progress = Progress { _, _ -> }): String {
        var shrink = 1.0
        repeat(RETRIES) {
            try { return run(req, shrink, progress) } catch (e: Exception) {
                if (!e.isTooLong()) throw e
                shrink *= 0.6 // our estimate was too low for this text: smaller parts
            }
        }
        return run(req, shrink, progress)
    }

    /** Input tokens one call may use with [system] and the note's instruction. */
    fun budget(req: SummaryRequest, system: String, shrink: Double = 1.0): Int {
        val fixed = Tokens.estimate(system) + Tokens.estimate(PromptBuilder.user(req, "")) + TEMPLATE_TOKENS
        val answer = minOf(ANSWER_TOKENS, model.contextTokens / 4)
        return maxOf(MIN_PART_TOKENS, ((model.contextTokens - fixed - answer) * shrink).toInt())
    }

    private suspend fun run(req: SummaryRequest, shrink: Double, progress: Progress, depth: Int = 0, partNotes: Boolean = false): String {
        val system = PromptBuilder.system(req, fromPartNotes = partNotes)
        val budget = budget(req, system, shrink)
        if (Tokens.estimate(req.text) <= budget || depth >= MAX_DEPTH) {
            val text = if (depth >= MAX_DEPTH) Tokens.split(req.text, budget).first() else req.text
            progress.update(0, 1)
            return clean(model.generate(system, PromptBuilder.user(req, text))).also { progress.update(1, 1) }
        }
        val parts = Tokens.split(req.text, budget(req, PromptBuilder.partSystem(req, 99, 99), shrink))
        val notes = ArrayList<String>()
        parts.forEachIndexed { i, part ->
            progress.update(i, parts.size + 1)
            // the note's instruction is applied to the final summary only
            val partReq = req.copy(notePrompt = null)
            notes += clean(model.generate(PromptBuilder.partSystem(req, i + 1, parts.size), PromptBuilder.user(partReq, part)))
        }
        progress.update(parts.size, parts.size + 1)
        val joined = notes.mapIndexed { i, n -> "Part ${i + 1}:\n$n" }.joinToString("\n\n")
        return run(req.copy(text = joined), shrink, progress, depth + 1, partNotes = true)
    }

    companion object {
        const val ANSWER_TOKENS = 900
        const val TEMPLATE_TOKENS = 64
        const val MIN_PART_TOKENS = 300
        const val MAX_DEPTH = 3
        const val RETRIES = 3

        /** Drops a code fence around the whole answer and the model's own lead-ins. */
        fun clean(s: String): String {
            var t = s.trim()
            val fence = Regex("^```[a-zA-Z]*\\s*\\n([\\s\\S]*?)\\n?```$")
            fence.find(t)?.let { t = it.groupValues[1].trim() }
            return t
        }
    }
}
