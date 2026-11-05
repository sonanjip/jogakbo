package io.github.taehunkim.jogakbo.fetch

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Converts the readable part of an HTML page to Markdown (headings, paragraphs, lists, quotes, code, links, tables). */
object HtmlToMarkdown {
    fun convert(root: Element): String {
        val out = StringBuilder()
        block(root, out, listDepth = 0)
        return out.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun block(el: Element, out: StringBuilder, listDepth: Int) {
        for (child in el.childNodes()) {
            when (child) {
                is TextNode -> child.text().trim().takeIf { it.isNotEmpty() }?.let { para(out, escape(it)) }
                is Element -> element(child, out, listDepth)
            }
        }
    }

    private fun para(out: StringBuilder, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append(if (out.endsWith("\n")) "\n" else "\n\n")
        out.append(t).append("\n\n")
    }

    private fun element(e: Element, out: StringBuilder, listDepth: Int) {
        when (e.tagName()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = e.tagName()[1].digitToInt().coerceIn(2, 4) // the note's title is the h1
                inline(e).takeIf { it.isNotBlank() }?.let { para(out, "#".repeat(level) + " " + it) }
            }
            "p" -> para(out, inline(e))
            "ul", "ol" -> {
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append("\n")
                var n = 1
                for (li in e.children().filter { it.tagName() == "li" }) {
                    val marker = if (e.tagName() == "ol") "${n++}." else "-"
                    val nested = li.children().filter { it.tagName() == "ul" || it.tagName() == "ol" }
                    nested.forEach { it.remove() }
                    out.append("  ".repeat(listDepth)).append(marker).append(' ').append(inline(li)).append('\n')
                    nested.forEach { element(it, out, listDepth + 1) }
                }
                if (listDepth == 0) out.append('\n')
            }
            "blockquote" -> {
                val inner = StringBuilder().also { block(e, it, 0) }.toString().trim()
                if (inner.isNotEmpty()) para(out, inner.lines().joinToString("\n") { "> $it" })
            }
            "pre" -> para(out, "```\n" + e.wholeText().trimEnd() + "\n```")
            "table" -> table(e)?.let { para(out, it) }
            "br" -> out.append('\n')
            "hr" -> para(out, "---")
            "img", "figure", "picture", "video", "audio", "svg", "canvas", "iframe", "script", "style", "noscript", "button", "form", "input", "select" -> {
                if (e.tagName() == "figure") e.selectFirst("figcaption")?.let { para(out, "*" + inline(it) + "*") }
            }
            "div", "section", "article", "main", "header", "footer", "span", "body", "td", "th", "li", "dl", "dd", "dt", "center", "font" -> {
                // inline-only content becomes one paragraph, otherwise walk its blocks
                if (e.children().none { isBlock(it) }) para(out, inline(e)) else block(e, out, listDepth)
            }
            else -> if (e.children().any { isBlock(it) }) block(e, out, listDepth) else para(out, inline(e))
        }
    }

    private val BLOCKS = setOf("p", "div", "section", "article", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "table", "figure", "main", "header", "footer", "hr", "dl")
    private fun isBlock(e: Element) = e.tagName() in BLOCKS

    /** Inline content: text with bold, italic, code and links. */
    fun inline(e: Node): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            when (n) {
                is TextNode -> sb.append(escape(n.text()))
                is Element -> when (n.tagName()) {
                    "strong", "b" -> wrap(n, "**", sb)
                    "em", "i" -> wrap(n, "*", sb)
                    "code" -> n.text().takeIf { it.isNotBlank() }?.let { sb.append('`').append(it).append('`') }
                    "a" -> {
                        val text = inline(n).trim()
                        val href = n.absUrl("href")
                        if (text.isEmpty()) Unit
                        else if (href.startsWith("http")) sb.append('[').append(text).append("](").append(href.replace(")", "%29")).append(')')
                        else sb.append(text)
                    }
                    "br" -> sb.append("  \n")
                    "img", "script", "style", "svg", "button" -> Unit
                    else -> n.childNodes().forEach { walk(it) }
                }
            }
        }
        e.childNodes().forEach { walk(it) }
        return sb.toString().replace(Regex("[ \\t\\u00A0]+"), " ").replace(Regex(" *\\n *"), "\n").trim()
    }

    private fun wrap(n: Element, mark: String, sb: StringBuilder) {
        val t = inline(n).trim()
        if (t.isNotEmpty()) sb.append(mark).append(t).append(mark).append(' ')
    }

    private fun table(t: Element): String? {
        val rows = t.select("tr").map { r -> r.children().filter { it.tagName() == "td" || it.tagName() == "th" }.map { inline(it).replace("|", "\\|").replace("\n", " ") } }
            .filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null
        val width = rows.maxOf { it.size }
        val norm = rows.map { it + List(width - it.size) { "" } }
        val sb = StringBuilder()
        sb.append("| ").append(norm[0].joinToString(" | ")).append(" |\n")
        sb.append("|").append(" --- |".repeat(width)).append('\n')
        norm.drop(1).forEach { sb.append("| ").append(it.joinToString(" | ")).append(" |\n") }
        return sb.toString().trimEnd()
    }

    /** Characters that would start Markdown syntax at the beginning of a text are left as they are; only stray markers are escaped. */
    private fun escape(s: String): String = s.replace("*", "\\*").replace("_", "\\_").replace("`", "\\`")
}
