package io.github.taehunkim.jogakbo.tags

import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.data.db.TagState
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTaggerTest {
    @Test fun statesByProbability() {
        assertEquals(TagState.AUTO_YES, NoteTagger.stateFor(0.7f))
        assertEquals(TagState.SUGGESTED, NoteTagger.stateFor(0.4f))
        assertEquals(TagState.AUTO_NO, NoteTagger.stateFor(0.39f))
    }

    @Test fun premiseIsTheTitleAndTheSummaryOrTheOriginalWithoutMarkdown() {
        val n = NoteEntity(uuid = "u", kind = NoteKind.MEMO, title = "Trip", body = "# Plan\n\n- **Jeju** `x`", createdAt = 0, updatedAt = 0)
        assertEquals("Trip Plan - Jeju x", NoteTagger.premise(n))
        assertEquals("Trip short", NoteTagger.premise(n.copy(summary = "short")))
        assertEquals(1500, NoteTagger.premise(n.copy(body = "a".repeat(5000))).length)
    }
}
