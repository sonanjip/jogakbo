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
        var calls = 0
        val counted = object : TextModel by model {
            override suspend fun generate(system: String, user: String, onToken: (String) -> Unit): String {
                check(++calls <= MAX_CALLS) { "Document summary reached the safety limit of $MAX_CALLS calls. Summarize smaller sections separately." }
                return model.generate(system, user, onToken)
            }
        }
        return Summarizer(counted).summarizeCounted(req, progress)
    }

    private suspend fun summarizeCounted(req: SummaryRequest, progress: Progress): String {
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
        if (Tokens.estimate(req.text) <= budget) {
            val text = req.text
            progress.update(0, 1)
            return clean(model.generate(system, PromptBuilder.user(req, text))).also { progress.update(1, 1) }
        }
        check(depth < MAX_DEPTH) { "Document summary did not shrink enough. Summarize smaller sections separately." }
        val parts = Tokens.split(req.text, budget(req, PromptBuilder.partSystem(req, 99, 99), shrink))
        val notes = ArrayList<String>()
        val labels = ArrayList<String>() // where in the document each part came from, kept through the levels
        parts.forEachIndexed { i, part ->
            labels += Tokens.sectionRange(part)?.let { (a, b) -> if (a == b) " ($a)" else " ($a – $b)" } ?: ""
            progress.update(i, parts.size + 1)
            // the note's instruction is applied to the final summary only
            val partReq = req.copy(notePrompt = null)
            notes += clean(model.generate(PromptBuilder.partSystem(req, i + 1, parts.size), PromptBuilder.user(partReq, part)))
        }
        progress.update(parts.size, parts.size + 1)
        val joined = notes.mapIndexed { i, n -> "## Part ${i + 1}${labels[i]}\n$n" }.joinToString("\n\n")
        return run(req.copy(text = joined), shrink, progress, depth + 1, partNotes = true)
    }

    companion object {
        const val ANSWER_TOKENS = 900
        const val TEMPLATE_TOKENS = 64
        const val MIN_PART_TOKENS = 300
        /** Levels of notes on notes. A 500-page document at a 4K window needs about four; the last level only cuts if it must. */
        const val MAX_DEPTH = 6
        const val MAX_CALLS = 256
        const val RETRIES = 3

        /** Drops a code fence around the whole answer, reasoning blocks, and the model's own lead-ins. */
        fun clean(s: String): String {
            var t = s.trim()
            t = Regex("<think>[\\s\\S]*?</think>").replace(t, "").trim()
            t = Regex("^<think>[\\s\\S]*$").replace(t, "").trim()
            val fence = Regex("^```[a-zA-Z]*\\s*\\n([\\s\\S]*?)\\n?```$")
            fence.find(t)?.let { t = it.groupValues[1].trim() }
            return t
        }
    }
}
