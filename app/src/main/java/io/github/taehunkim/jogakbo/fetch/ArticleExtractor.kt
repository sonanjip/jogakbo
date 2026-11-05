package io.github.taehunkim.jogakbo.fetch

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** The readable part of a web page. */
data class Article(val title: String, val site: String?, val description: String?, val markdown: String, val published: String?)

/**
 * Finds the main text of a page (a simple readability: the element holding the most paragraph text, after
 * removing navigation, ads and other chrome) and converts it to Markdown.
 */
object ArticleExtractor {
    private const val NOISE = "script, style, noscript, template, nav, aside, footer, form, iframe, svg, button, select, " +
        "[role=navigation], [role=banner], [role=contentinfo], [role=complementary], [aria-hidden=true], [hidden], " +
        ".ad, .ads, .advert, .advertisement, .banner, .share, .social, .related, .recommend, .comments, #comments, .cookie, .newsletter, .subscribe, .breadcrumb, .menu, .sidebar, " +
        // reference lists, footnote markers and wiki chrome
        "ol.references, .reflist, .references, .mw-references-wrap, .footnotes, sup.reference, .mw-editsection, .navbox, .vertical-navbox, " +
        ".catlinks, #toc, .toc, .hatnote, .metadata, .noprint, .mw-jump-link, [role=note]"

    fun extract(html: String, url: String): Article {
        val doc = Jsoup.parse(html, url)
        val title = meta(doc, "og:title") ?: doc.title().takeIf { it.isNotBlank() } ?: doc.selectFirst("h1")?.text() ?: url
        val site = meta(doc, "og:site_name") ?: runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
        val description = meta(doc, "og:description") ?: doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
        val published = meta(doc, "article:published_time") ?: doc.selectFirst("time[datetime]")?.attr("datetime")
        val body = doc.body() ?: return Article(title.trim(), site, description, description.orEmpty(), published)
        body.select(NOISE).remove()
        // header elements outside the article are page chrome; inside an article they often hold its title
        body.select("header").filter { it.parents().none { p -> p.tagName() == "article" } }.forEach { it.remove() }
        val main = best(body)
        var md = HtmlToMarkdown.convert(main)
        // the article's own heading repeats the title
        md = md.lines().dropWhile { it.isBlank() }.let { ls ->
            if (ls.firstOrNull()?.trimStart('#', ' ')?.trim() == title.trim()) ls.drop(1) else ls
        }.joinToString("\n").trim()
        md = dropTrailingSections(md)
        if (md.length < 200) md = HtmlToMarkdown.convert(body).ifBlank { description.orEmpty() }
        return Article(title.trim(), site, description?.trim(), md, published)
    }

    private val TAIL = Regex("^#{2,4} *(see also|references|notes|footnotes|external links|further reading|bibliography|sources|citations|related articles|같이 보기|참고 ?문헌|각주|외부 링크|출처|関連項目|脚注|参考文献|外部リンク)([^\\p{L}].*)?$", RegexOption.IGNORE_CASE)

    /** Cuts the "See also / References / External links" tail of an article (headings in its second half). */
    fun dropTrailingSections(md: String): String {
        val lines = md.lines()
        val half = md.length / 2
        var pos = 0
        for ((i, l) in lines.withIndex()) {
            if (pos > half && TAIL.matches(l.trim())) return lines.take(i).joinToString("\n").trim()
            pos += l.length + 1
        }
        return md
    }

    private fun meta(doc: Document, property: String): String? =
        (doc.selectFirst("meta[property=$property]") ?: doc.selectFirst("meta[name=$property]"))?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    /** The element that holds the most paragraph text (paragraphs count for their parent and half for the grandparent). */
    fun best(body: Element): Element {
        body.selectFirst("[itemprop=articleBody]")?.let { if (it.text().length > 300) return it }
        val articles = body.select("article").filter { it.text().length > 300 }
        if (articles.size == 1) return articles[0]
        val score = HashMap<Element, Double>()
        // paragraph text in each element's whole subtree
        val total = HashMap<Element, Double>()
        for (p in body.select("p, pre, li, blockquote")) {
            val len = p.text().length
            if (len < 25) continue
            if (p.parents().any { NEGATIVE.containsMatchIn(it.className() + " " + it.id()) }) continue
            // paragraphs carry an article; list items (link lists, references) count less
            val weight = (len + 10.0 * p.text().count { it == ',' || it == '.' }) * (if (p.tagName() == "p") 1.0 else 0.4)
            p.parent()?.let { score.merge(it, weight, Double::plus) }
            p.parent()?.parent()?.let { score.merge(it, weight / 2, Double::plus) }
            for (anc in p.parents()) { total.merge(anc, weight, Double::plus); if (anc == body) break }
        }
        fun adjusted(e: Element) = (score[e] ?: 0.0) * (1 - linkDensity(e)) * (if (POSITIVE.containsMatchIn(e.className() + " " + e.id())) 1.25 else 1.0)
        val top = score.keys.maxByOrNull { adjusted(it) } ?: return body
        // an article split into sections (Wikipedia, blogs): walk up through wrappers, and take an ancestor that
        // holds clearly more paragraph text (the other sections); stop where only a little more text joins (page chrome)
        var best = top
        var cur = top
        while (cur != body) {
            val parent = cur.parent() ?: break
            val ratio = (total[parent] ?: 0.0) / (total[cur] ?: 1.0).coerceAtLeast(1.0)
            when {
                ratio >= 1.5 -> { best = parent; cur = parent }
                ratio <= 1.1 -> cur = parent
                else -> break
            }
        }
        return if (best == body.parent()) body else best
    }

    private val NEGATIVE = Regex("comment|reference|footnote|citation|sidebar|related|share|social|promo|sponsor|footer|navbox|nav-|menu|widget", RegexOption.IGNORE_CASE)
    private val POSITIVE = Regex("article|content|entry|post|story|body|main|text|parser-output", RegexOption.IGNORE_CASE)

    private fun linkDensity(e: Element): Double {
        val all = e.text().length.takeIf { it > 0 } ?: return 0.0
        val links = e.select("a").sumOf { it.text().length }
        return (links.toDouble() / all).coerceIn(0.0, 0.9)
    }
}
