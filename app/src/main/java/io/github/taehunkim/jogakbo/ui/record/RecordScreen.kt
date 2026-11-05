package io.github.taehunkim.jogakbo.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.data.db.CueEntity
import io.github.taehunkim.jogakbo.notes.Bodies
import io.github.taehunkim.jogakbo.record.RecordingService
import io.github.taehunkim.jogakbo.rememberContainer
import io.github.taehunkim.jogakbo.ui.common.formatDuration
import kotlinx.coroutines.flow.flowOf

/** The recording in progress: time, level, pause and stop, and the transcript lines as they come. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(onBack: () -> Unit, onFinished: (Long) -> Unit) {
    val c = rememberContainer()
    val ctx = LocalContext.current
    val session by RecordingService.current.collectAsStateWithLifecycle()
    var noteId by remember { mutableStateOf<Long?>(null) }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        session?.let { noteId = it.noteId; seen = true }
        // the service finished the note: show it
        if (session == null && seen) noteId?.let(onFinished)
    }
    val cues by remember(noteId) { noteId?.let { c.db.noteDao().observeCues(it) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList<CueEntity>())
    val list = rememberLazyListState()
    LaunchedEffect(cues.size) { if (cues.isNotEmpty()) list.animateScrollToItem(cues.size - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recording_title_bar)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val s = session
            Text(formatDuration(s?.state?.elapsedMs ?: 0), style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag("rec_time"))
            LinearProgressIndicator(progress = { ((s?.state?.level ?: 0f) * 6f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            when {
                s == null && !seen -> Text(stringResource(R.string.recording_starting))
                s?.finishing == true -> { Text(stringResource(R.string.recording_finishing)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                s != null -> {
                    if (!s.canTranscribe) Text(stringResource(R.string.recording_no_live), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FilledTonalButton(
                            onClick = { RecordingService.send(ctx, if (s.state.paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE) },
                            modifier = Modifier.testTag("rec_pause"),
                        ) {
                            Icon(if (s.state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                            Text(stringResource(if (s.state.paused) R.string.action_resume else R.string.action_pause), Modifier.padding(start = 6.dp))
                        }
                        Button(onClick = { RecordingService.send(ctx, RecordingService.ACTION_STOP) }, modifier = Modifier.testTag("rec_stop")) {
                            Icon(Icons.Filled.Stop, null)
                            Text(stringResource(R.string.action_stop), Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("rec_cues"), state = list) {
                items(cues, key = { it.id }) { cue ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(Bodies.time(cue.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(52.dp))
                        Text(cue.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
