package io.github.taehunkim.jogakbo.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.ui.common.Markdown

private fun TextFieldValue.edit() = Edit(text, minOf(selection.start, selection.end), maxOf(selection.start, selection.end))
private fun Edit.value() = TextFieldValue(text, TextRange(start, end))

/**
 * Markdown text with a formatting toolbar (for people who do not know Markdown) and a preview tab.
 * Used for memos and for summaries.
 */
@Composable
fun MarkdownEditor(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier, placeholder: String = "") {
    var tab by rememberSaveable { mutableStateOf(0) }
    var linkDialog by remember { mutableStateOf(false) }
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.editor_write)) }, modifier = Modifier.testTag("editor_tab_write"))
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.editor_preview)) }, modifier = Modifier.testTag("editor_tab_preview"))
        }
        if (tab == 0) {
            Toolbar(
                onHeading = { l -> onValueChange(MarkdownActions.heading(value.edit(), l).value()) },
                onWrap = { w -> onValueChange(MarkdownActions.wrap(value.edit(), w).value()) },
                onPrefix = { p -> onValueChange(MarkdownActions.prefix(value.edit(), p).value()) },
                onLink = { linkDialog = true },
                onRule = { onValueChange(MarkdownActions.rule(value.edit()).value()) },
            )
            OutlinedTextField(
                value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxSize().padding(top = 4.dp).testTag("editor_text"),
                placeholder = { Text(placeholder) },
            )
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).testTag("editor_preview")) {
                if (value.text.isBlank()) Text(stringResource(R.string.editor_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Markdown(value.text)
            }
        }
    }
    if (linkDialog) {
        val selected = value.edit().let { it.text.substring(it.start, it.end) }
        LinkDialog(selected, onDismiss = { linkDialog = false }) { label, url ->
            onValueChange(MarkdownActions.link(value.edit(), label, url).value()); linkDialog = false
        }
    }
}

@Composable
private fun Toolbar(
    onHeading: (Int) -> Unit, onWrap: (MarkdownActions.Wrap) -> Unit, onPrefix: (MarkdownActions.Prefix) -> Unit, onLink: () -> Unit, onRule: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("editor_toolbar")) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            for (level in 1..3) {
                TextButton(onClick = { onHeading(level) }, modifier = Modifier.testTag("md_h$level")) {
                    Text("H$level", fontWeight = FontWeight.Bold)
                }
            }
            Tool(Icons.Filled.FormatBold, R.string.md_bold, "md_bold") { onWrap(MarkdownActions.Wrap.BOLD) }
            Tool(Icons.Filled.FormatItalic, R.string.md_italic, "md_italic") { onWrap(MarkdownActions.Wrap.ITALIC) }
            Tool(Icons.Filled.FormatStrikethrough, R.string.md_strike, "md_strike") { onWrap(MarkdownActions.Wrap.STRIKE) }
            Tool(Icons.AutoMirrored.Filled.FormatListBulleted, R.string.md_bullet, "md_bullet") { onPrefix(MarkdownActions.Prefix.BULLET) }
            Tool(Icons.Filled.FormatListNumbered, R.string.md_numbered, "md_numbered") { onPrefix(MarkdownActions.Prefix.NUMBERED) }
            Tool(Icons.Filled.CheckBox, R.string.md_checkbox, "md_checkbox") { onPrefix(MarkdownActions.Prefix.CHECKBOX) }
            Tool(Icons.Filled.FormatQuote, R.string.md_quote, "md_quote") { onPrefix(MarkdownActions.Prefix.QUOTE) }
            Tool(Icons.Filled.Code, R.string.md_code, "md_code") { onWrap(MarkdownActions.Wrap.CODE) }
            Tool(Icons.Filled.Link, R.string.md_link, "md_link", onLink)
            Tool(Icons.Filled.HorizontalRule, R.string.md_rule, "md_rule", onRule)
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, label: Int, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Icon(icon, stringResource(label)) }
}

@Composable
private fun LinkDialog(selected: String, onDismiss: () -> Unit, onDone: (String, String) -> Unit) {
    var label by remember { mutableStateOf(selected) }
    var url by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.md_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.md_link_text)) }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.md_link_url)) }, singleLine = true, modifier = Modifier.testTag("md_link_url"))
            }
        },
        confirmButton = { TextButton(onClick = { onDone(label, url) }, enabled = url.length > 8) { Text(stringResource(R.string.action_insert)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
