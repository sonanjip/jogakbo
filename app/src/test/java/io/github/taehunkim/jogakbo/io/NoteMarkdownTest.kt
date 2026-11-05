package io.github.taehunkim.jogakbo.io

import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class NoteMarkdownTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val note = NoteEntity(
        uuid = "1234abcd-0000-0000-0000-000000000000", kind = NoteKind.LINK, title = "A \"quoted\": title",
        body = "## Original\n\nThe page text.\n\n<!-- a comment -->", summary = "- point one\n- point two", summaryEdited = true,
        summaryPrompt = "Focus: prices", sourceUrl = "https://example.com/a?b=c", sourceMeta = """{"site":"Ex"}""",
        createdAt = 1_700_000_000_000, updatedAt = 1_700_000_100_000, durationMs = 5000,
    )

    @Test fun roundTrip() {
        val md = NoteMarkdown.write(note, zone, includeSummary = true, audioPath = "audio/x.m4a", cuesPath = "audio/x.cues.json")
        val p = NoteMarkdown.read(md, "notes/x.md")
        assertEquals(note.uuid, p.uuid)
        assertEquals(NoteKind.LINK, p.kind)
        assertEquals(note.title, p.title)
        assertEquals(note.body, p.body)
        assertEquals(note.summary, p.summary)
        assertTrue(p.summaryEdited)
        assertEquals(note.summaryPrompt, p.summaryPrompt)
        assertEquals(note.sourceUrl, p.sourceUrl)
        assertEquals(note.sourceMeta, p.sourceMeta)
        assertEquals(note.createdAt, p.createdAt)
        assertEquals(note.updatedAt, p.updatedAt)
        assertEquals(5000L, p.durationMs)
        assertEquals("audio/x.m4a", p.audioPath)
        assertTrue(md.contains("# A \"quoted\": title\n"))
    }

    @Test fun withoutSummary() {
        val p = NoteMarkdown.read(NoteMarkdown.write(note, zone, includeSummary = false, null, null), "x.md")
        assertNull(p.summary); assertNull(p.summaryPrompt); assertEquals(note.body, p.body)
    }

    @Test fun plainMarkdownIsAMemo() {
        val p = NoteMarkdown.read("# Shopping\n\n- milk\n- eggs\n", "list.md")
        assertNull(p.uuid)
        assertEquals(NoteKind.MEMO, p.kind)
        assertEquals("Shopping", p.title)
        assertEquals("- milk\n- eggs", p.body)
        val q = NoteMarkdown.read("just text", "folder/idea.txt")
        assertEquals("idea", q.title); assertEquals("just text", q.body)
        val fm = NoteMarkdown.read("---\ntitle: Other app\ntags: [a]\n---\nBody", "o.md")
        assertEquals("Other app", fm.title); assertEquals("Body", fm.body); assertNull(fm.uuid)
    }

    @Test fun fileNames() {
        assertEquals("20231115-a-quoted-title-1234abcd.md", NoteMarkdown.fileName(note, zone))
        assertEquals("20231115-회의-메모-1234abcd.md", NoteMarkdown.fileName(note.copy(title = "회의 메모!"), zone))
    }
}
