package io.github.taehunkim.jogakbo.fetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleExtractorTest {
    private val html = """
        <html><head><title>Site | Big News</title><meta property="og:title" content="Big News"><meta property="og:site_name" content="Daily">
        <meta name="description" content="What happened."></head>
        <body><header><nav><a href="/">Home</a><a href="/world">World</a></nav></header>
        <aside class="sidebar"><p>Subscribe to our newsletter for more stories every single day of the week.</p></aside>
        <div class="content"><h1>Big News</h1>
          <p>The first paragraph explains, at some length, what happened in the city yesterday and why it matters.</p>
          <h2>Details</h2>
          <p>A second paragraph with <strong>bold words</strong>, a <a href="/more">link</a> and more detail, so that it is long enough.</p>
          <ul><li>Point one is here</li><li>Point two is here</li></ul>
          <table><tr><th>Item</th><th>Price</th></tr><tr><td>Tea</td><td>3</td></tr></table>
        </div>
        <footer><p>Copyright notice that is long enough to count as a paragraph, but it is a footer.</p></footer></body></html>
    """.trimIndent()

    @Test fun extractsTheArticleAsMarkdown() {
        val a = ArticleExtractor.extract(html, "https://news.example.com/a/1")
        assertEquals("Big News", a.title)
        assertEquals("Daily", a.site)
        val md = a.markdown
        assertTrue(md, md.contains("The first paragraph explains"))
        assertTrue(md, md.contains("## Details"))
        assertTrue(md, md.contains("**bold words**"))
        assertTrue(md, md.contains("[link](https://news.example.com/more)"))
        assertTrue(md, md.contains("- Point one is here"))
        assertTrue(md, md.contains("| Item | Price |"))
        assertFalse(md, md.contains("newsletter"))
        assertFalse(md, md.contains("Copyright"))
        assertFalse(md, md.startsWith("# Big News")) // the title is not repeated
    }

    @Test fun wikiStyleReferencesAreNotTheArticle() {
        val para = (1..4).joinToString("") { "<p>Bojagi paragraph $it describes the wrapping cloth, its patchwork of scraps, its colours and its uses in daily life and rituals.</p>" }
        val refs = (1..30).joinToString("") { "<li id=\"cite_note-$it\"><a href=\"#r$it\">↑</a> Author $it (2010). <i>A long book title about Korean culture number $it</i>. Publisher, Seoul. p. $it. ISBN 978-1-56591-287-8.</li>" }
        val html = "<html><head><title>Bojagi - Wikipedia</title></head><body><div id=\"content\"><div class=\"mw-parser-output\">$para" +
            "<h2>References<span class=\"mw-editsection\">[edit]</span></h2><div class=\"reflist\"><ol class=\"references\">$refs</ol></div></div></div></body></html>"
        val md = ArticleExtractor.extract(html, "https://en.wikipedia.org/wiki/Bojagi").markdown
        assertTrue(md, md.startsWith("Bojagi paragraph 1"))
        assertFalse(md, md.contains("ISBN"))
        assertFalse(md, md.contains("[edit]"))
    }

    @Test fun sectionsAreKeptTogether() {
        fun sec(n: Int) = "<section class=\"mf-section-$n\">" + (1..3).joinToString("") { "<p>Section $n paragraph $it tells a part of the story with enough words to count, really.</p>" } + "</section>"
        val html = "<html><body><div id=\"bodyContent\"><div class=\"mw-parser-output\">${sec(0)}<h2>History</h2>${sec(1)}<h2>Use</h2>${sec(2)}</div></div><footer><p>Footer text that is long enough to be a paragraph here.</p></footer></body></html>"
        val md = ArticleExtractor.extract(html, "https://en.m.wikipedia.org/wiki/X").markdown
        assertTrue(md, md.contains("Section 0 paragraph 1") && md.contains("Section 2 paragraph 3") && md.contains("## History"))
        assertFalse(md, md.contains("Footer"))
    }

    @Test fun trailingSectionsAreDropped() {
        val body = (1..20).joinToString("\n\n") { "Paragraph $it of the article." }
        assertEquals(body, ArticleExtractor.dropTrailingSections("$body\n\n## See also\n\n- [X](https://x)\n\n## References"))
        val early = "## Notes\n\n$body"
        assertEquals(early, ArticleExtractor.dropTrailingSections(early)) // a "Notes" heading at the start stays
    }

    @Test fun charsetFromMeta() {
        val bytes = "<meta charset=\"euc-kr\"><p>한글</p>".toByteArray(charset("EUC-KR"))
        assertTrue(UrlConnectionHttp.decode(bytes, "text/html").contains("한글"))
    }
}
