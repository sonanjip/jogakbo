package io.github.taehunkim.jogakbo.ui.notes

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.ui.common.formatDuration
import kotlinx.coroutines.delay
import java.io.File

/** Plays a note's audio; transcript lines seek it ([seekTo]). */
@Stable
class PlayerState(private val file: File) {
    private var player: MediaPlayer? = null
    var playing by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var failed by mutableStateOf(false); private set

    private fun ensure(): MediaPlayer? {
        player?.let { return it }
        return runCatching {
            MediaPlayer().apply {
                setDataSource(file.path); prepare()
                setOnCompletionListener { this@PlayerState.playing = false }
            }
        }.onSuccess { player = it; durationMs = it.duration.toLong() }.onFailure { failed = true }.getOrNull()
    }

    fun prepare() { ensure() }
    fun toggle() { val p = ensure() ?: return; if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true } }
    fun seekTo(ms: Long, play: Boolean = true) {
        val p = ensure() ?: return
        p.seekTo(ms.toInt()); positionMs = ms
        if (play && !p.isPlaying) { p.start(); playing = true }
    }
    fun tick() { player?.let { if (it.isPlaying) positionMs = it.currentPosition.toLong() } }
    fun release() { player?.release(); player = null; playing = false }
}

@Composable
fun rememberPlayer(file: File): PlayerState {
    val s = remember(file) { PlayerState(file) }
    DisposableEffect(s) { s.prepare(); onDispose { s.release() } }
    LaunchedEffect(s) { while (true) { s.tick(); delay(250) } }
    return s
}

@Composable
fun AudioPlayerBar(p: PlayerState) {
    if (p.failed) { Text(stringResource(R.string.player_failed), color = MaterialTheme.colorScheme.error); return }
    Row(Modifier.fillMaxWidth().testTag("player"), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { p.toggle() }, modifier = Modifier.testTag("player_toggle")) {
            Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (p.playing) R.string.player_pause else R.string.player_play))
        }
        Slider(
            value = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else 0f,
            onValueChange = { f -> p.seekTo((f * p.durationMs).toLong(), play = p.playing) },
            modifier = Modifier.weight(1f),
        )
        Text(formatDuration(p.positionMs) + " / " + formatDuration(p.durationMs), style = MaterialTheme.typography.labelSmall)
    }
}
