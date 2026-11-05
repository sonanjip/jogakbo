package io.github.taehunkim.jogakbo.editor

import io.github.taehunkim.jogakbo.ui.editor.Edit
import io.github.taehunkim.jogakbo.ui.editor.MarkdownActions
import io.github.taehunkim.jogakbo.ui.editor.MarkdownActions.Prefix
import io.github.taehunkim.jogakbo.ui.editor.MarkdownActions.Wrap
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownActionsTest {
    private fun sel(text: String, s: Int, e: Int = s) = Edit(text, s, e)

    @Test fun boldWrapsAndUnwraps() {
        val b = MarkdownActions.wrap(sel("say hello now", 4, 9), Wrap.BOLD)
        assertEquals("say **hello** now", b.text)
        assertEquals("hello", b.text.substring(b.start, b.end))
        val back = MarkdownActions.wrap(b, Wrap.BOLD)
        assertEquals("say hello now", back.text)
    }

    @Test fun cursorGetsEmptyPair() {
        val e = MarkdownActions.wrap(sel("ab", 1), Wrap.ITALIC)
        assertEquals("a**b", e.text)
        assertEquals(2, e.start)
    }

    @Test fun spacesStayOutsideMarks() {
        val e = MarkdownActions.wrap(sel("x hello y", 1, 8), Wrap.STRIKE)
        assertEquals("x ~~hello~~ y", e.text)
    }

    @Test fun italicDoesNotEatBold() {
        val bold = "a **b** c"
        val e = MarkdownActions.wrap(sel(bold, 4, 5), Wrap.ITALIC)
        assertEquals("a ***b*** c", e.text)
    }

    @Test fun headingTogglesAndReplacesLevel() {
        val h2 = MarkdownActions.heading(sel("Title\nbody", 2), 2)
        assertEquals("## Title\nbody", h2.text)
        assertEquals("### Title\nbody", MarkdownActions.heading(h2, 3).text)
        assertEquals("Title\nbody", MarkdownActions.heading(MarkdownActions.heading(h2, 3), 3).text)
    }

    @Test fun listsOverSeveralLines() {
        val t = "one\ntwo\nthree"
        val b = MarkdownActions.prefix(sel(t, 0, t.length), Prefix.BULLET)
        assertEquals("- one\n- two\n- three", b.text)
        assertEquals(t, MarkdownActions.prefix(b, Prefix.BULLET).text)
        assertEquals("1. one\n2. two\n3. three", MarkdownActions.prefix(b, Prefix.NUMBERED).text)
        assertEquals("- [ ] one\n- [ ] two\n- [ ] three", MarkdownActions.prefix(sel(t, 0, t.length), Prefix.CHECKBOX).text)
        assertEquals("> one\n> two\n> three", MarkdownActions.prefix(sel(t, 0, t.length), Prefix.QUOTE).text)
    }

    @Test fun onlyTouchedLinesChange() {
        val t = "a\nb\nc"
        assertEquals("a\n- b\nc", MarkdownActions.prefix(sel(t, 3), Prefix.BULLET).text)
    }

    @Test fun codeFencesMultiline() {
        val t = "x\ny"
        assertEquals("```\nx\ny\n```", MarkdownActions.wrap(sel(t, 0, 3), Wrap.CODE).text)
        assertEquals("`x`\ny", MarkdownActions.wrap(sel(t, 0, 1), Wrap.CODE).text)
    }

    @Test fun linkAndRule() {
        val l = MarkdownActions.link(sel("see here", 4, 8), "here", "https://a.b/c d")
        assertEquals("see [here](https://a.b/c%20d)", l.text)
        val r = MarkdownActions.rule(sel("para", 4))
        assertEquals("para\n\n---\n", r.text)
    }
}
