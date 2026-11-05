package io.github.taehunkim.jogakbo.record

import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.audio.Pcm
import io.github.taehunkim.jogakbo.audio.SpeechTools
import java.io.File
import java.io.RandomAccessFile

/**
 * Transcribes a recording while it grows. Each pass reads the audio after the last line already given out
 * ([committedMs]) up to what is recorded, and gives out the lines that end at least [holdMs] before the end
 * (the last one may still be cut off: the next pass reads it again). The final pass gives out everything.
 */
class LiveTranscriber(
    private val speech: SpeechTools,
    private val pcm: File,
    private val scratch: File,
    private val onCue: suspend (Cue) -> Unit,
    private val minNewMs: Long = MIN_NEW_MS,
    private val holdMs: Long = HOLD_MS,
) {
    @Volatile var committedMs = 0L
        private set

    /** Returns the lines given out by this pass. */
    suspend fun pass(availableMs: Long, final: Boolean): List<Cue> {
        val from = committedMs
        if (availableMs - from < (if (final) 1 else minNewMs)) {
            if (final) committedMs = availableMs
            return emptyList()
        }
        copy(from, availableMs)
        val heard = ArrayList<Cue>()
        speech.transcribe(scratch, 0) { c -> if (c.text.isNotBlank()) heard += Cue(c.startMs + from, c.endMs + from, c.text.trim()) }
        val limit = if (final) Long.MAX_VALUE else availableMs - holdMs
        val out = heard.filter { it.endMs <= limit }
        for (c in out) onCue(c)
        committedMs = when {
            final -> availableMs
            out.isNotEmpty() -> out.last().endMs
            // only silence (or nothing understood) for a long while: move on, keeping a little margin
            heard.isEmpty() && availableMs - from > SKIP_SILENCE_MS -> availableMs - holdMs * 2
            else -> from
        }
        return out
    }

    private fun copy(fromMs: Long, toMs: Long) {
        val start = Pcm.msToByte(fromMs)
        val end = minOf(Pcm.msToByte(toMs), pcm.length())
        RandomAccessFile(pcm, "r").use { input ->
            input.seek(start)
            scratch.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var left = end - start
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n); left -= n
                }
            }
        }
    }

    companion object {
        const val MIN_NEW_MS = 5_000L
        const val HOLD_MS = 1_000L
        const val SKIP_SILENCE_MS = 25_000L
    }
}
