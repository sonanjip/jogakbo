package io.github.taehunkim.jogakbo.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Pcm {
    const val RATE = 16_000
    /** 16-bit mono at [RATE]. */
    const val BYTES_PER_SECOND = RATE * 2

    fun msToByte(ms: Long): Long = (ms * RATE / 1000) * 2
    fun byteToMs(bytes: Long): Long = bytes / 2 * 1000 / RATE

    /** Wraps raw 16-bit little-endian mono PCM into a complete WAV file (what the audio embedder expects). */
    fun toWav(pcm: ByteArray, sampleRate: Int = RATE): ByteArray {
        val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return out.array()
    }

    /** Root-mean-square level in 0..1. */
    fun rms(pcm: ByteArray): Double {
        if (pcm.size < 2) return 0.0
        val sb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        val n = sb.remaining()
        for (i in 0 until n) { val v = sb.get(i) / 32768.0; sum += v * v }
        return Math.sqrt(sum / n)
    }

    fun toFloats(pcm: ByteArray): FloatArray {
        val sb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
    }
}

/**
 * Streaming conversion of arbitrary interleaved PCM to mono at 16 kHz. Channels are averaged; sample-rate
 * conversion uses area averaging, which is enough for speech and avoids aliasing when downsampling.
 */
class StreamingResampler(private val srcRate: Int, private val channels: Int, private val dstRate: Int = Pcm.RATE) {
    private val step = srcRate.toDouble() / dstRate // source samples per output sample
    private var acc = 0.0      // sum of source samples inside the current output window
    private var covered = 0.0  // how much of the current window is filled (in source samples)

    /** [interleaved] holds `channels` values per frame, scaled to -1..1. Returns mono samples at the target rate. */
    fun process(interleaved: FloatArray, frames: Int, out: MutableList<Short>) {
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) s += interleaved[f * channels + c]
            s /= channels
            var remaining = 1.0 // this source sample is worth one unit of window coverage
            while (remaining > 1e-9) {
                val room = step - covered
                val take = minOf(room, remaining)
                acc += s * take; covered += take; remaining -= take
                if (covered >= step - 1e-9) {
                    out += (acc / step * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                    acc = 0.0; covered = 0.0
                }
            }
        }
    }
}

/** One stretch of time inside a media file. */
data class Span(val startMs: Long, val endMs: Long) {
    val length get() = endMs - startMs
}

/** One recognised utterance. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)
