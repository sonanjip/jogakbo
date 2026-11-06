package io.github.taehunkim.jogakbo.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.data.prefs.EmbedMode
import io.github.taehunkim.jogakbo.rememberContainer
import kotlinx.coroutines.flow.first
import io.github.taehunkim.jogakbo.ui.common.onMain
import kotlinx.coroutines.launch

enum class EditTarget { NOTE, SUMMARY }

/**
 * Writes a new memo ([noteId] 0, [initialText] from a share), edits a note's original, or edits its summary.
 * [onSaved] gets the note's id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(noteId: Long, target: EditTarget, initialText: String?, onDone: () -> Unit, onSaved: (Long) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable { mutableStateOf(noteId == 0L) }
    var title by rememberSaveable { mutableStateOf("") }
    var text by remember { mutableStateOf(TextFieldValue(initialText.orEmpty(), TextRange(initialText.orEmpty().length))) }
    var original by remember { mutableStateOf(initialText.orEmpty() to "") }
    val autoMemo by c.settings.autoSummary(NoteKind.MEMO).collectAsStateWithLifecycle(false)
    var summarize by remember { mutableStateOf<Boolean?>(null) }
    // "now" starts as the Settings default; a note that differs from it keeps its own choice
    val defaultNow = c.settings.embedMode.collectAsStateWithLifecycle(EmbedMode.IMMEDIATE).value == EmbedMode.IMMEDIATE
    var now by remember { mutableStateOf<Boolean?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(noteId) {
        if (noteId == 0L || loaded) return@LaunchedEffect
        val n = c.db.noteDao().observe(noteId).first() ?: return@LaunchedEffect
        val t = if (target == EditTarget.SUMMARY) n.summary.orEmpty() else n.body
        title = n.title
        text = TextFieldValue(t, TextRange(t.length))
        original = t to n.title
        loaded = true
    }
    val changed = text.text != original.first || (target == EditTarget.NOTE && title != original.second && noteId != 0L) || (noteId == 0L && title.isNotBlank())
    fun close() { if (changed) confirmDiscard = true else onDone() }
    BackHandler { close() }

    fun save() = scope.launch {
        when {
            target == EditTarget.SUMMARY -> { c.notes.updateSummary(noteId, text.text); onMain { onSaved(noteId) } }
            noteId == 0L -> {
                if (text.text.isBlank() && title.isBlank()) { onMain { onDone() }; return@launch }
                val id = c.notes.createMemo(title, text.text, summarize = summarize ?: autoMemo, timing = now?.takeIf { it != defaultNow }); onMain { onSaved(id) }
            }
            else -> { c.notes.updateMemo(noteId, title, text.text); onMain { onSaved(noteId) } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(when { target == EditTarget.SUMMARY -> R.string.edit_summary_title; noteId == 0L -> R.string.new_memo; else -> R.string.edit_note_title })) },
                navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) } },
                actions = { IconButton(onClick = { save() }, modifier = Modifier.testTag("save")) { Icon(Icons.Filled.Check, stringResource(R.string.action_save)) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (target == EditTarget.NOTE) OutlinedTextField(
                title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("title"),
                label = { Text(stringResource(R.string.note_title_label)) }, placeholder = { Text(stringResource(R.string.note_title_hint)) },
            )
            if (noteId == 0L) Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(summarize ?: autoMemo, { summarize = it }, modifier = Modifier.testTag("memo_summarize"))
                    Text(stringResource(R.string.memo_summarize))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(now ?: defaultNow, { now = it }, modifier = Modifier.testTag("memo_now"))
                    Text(stringResource(R.string.timing_now))
                }
            }
            MarkdownEditor(
                text, { text = it }, Modifier.weight(1f).fillMaxWidth(),
                placeholder = stringResource(if (target == EditTarget.SUMMARY) R.string.summary_placeholder else R.string.memo_placeholder),
            )
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.discard_title)) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDone() }) { Text(stringResource(R.string.action_discard)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_keep_editing)) } },
    )
}
