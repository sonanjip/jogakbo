package io.github.taehunkim.jogakbo.notes

import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.index.NoteIndexer
import io.github.taehunkim.jogakbo.ui.notes.videoAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {
    @Test fun titles() {
        assertEquals("Given", NoteRepository.titleFor(" Given ", "body", 0))
        assertEquals("Heading here", NoteRepository.titleFor("", "\n## **Heading** here\nmore", 0))
        assertTrue(NoteRepository.titleFor("", "  ", 0).matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }

    @Test fun urls() {
        assertEquals("https://a.com/x", NoteRepository.findUrl("Look at this: https://a.com/x."))
        assertEquals("https://example.com", NoteRepository.normalizeUrl(" example.com "))
        assertEquals("http://x.org", NoteRepository.normalizeUrl("http://x.org"))
        assertEquals("https://youtu.be/abc?t=75", videoAt("https://youtu.be/abc", 75_500))
        assertEquals("https://www.youtube.com/watch?v=abc&t=3", videoAt("https://www.youtube.com/watch?v=abc&t=99s", 3000))
    }

    @Test fun transcriptParagraphs() {
        val t = Bodies.transcript(listOf(Cue(0, 1000, "One."), Cue(5000, 6000, "Two."), Cue(40_000, 41_000, "Three.")))
        assertEquals("**0:00** One. Two.\n\n**0:40** Three.", t)
    }

    @Test fun chunksHaveSummaryAndBody() {
        val n = NoteEntity(uuid = "u", kind = NoteKind.MEMO, title = "T", body = "# A\n\ntext a\n\n# B\n\ntext b", summary = "- s", createdAt = 0, updatedAt = 0)
        val c = NoteIndexer.chunks(n)
        assertEquals("- s", c.first().text)
        assertTrue(c.drop(1).all { it.title!!.startsWith("T") })
        assertTrue(c.any { "text b" in it.text })
    }
}
