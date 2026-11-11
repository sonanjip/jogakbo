package io.github.taehunkim.jogakbo.notes

import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteTagName
import io.github.taehunkim.jogakbo.data.db.TagEntity
import io.github.taehunkim.jogakbo.index.NoteIndexer

/** Persistent outcomes come from the note/answers; transient execution states come from WorkManager. */
enum class ProcessingState { NONE, WAITING, RUNNING, DONE, FAILED, STALE, PARTIAL, NO_TAGS }
enum class ProcessingOutcome { DONE, NOT_FOUND, NOT_READY, NO_MODEL, NO_TAGS }

object NoteProcessingStatus {
    fun summary(note: NoteEntity): ProcessingState = when (note.summaryState) {
        JobState.QUEUED, JobState.DEFERRED -> ProcessingState.WAITING
        JobState.RUNNING -> ProcessingState.RUNNING
        JobState.FAILED -> ProcessingState.FAILED
        else -> if (!note.summary.isNullOrBlank()) ProcessingState.DONE else ProcessingState.NONE
    }

    fun index(note: NoteEntity, modelId: String): ProcessingState = when {
        note.embeddedAt == null || note.embedKey == null -> ProcessingState.NONE
        note.embeddedAt < note.updatedAt || note.embedKey != NoteIndexer.key(modelId) -> ProcessingState.STALE
        else -> ProcessingState.DONE
    }

    fun tags(all: List<TagEntity>, rows: List<NoteTagName>): ProcessingState {
        if (all.isEmpty()) return ProcessingState.NO_TAGS
        val answered = rows.map { it.tagId }.toSet()
        val count = all.count { it.id in answered }
        return when (count) {
            0 -> ProcessingState.NONE
            all.size -> ProcessingState.DONE
            else -> ProcessingState.PARTIAL
        }
    }
}
