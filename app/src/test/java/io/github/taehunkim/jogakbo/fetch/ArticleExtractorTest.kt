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

    @Test fun charsetFromMeta() {
        val bytes = "<meta charset=\"euc-kr\"><p>한글</p>".toByteArray(charset("EUC-KR"))
        assertTrue(UrlConnectionHttp.decode(bytes, "text/html").contains("한글"))
    }
}
