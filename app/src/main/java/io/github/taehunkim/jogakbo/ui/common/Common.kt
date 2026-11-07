package io.github.taehunkim.jogakbo.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.data.db.NoteKind

@Composable
fun WarningCard(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Warning, null)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

fun kindIcon(kind: NoteKind): ImageVector = when (kind) {
    NoteKind.MEMO -> Icons.AutoMirrored.Filled.Notes
    NoteKind.LINK -> Icons.Filled.Link
    NoteKind.YOUTUBE -> Icons.Filled.SmartDisplay
    NoteKind.AUDIO -> Icons.Filled.Mic
    NoteKind.DOCUMENT -> Icons.Filled.Description
}

fun kindLabel(kind: NoteKind): Int = when (kind) {
    NoteKind.MEMO -> R.string.kind_memo
    NoteKind.LINK -> R.string.kind_link
    NoteKind.YOUTUBE -> R.string.kind_youtube
    NoteKind.AUDIO -> R.string.kind_audio
    NoteKind.DOCUMENT -> R.string.kind_document
}

fun formatDate(ctx: Context, ms: Long): String =
    DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun openUrl(ctx: Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, R.string.no_app_found, Toast.LENGTH_SHORT).show()
    }
}

/** Share plain text (a note's Markdown). */
fun shareText(ctx: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try { ctx.startActivity(Intent.createChooser(send, null)) } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, R.string.no_app_found, Toast.LENGTH_SHORT).show()
    }
}
