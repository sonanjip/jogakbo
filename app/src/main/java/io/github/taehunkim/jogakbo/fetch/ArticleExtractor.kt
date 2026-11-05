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
        ".ad, .ads, .advert, .advertisement, .banner, .share, .social, .related, .recommend, .comments, #comments, .cookie, .newsletter, .subscribe, .breadcrumb, .menu, .sidebar"

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
        if (md.length < 200) md = HtmlToMarkdown.convert(body).ifBlank { description.orEmpty() }
        return Article(title.trim(), site, description?.trim(), md, published)
    }

    private fun meta(doc: Document, property: String): String? =
        (doc.selectFirst("meta[property=$property]") ?: doc.selectFirst("meta[name=$property]"))?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    /** The element that holds the most paragraph text (paragraphs count for their parent and half for the grandparent). */
    fun best(body: Element): Element {
        body.selectFirst("[itemprop=articleBody]")?.let { if (it.text().length > 300) return it }
        val articles = body.select("article").filter { it.text().length > 300 }
        if (articles.size == 1) return articles[0]
        val score = HashMap<Element, Double>()
        for (p in body.select("p, pre, li, blockquote")) {
            val len = p.text().length
            if (len < 25) continue
            val weight = len + 10.0 * p.text().count { it == ',' || it == '.' }
            p.parent()?.let { score.merge(it, weight, Double::plus) }
            p.parent()?.parent()?.let { score.merge(it, weight / 2, Double::plus) }
        }
        val top = score.maxByOrNull { (e, s) -> s * (1 - linkDensity(e)) }?.key ?: return body
        return top
    }

    private fun linkDensity(e: Element): Double {
        val all = e.text().length.takeIf { it > 0 } ?: return 0.0
        val links = e.select("a").sumOf { it.text().length }
        return (links.toDouble() / all).coerceIn(0.0, 0.9)
    }
}
