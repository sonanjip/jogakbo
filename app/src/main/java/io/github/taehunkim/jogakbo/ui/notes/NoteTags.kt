package io.github.taehunkim.jogakbo.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.data.db.TagState
import io.github.taehunkim.jogakbo.rememberContainer
import kotlinx.coroutines.launch

/**
 * The note's tags: the model's and the user's are chips (x takes one off for good), the model's unsure answers are
 * suggestions to accept or decline, and "Tags" opens the full list to tick.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteTags(noteId: Long) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val rows by c.db.tagDao().observeOfNote(noteId).collectAsStateWithLifecycle(emptyList())
    val all by c.db.tagDao().observeTags().collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf(false) }
    if (all.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("note_tags")) {
        for (t in rows.filter { it.state.applied }) {
            InputChip(
                selected = true, onClick = { scope.launch { c.tags.removeFromNote(noteId, t.tagId) } }, label = { Text("#" + t.name) },
                trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.tag_remove), Modifier.size(16.dp)) },
                modifier = Modifier.testTag("note_tag_${t.name}"),
            )
        }
        for (t in rows.filter { it.state == TagState.SUGGESTED }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SuggestionChip(
                    onClick = { scope.launch { c.tags.addToNote(noteId, t.tagId) } }, label = { Text("#" + t.name + "?") },
                    icon = { Icon(Icons.Filled.Check, stringResource(R.string.tag_accept), Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("note_tag_suggested_${t.name}"),
                )
                Icon(
                    Icons.Filled.Close, stringResource(R.string.tag_decline),
                    Modifier.size(20.dp).clickable { scope.launch { c.tags.removeFromNote(noteId, t.tagId) } }.testTag("note_tag_decline_${t.name}"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AssistChip(
            onClick = { editing = true }, label = { Text(stringResource(R.string.tags)) },
            leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) }, modifier = Modifier.testTag("note_tags_edit"),
        )
    }
    if (editing) {
        val on = rows.filter { it.state.applied }.map { it.tagId }.toSet()
        var chosen by remember { mutableStateOf(on) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.tags)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (t in all) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(t.id in chosen, { chosen = if (it) chosen + t.id else chosen - t.id }, modifier = Modifier.testTag("tag_check_${t.name}"))
                        Text("#" + t.name)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    scope.launch {
                        for (id in chosen - on) c.tags.addToNote(noteId, id)
                        for (id in on - chosen) c.tags.removeFromNote(noteId, id)
                    }
                }, modifier = Modifier.testTag("tags_save")) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
