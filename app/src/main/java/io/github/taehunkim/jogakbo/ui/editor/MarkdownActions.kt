package io.github.taehunkim.jogakbo.ui.editor

/** Text with a selection ([start] ≤ [end]; equal = a cursor). */
data class Edit(val text: String, val start: Int, val end: Int) {
    init { require(start in 0..end && end <= text.length) }
}

/** What the formatting toolbar does, without Compose (unit-tested). */
object MarkdownActions {
    enum class Wrap(val mark: String) { BOLD("**"), ITALIC("*"), STRIKE("~~"), CODE("`") }
    enum class Prefix { BULLET, NUMBERED, CHECKBOX, QUOTE }

    /** Bold, italic, strikethrough or inline code around the selection; again removes it. A cursor gets an empty pair. */
    fun wrap(e: Edit, w: Wrap): Edit {
        val m = w.mark
        if (w == Wrap.CODE && e.text.substring(e.start, e.end).contains('\n')) return fence(e)
        // already wrapped just outside the selection: unwrap
        if (e.start >= m.length && e.end + m.length <= e.text.length &&
            e.text.substring(e.start - m.length, e.start) == m && e.text.substring(e.end, e.end + m.length) == m &&
            !(w == Wrap.ITALIC && isBoldAround(e))
        ) {
            val t = e.text.removeRange(e.end, e.end + m.length).removeRange(e.start - m.length, e.start)
            return Edit(t, e.start - m.length, e.end - m.length)
        }
        val sel = e.text.substring(e.start, e.end)
        // the selection itself carries the marks: unwrap
        if (sel.length >= 2 * m.length && sel.startsWith(m) && sel.endsWith(m) && !(w == Wrap.ITALIC && sel.startsWith("**") && !sel.startsWith("***"))) {
            val inner = sel.substring(m.length, sel.length - m.length)
            return Edit(e.text.replaceRange(e.start, e.end, inner), e.start, e.start + inner.length)
        }
        // marks hug the text: keep the selection's outer spaces outside the marks
        val lead = sel.length - sel.trimStart().length
        val trail = sel.length - sel.trimEnd().length
        val core = sel.trim()
        val replaced = sel.take(lead) + m + core + m + sel.takeLast(trail).let { if (core.isEmpty()) "" else it }
        val t = e.text.replaceRange(e.start, e.end, if (core.isEmpty()) m + m else replaced)
        val s = e.start + (if (core.isEmpty()) 0 else lead) + m.length
        return Edit(t, s, s + core.length)
    }

    private fun isBoldAround(e: Edit): Boolean =
        e.start >= 2 && e.end + 2 <= e.text.length && e.text.substring(e.start - 2, e.start) == "**" && e.text.substring(e.end, e.end + 2) == "**" &&
            !(e.start >= 3 && e.text[e.start - 3] == '*')

    /** A fenced code block around the selected lines. */
    fun fence(e: Edit): Edit {
        val (ls, le) = lineRange(e)
        val block = e.text.substring(ls, le)
        val t = e.text.replaceRange(ls, le, "```\n$block\n```")
        return Edit(t, ls + 4, ls + 4 + block.length)
    }

    /** Heading of [level] (1–3) on the selected lines; the same level again makes them plain lines. */
    fun heading(e: Edit, level: Int): Edit = mapLines(e) { lines ->
        val mark = "#".repeat(level) + " "
        val all = lines.all { it.startsWith(mark) }
        lines.map { l ->
            val plain = l.replace(Regex("^#{1,6} "), "")
            if (all) plain else mark + plain
        }
    }

    /** A list, checkbox list or quote on the selected lines; again removes it. */
    fun prefix(e: Edit, p: Prefix): Edit = mapLines(e) { lines ->
        val content = lines.filter { it.isNotBlank() }
        val has: (String) -> Boolean = when (p) {
            Prefix.BULLET -> { l -> Regex("^\\s*[-*+] (?!\\[[ xX]\\] )").containsMatchIn(l) }
            Prefix.NUMBERED -> { l -> Regex("^\\s*\\d+[.)] ").containsMatchIn(l) }
            Prefix.CHECKBOX -> { l -> Regex("^\\s*[-*+] \\[[ xX]\\] ").containsMatchIn(l) }
            Prefix.QUOTE -> { l -> l.startsWith(">") }
        }
        val remove = content.isNotEmpty() && content.all(has)
        var n = 0
        lines.map { l ->
            if (l.isBlank() && lines.size > 1) return@map l
            val indent = l.takeWhile { it == ' ' }
            val bare = l.trimStart().replace(Regex("^([-*+] \\[[ xX]\\] |[-*+] |\\d+[.)] |> ?)"), "")
            if (remove) indent + bare
            else when (p) {
                Prefix.BULLET -> "$indent- $bare"
                Prefix.NUMBERED -> "$indent${++n}. $bare"
                Prefix.CHECKBOX -> "$indent- [ ] $bare"
                Prefix.QUOTE -> "> " + l.removePrefix("> ").removePrefix(">")
            }
        }
    }

    /** `[label](url)` in place of the selection. */
    fun link(e: Edit, label: String, url: String): Edit {
        val l = label.ifBlank { url }.replace("]", "\\]")
        val u = url.trim().replace(" ", "%20").replace(")", "%29")
        val s = "[$l]($u)"
        return Edit(e.text.replaceRange(e.start, e.end, s), e.start + s.length, e.start + s.length)
    }

    /** A horizontal rule on its own line after the cursor's line. */
    fun rule(e: Edit): Edit {
        val (_, le) = lineRange(e)
        val before = if (le == 0 || e.text.substring(0, le).endsWith("\n\n")) "" else if (e.text.substring(0, le).endsWith("\n")) "\n" else "\n\n"
        val ins = "$before---\n"
        val t = e.text.substring(0, le) + ins + e.text.substring(le).removePrefix("\n")
        val c = le + ins.length
        return Edit(t, c, c)
    }

    /** Start and end of the lines the selection touches. */
    fun lineRange(e: Edit): Pair<Int, Int> {
        val s = e.text.lastIndexOf('\n', (e.start - 1).coerceAtLeast(0)).let { if (e.start == 0 || it < 0) 0 else if (e.text[e.start - 1] == '\n') e.start else it + 1 }
        val endAt = if (e.end > e.start && e.text[e.end - 1] == '\n') e.end - 1 else e.end
        val le = e.text.indexOf('\n', endAt).let { if (it < 0) e.text.length else it }
        return s to maxOf(s, le)
    }

    private fun mapLines(e: Edit, f: (List<String>) -> List<String>): Edit {
        val (ls, le) = lineRange(e)
        val old = e.text.substring(ls, le)
        val new = f(old.split('\n')).joinToString("\n")
        val t = e.text.replaceRange(ls, le, new)
        // a cursor stays at the end of its line; a selection covers the changed lines
        return if (e.start == e.end) Edit(t, ls + new.length, ls + new.length) else Edit(t, ls, ls + new.length)
    }
}
