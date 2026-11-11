package io.github.taehunkim.jogakbo.ui.notes

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.index.EmbedScheduler
import io.github.taehunkim.jogakbo.index.EmbedWorker
import io.github.taehunkim.jogakbo.jobs.NoteWorker
import io.github.taehunkim.jogakbo.notes.NoteProcessingStatus
import io.github.taehunkim.jogakbo.notes.ProcessingOutcome
import io.github.taehunkim.jogakbo.notes.ProcessingState
import io.github.taehunkim.jogakbo.rememberContainer

/** The note's persisted results, overlaid with queued/running/failed explicit or bulk work. */
@Composable
fun NoteProcessingCard(note: NoteEntity) {
    val c = rememberContainer()
    val context = LocalContext.current
    val wm = remember(context) { WorkManager.getInstance(context) }
    val all by c.db.tagDao().observeTags().collectAsStateWithLifecycle(emptyList())
    val rows by remember(note.id) { c.db.tagDao().observeOfNote(note.id) }.collectAsStateWithLifecycle(emptyList())
    val modelId by c.settings.embeddingModelId.collectAsStateWithLifecycle("")
    val decisionId by c.settings.decisionModelId.collectAsStateWithLifecycle("")
    val work by remember(note.id) { wm.getWorkInfosByTagFlow(NoteWorker.tag(note.id)) }.collectAsStateWithLifecycle(emptyList())
    val bulk by remember {
        wm.getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(EmbedScheduler.NOW, EmbedScheduler.AUTO, EmbedScheduler.SCHEDULED, EmbedScheduler.IDLE))
    }.collectAsStateWithLifecycle(emptyList())
    // Explicit jobs use unique work names; each request carries its operation as a tag as well.
    fun job(kind: String) = work.filter { NoteWorker.operationTag(kind) in it.tags }
        .maxByOrNull { it.outputData.getLong(NoteWorker.OUT_AT, it.inputDataTime()) }
    val runningBulk = bulk.firstOrNull { it.state == WorkInfo.State.RUNNING && it.progress.getLong(EmbedWorker.PROGRESS_NOTE_ID, 0) == note.id }
    val bulkStage = runningBulk?.progress?.getString(EmbedWorker.PROGRESS_STAGE)
    val sourceBusy = note.sourceState in setOf(JobState.QUEUED, JobState.RUNNING, JobState.DEFERRED)
    val busy = sourceBusy || work.any { !it.state.isFinished } || runningBulk != null
    val indexWork = job(NoteWorker.JOB_INDEX)
    val tagWork = job(NoteWorker.JOB_TAGS)
    val summary = executionState(NoteProcessingStatus.summary(note), job(NoteWorker.JOB_SUMMARY), bulkStage == EmbedWorker.STAGE_SUMMARY, null)
    val index = executionState(NoteProcessingStatus.index(note, modelId), indexWork, bulkStage == EmbedWorker.STAGE_INDEX, note.embeddedAt)
    val tags = executionState(NoteProcessingStatus.tags(all, rows), tagWork, bulkStage == EmbedWorker.STAGE_TAGS, note.taggedAt)
    Card(Modifier.fillMaxWidth().testTag("note_processing")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.note_processing_title), style = MaterialTheme.typography.titleSmall)
            ProcessingRow(R.string.summary, summary, "processing_summary")
            ProcessingRow(R.string.processing_index_label, index, "processing_index",
                action = if (note.embeddedAt == null) R.string.processing_index_run else R.string.processing_index_again,
                enabled = !busy, actionTag = "note_index_run", onClick = { c.jobs.index(note.id) })
            if (index == ProcessingState.FAILED) FailureHint(indexWork)
            else if (modelId.isBlank()) Text(stringResource(R.string.processing_index_no_model), style = MaterialTheme.typography.bodySmall)
            ProcessingRow(R.string.processing_tags_label, tags, "processing_tags",
                action = if (note.taggedAt == null) R.string.processing_tags_run else R.string.processing_tags_again,
                enabled = !busy && all.isNotEmpty(), actionTag = "note_tag_run", onClick = { c.jobs.tag(note.id) })
            if (tags == ProcessingState.FAILED) FailureHint(tagWork)
            else if (decisionId.isBlank() && all.isNotEmpty()) Text(stringResource(R.string.processing_tags_no_model), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// WorkInfo deliberately exposes no enqueue timestamp. Request time is carried in an immutable tag.
private fun WorkInfo.inputDataTime(): Long = tags.firstOrNull { it.startsWith(NoteWorker.REQUEST_TAG_PREFIX) }
    ?.removePrefix(NoteWorker.REQUEST_TAG_PREFIX)?.toLongOrNull() ?: 0L

private fun executionState(base: ProcessingState, work: WorkInfo?, bulkRunning: Boolean, completedAt: Long?): ProcessingState = when {
    bulkRunning -> ProcessingState.RUNNING
    work?.state == WorkInfo.State.RUNNING -> ProcessingState.RUNNING
    work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED -> ProcessingState.WAITING
    work?.state == WorkInfo.State.FAILED && (completedAt ?: 0) <= work.outputData.getLong(NoteWorker.OUT_AT, Long.MAX_VALUE) -> ProcessingState.FAILED
    else -> base
}

@Composable
private fun FailureHint(work: WorkInfo?) {
    val message = when (work?.outputData?.getString(NoteWorker.OUT_REASON)) {
        ProcessingOutcome.NO_MODEL.name -> R.string.processing_no_model
        ProcessingOutcome.NOT_READY.name -> R.string.processing_source_wait
        ProcessingOutcome.NO_TAGS.name -> R.string.processing_no_tags
        else -> R.string.processing_failed_hint
    }
    Text(stringResource(message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ProcessingRow(@StringRes label: Int, state: ProcessingState, tag: String,
                          @StringRes action: Int? = null, enabled: Boolean = false,
                          actionTag: String = "", onClick: () -> Unit = {}) {
    val status = when (state) {
        ProcessingState.NONE -> R.string.processing_none
        ProcessingState.WAITING -> R.string.processing_waiting
        ProcessingState.RUNNING -> R.string.processing_running
        ProcessingState.DONE -> R.string.processing_done
        ProcessingState.FAILED -> R.string.processing_failed
        ProcessingState.STALE -> R.string.processing_stale
        ProcessingState.PARTIAL -> R.string.processing_partial
        ProcessingState.NO_TAGS -> R.string.processing_no_tags
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.processing_status, stringResource(label), stringResource(status)),
            Modifier.weight(1f).testTag(tag), style = MaterialTheme.typography.bodyMedium)
        if (action != null) TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(actionTag)) { Text(stringResource(action)) }
    }
}
