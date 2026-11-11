package io.github.taehunkim.jogakbo.notes

import io.github.taehunkim.jogakbo.data.db.*
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteProcessingStatusTest {
    private val note = NoteEntity(uuid = "u", kind = NoteKind.MEMO, title = "Title", createdAt = 10, updatedAt = 20)

    @Test fun indexCompletionRequiresCurrentTextAndModel() {
        assertEquals(ProcessingState.NONE, NoteProcessingStatus.index(note, "m"))
        val indexed = note.copy(embeddedAt = 20, embedKey = "m|n1")
        assertEquals(ProcessingState.DONE, NoteProcessingStatus.index(indexed, "m"))
        assertEquals(ProcessingState.STALE, NoteProcessingStatus.index(indexed.copy(updatedAt = 21), "m"))
        assertEquals(ProcessingState.STALE, NoteProcessingStatus.index(indexed, "other"))
    }

    @Test fun negativeAndUserDecisionsCountAsCompletedTags() {
        val tags = (1L..3L).map { TagEntity(id = it, name = "tag$it", ordinal = it.toInt(), createdAt = 1) }
        val rows = listOf(TagState.AUTO_NO, TagState.MANUAL, TagState.REJECTED).mapIndexed { i, state ->
            NoteTagName(1, i + 1L, "tag", state, null)
        }
        assertEquals(ProcessingState.DONE, NoteProcessingStatus.tags(tags, rows))
        assertEquals(ProcessingState.PARTIAL, NoteProcessingStatus.tags(tags, rows.take(1)))
        assertEquals(ProcessingState.NONE, NoteProcessingStatus.tags(tags, emptyList()))
        assertEquals(ProcessingState.NO_TAGS, NoteProcessingStatus.tags(emptyList(), rows))
    }

    @Test fun summaryShowsItsJobStateWithoutHidingFailuresBehindAnOldSummary() {
        assertEquals(ProcessingState.NONE, NoteProcessingStatus.summary(note))
        assertEquals(ProcessingState.DONE, NoteProcessingStatus.summary(note.copy(summary = "Saved", summaryState = JobState.DONE)))
        assertEquals(ProcessingState.WAITING, NoteProcessingStatus.summary(note.copy(summaryState = JobState.DEFERRED)))
        assertEquals(ProcessingState.RUNNING, NoteProcessingStatus.summary(note.copy(summaryState = JobState.RUNNING)))
        assertEquals(ProcessingState.FAILED, NoteProcessingStatus.summary(note.copy(summary = "Old", summaryState = JobState.FAILED)))
    }
}
