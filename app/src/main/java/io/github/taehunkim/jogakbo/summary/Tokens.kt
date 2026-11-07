package io.github.taehunkim.jogakbo.summary

/**
 * Rough token counts without the model's tokenizer (OndolSaem does not expose one). Hangul, kana and CJK
 * characters count as one token each, everything else as one per [LATIN_CHARS_PER_TOKEN] characters. Errs on the
 * high side, so prompts stay inside the window.
 */
object Tokens {
    const val LATIN_CHARS_PER_TOKEN = 3.2

    fun estimate(text: String): Int {
        var wide = 0
        var other = 0
        for (ch in text) if (isWide(ch)) wide++ else other++
        return wide + Math.ceil(other / LATIN_CHARS_PER_TOKEN).toInt()
    }

    private fun isWide(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c) ?: return false
        return b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.HANGUL_JAMO ||
            b == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO || b == Character.UnicodeBlock.HIRAGANA ||
            b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    /**
     * Cuts [text] into pieces of at most [maxTokens] each, at paragraph, then line, then sentence, then word
     * boundaries (a hard cut only for one overlong word).
     */
    fun split(text: String, maxTokens: Int): List<String> {
        require(maxTokens > 0)
        if (estimate(text) <= maxTokens) return listOf(text.trim()).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var curTokens = 0
        fun flush() { cur.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }; cur.clear(); curTokens = 0 }
        for (unit in units(text, maxTokens)) {
            val t = estimate(unit)
            if (curTokens + t > maxTokens && cur.isNotEmpty()) flush()
            cur.append(unit); curTokens += t
        }
        flush()
        return out
    }

    /** The first and last `## ` heading of [part] ("Page 12", "Page 14"), or null when it has none. */
    fun sectionRange(part: String): Pair<String, String>? {
        val heads = part.lineSequence().filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim().take(40) }.toList()
        return if (heads.isEmpty()) null else heads.first() to heads.last()
    }

    /** Pieces of [text] (keeping their separators) that each fit [maxTokens]. */
    private fun units(text: String, maxTokens: Int): List<String> {
        // a "## " heading starts a section (a page, slide or sheet of a document): sections stay whole while they fit
        val levels = listOf(Regex("(?<=\\n)(?=## )"), Regex("(?<=\\n\\n)"), Regex("(?<=\\n)"), Regex("(?<=[.!?。！？]\\s)"), Regex("(?<=\\s)"))
        fun cut(s: String, level: Int): List<String> {
            if (estimate(s) <= maxTokens) return listOf(s)
            if (level >= levels.size) {
                // one huge "word": hard cut by characters
                val step = maxOf(1, maxTokens)
                return s.chunked(step)
            }
            return s.split(levels[level]).filter { it.isNotEmpty() }.flatMap { cut(it, level + 1) }
        }
        return cut(text, 0)
    }
}
