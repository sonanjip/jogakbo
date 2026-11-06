package io.github.taehunkim.jogakbo.ui.notes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.data.db.CueEntity
import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.notes.Bodies
import io.github.taehunkim.jogakbo.rememberContainer
import io.github.taehunkim.jogakbo.service.NotesApi
import io.github.taehunkim.jogakbo.ui.common.Markdown
import io.github.taehunkim.jogakbo.ui.common.WarningCard
import io.github.taehunkim.jogakbo.ui.common.formatDate
import io.github.taehunkim.jogakbo.ui.common.formatDuration
import io.github.taehunkim.jogakbo.ui.common.kindLabel
import io.github.taehunkim.jogakbo.ui.common.openUrl
import io.github.taehunkim.jogakbo.ui.common.shareText
import io.github.taehunkim.jogakbo.ui.common.onMain
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One note: the summary on top, then the original (with the player and the transcript lines for audio and videos). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(noteId: Long, onBack: () -> Unit, onEdit: () -> Unit, onEditSummary: () -> Unit) {
    val c = rememberContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val note by c.db.noteDao().observe(noteId).collectAsStateWithLifecycle(null)
    val cues by c.db.noteDao().observeCues(noteId).collectAsStateWithLifecycle(emptyList())
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var summarizeDialog by remember { mutableStateOf(false) }
    val n = note

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(n?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (n != null && n.kind == NoteKind.MEMO) IconButton(onClick = onEdit, modifier = Modifier.testTag("edit_note")) { Icon(Icons.Filled.Edit, stringResource(R.string.action_edit)) }
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("note_menu")) { Icon(Icons.Filled.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (n != null && n.kind != NoteKind.MEMO) DropdownMenuItem(text = { Text(stringResource(R.string.action_edit_original)) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = { menu = false; n?.let { shareText(ctx, it.title, NotesApi.markdown(it)) } })
                        if (n?.audioFile != null && n.sourceState != JobState.RUNNING) DropdownMenuItem(text = { Text(stringResource(R.string.action_delete_audio)) }, onClick = { menu = false; scope.launch { c.notes.deleteAudio(noteId) } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; confirmDelete = true }, modifier = Modifier.testTag("delete_note"))
                    }
                },
            )
        },
    ) { pad ->
        if (n == null) return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Header(n)
            NoteTags(noteId)
            SourceState(n) { scope.launch { c.notes.retrySource(noteId) } }
            SummaryCard(n, onSummarize = { summarizeDialog = true }, onEdit = onEditSummary)
            val audio = remember(n.audioFile) { c.notes.audioFile(n) }
            val player = audio?.let { rememberPlayer(it) }
            if (player != null) AudioPlayerBar(player)
            HorizontalDivider()
            when {
                n.kind == NoteKind.AUDIO && cues.isNotEmpty() -> {
                    Text(stringResource(R.string.note_transcript), style = MaterialTheme.typography.titleMedium)
                    CueList(cues, player?.positionMs) { cue -> player?.seekTo(cue.startMs) }
                }
                n.kind == NoteKind.YOUTUBE && cues.isNotEmpty() -> {
                    val desc = remember(n.sourceMeta) { runCatching { JSONObject(n.sourceMeta ?: "{}").optString("description") }.getOrDefault("") }
                    if (desc.isNotBlank()) {
                        Text(stringResource(R.string.video_description), style = MaterialTheme.typography.titleMedium)
                        Markdown(desc.replace("\n", "  \n"))
                    }
                    Text(stringResource(R.string.video_transcript), style = MaterialTheme.typography.titleMedium)
                    CueList(cues, null) { cue -> n.sourceUrl?.let { openUrl(ctx, videoAt(it, cue.startMs)) } }
                }
                n.body.isNotBlank() -> {
                    if (n.kind != NoteKind.MEMO) Text(stringResource(R.string.note_original), style = MaterialTheme.typography.titleMedium)
                    Markdown(n.body, Modifier.testTag("note_body"))
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(R.string.delete_text)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { c.notes.delete(noteId); onMain { onBack() } } }, modifier = Modifier.testTag("delete_confirm")) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (summarizeDialog && n != null) SummarizeDialog(n, onDismiss = { summarizeDialog = false }) { prompt ->
        summarizeDialog = false
        scope.launch { c.notes.requestSummary(noteId, prompt) }
    }
}

@Composable
private fun Header(n: NoteEntity) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(n.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("note_title"))
        val meta = runCatching { JSONObject(n.sourceMeta ?: "{}") }.getOrDefault(JSONObject())
        val parts = listOfNotNull(
            stringResource(kindLabel(n.kind)), formatDate(ctx, n.createdAt),
            meta.optString("site").ifBlank { null }, meta.optString("channel").ifBlank { null },
            n.durationMs?.let { formatDuration(it) },
        )
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        n.sourceUrl?.let { url ->
            Text(
                url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { openUrl(ctx, url) }.testTag("note_url"),
            )
        }
    }
}

@Composable
private fun SourceState(n: NoteEntity, onRetry: () -> Unit) {
    when (n.sourceState) {
        JobState.QUEUED, JobState.RUNNING -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(if (n.kind == NoteKind.AUDIO) R.string.state_transcribing else R.string.state_fetching), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        JobState.FAILED -> WarningCard(stringResource(R.string.source_failed, n.sourceError.orEmpty()), Modifier.testTag("source_failed"), stringResource(R.string.action_retry), onRetry)
        else -> Unit
    }
}

@Composable
private fun SummaryCard(n: NoteEntity, onSummarize: () -> Unit, onEdit: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(true) }
    val busy = n.summaryState == JobState.QUEUED || n.summaryState == JobState.RUNNING
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().animateContentSize().testTag("summary_card")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.summary), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (n.summaryEdited) Text(stringResource(R.string.summary_edited), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 4.dp))
                if (n.summary != null) IconButton(onClick = { open = !open }) { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null) }
            }
            when {
                busy -> {
                    Text(stringResource(R.string.state_summarizing), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                n.summaryState == JobState.FAILED -> Text(stringResource(R.string.summary_failed, n.summaryError.orEmpty()), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("summary_error"))
            }
            if (n.summary != null && open) Markdown(n.summary, Modifier.testTag("summary_text"))
            else if (n.summary == null && !busy && n.summaryState != JobState.FAILED) Text(stringResource(R.string.summary_none), style = MaterialTheme.typography.bodySmall)
            n.summaryPrompt?.takeIf { open }?.let { Text(stringResource(R.string.summary_prompt_used, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val sourceReady = n.sourceState == JobState.NONE || n.sourceState == JobState.DONE
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSummarize, enabled = !busy && sourceReady, modifier = Modifier.testTag("summarize")) {
                    Text(stringResource(if (n.summary == null) R.string.action_summarize else R.string.action_summarize_again))
                }
                if (n.summary != null || sourceReady) TextButton(onClick = onEdit, enabled = !busy, modifier = Modifier.testTag("edit_summary")) {
                    Text(stringResource(if (n.summary == null) R.string.action_write_summary else R.string.action_edit_summary))
                }
            }
        }
    }
}

/** Summarize (again), with an optional instruction; asks first when it would replace a summary the user edited. */
@Composable
private fun SummarizeDialog(n: NoteEntity, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var prompt by remember { mutableStateOf(n.summaryPrompt.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("summarize_dialog"),
        title = { Text(stringResource(if (n.summary == null) R.string.action_summarize else R.string.action_summarize_again)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (n.summaryEdited) Text(stringResource(R.string.summary_overwrite_edited), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("overwrite_warning"))
                OutlinedTextField(
                    prompt, { prompt = it }, label = { Text(stringResource(R.string.summary_prompt_label)) },
                    placeholder = { Text(stringResource(R.string.summary_prompt_hint)) }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("summary_prompt"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(prompt.trim()) }, modifier = Modifier.testTag("summarize_confirm")) { Text(stringResource(if (n.summaryEdited) R.string.action_replace else R.string.action_summarize)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CueList(cues: List<CueEntity>, positionMs: Long?, onTap: (CueEntity) -> Unit) {
    val current = positionMs?.let { p -> cues.lastOrNull { it.startMs <= p }?.id }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("cues")) {
        cues.forEach { cue ->
            val active = cue.id == current
            Row(
                Modifier.fillMaxWidth().clickable { onTap(cue) }.padding(vertical = 4.dp).testTag("cue_${cue.ordinal}"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(Bodies.time(cue.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(52.dp))
                Text(
                    cue.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    fontWeight = if (active) FontWeight.Bold else null, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** The video link at [ms] (YouTube's t parameter). */
fun videoAt(url: String, ms: Long): String {
    val base = url.replace(Regex("[?&]t=\\d+s?"), "")
    return base + (if ('?' in base) "&" else "?") + "t=${ms / 1000}"
}
