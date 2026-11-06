package io.github.taehunkim.jogakbo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import io.github.taehunkim.jogakbo.data.db.TagEntity
import io.github.taehunkim.jogakbo.ml.HostState
import io.github.taehunkim.jogakbo.rememberContainer
import io.github.taehunkim.jogakbo.tags.TagRepository
import io.github.taehunkim.ondolsaem.sdk.OndolSaem
import kotlinx.coroutines.launch

/** The user's tags (add, edit, delete, order) and automatic tagging. */
@Composable
internal fun TagsTab() {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val tags by c.db.tagDao().observeTags().collectAsStateWithLifecycle(emptyList())
    val auto by c.settings.autoTag.collectAsStateWithLifecycle(true)
    val host by c.models.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TagEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    Section(R.string.settings_tags, R.string.settings_tags_desc)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_auto_tag), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_auto_tag_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = auto, onCheckedChange = { on -> scope.launch { c.settings.setAutoTag(on); if (on) c.embedScheduler.onNoteChanged() } }, modifier = Modifier.testTag("auto_tag"))
    }
    if (auto && host.status == HostState.Status.CONNECTED && !host.ready(OndolSaem.Categories.DECISION)) {
        WarningCardText(stringResource(R.string.tags_no_model))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.testTag("tag_add")) { Text(stringResource(R.string.tag_add)) }
        TextButton(onClick = { scope.launch { c.tags.retagAll(); c.embedScheduler.runNow(0) } }, modifier = Modifier.testTag("tag_retag")) {
            Text(stringResource(R.string.tag_retag_all))
        }
    }
    HorizontalDivider()
    if (tags.isEmpty()) Text(stringResource(R.string.tags_empty), style = MaterialTheme.typography.bodyMedium)
    tags.forEachIndexed { i, t ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("tag_row_${t.name}")) {
            Column(Modifier.weight(1f)) {
                Text("#" + t.name, style = MaterialTheme.typography.bodyLarge)
                if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { scope.launch { c.tags.move(t.id, -1) } }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.tag_up)) }
            IconButton(onClick = { scope.launch { c.tags.move(t.id, 1) } }, enabled = i < tags.size - 1) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.tag_down)) }
            IconButton(onClick = { editing = t }) { Icon(Icons.Default.Edit, stringResource(R.string.tag_edit)) }
            IconButton(onClick = { scope.launch { c.tags.delete(t.id) } }, modifier = Modifier.testTag("tag_delete_${t.name}")) { Icon(Icons.Default.Delete, stringResource(R.string.tag_delete)) }
        }
    }
    if (adding || editing != null) {
        TagDialog(editing, onDismiss = { adding = false; editing = null }) { name, desc ->
            val e = editing
            val r = if (e == null) c.tags.add(name, desc) else c.tags.update(e.id, name, desc)
            if (r is TagRepository.Result.Ok) { adding = false; editing = null }
            r
        }
    }
}

@Composable
private fun WarningCardText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("tags_no_model"))
}

/** Name and an optional description ("what belongs here"), which the decision model reads too. */
@Composable
internal fun TagDialog(tag: TagEntity?, onDismiss: () -> Unit, onSave: suspend (String, String) -> TagRepository.Result) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(tag?.name.orEmpty()) }
    var desc by remember { mutableStateOf(tag?.description.orEmpty()) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (tag == null) R.string.tag_add else R.string.tag_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it.take(TagRepository.MAX_NAME); error = null }, singleLine = true,
                    label = { Text(stringResource(R.string.tag_name)) }, isError = error != null,
                    supportingText = error?.let { e -> { Text(stringResource(e)) } }, modifier = Modifier.testTag("tag_name"),
                )
                OutlinedTextField(
                    desc, { desc = it.take(TagRepository.MAX_DESCRIPTION) }, label = { Text(stringResource(R.string.tag_description)) },
                    placeholder = { Text(stringResource(R.string.tag_description_hint)) }, modifier = Modifier.testTag("tag_description"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    when (onSave(name, desc)) {
                        TagRepository.Result.Blank -> error = R.string.tag_error_blank
                        TagRepository.Result.Duplicate -> error = R.string.tag_error_duplicate
                        is TagRepository.Result.Ok -> Unit
                    }
                }
            }, modifier = Modifier.testTag("tag_save")) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
