package io.github.taehunkim.jogakbo.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.taehunkim.jogakbo.audio.Pcm
import java.io.File
import java.io.InputStream

/** Where a recording's audio comes from: 16 kHz mono 16-bit PCM. */
interface PcmSource : AutoCloseable {
    fun start()
    /** Blocks until some audio is there; returns the bytes read, or -1 at the end. */
    fun read(buf: ByteArray): Int
}

/** The microphone. Needs RECORD_AUDIO. */
class MicSource : PcmSource {
    private var rec: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        val min = AudioRecord.getMinBufferSize(Pcm.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Pcm.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Pcm.BYTES_PER_SECOND))
        check(r.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        r.startRecording()
        rec = r
    }

    override fun read(buf: ByteArray): Int = rec?.read(buf, 0, buf.size)?.takeIf { it >= 0 } ?: -1

    override fun close() { rec?.let { runCatching { it.stop() }; it.release() }; rec = null }
}

/** A PCM file played as if it were the microphone ([speed] 1 = real time; tests use more). */
class FileSource(private val file: File, private val speed: Double = 1.0) : PcmSource {
    private var input: InputStream? = null
    private var started = 0L
    private var delivered = 0L

    override fun start() { input = file.inputStream().buffered(); started = System.nanoTime() }

    override fun read(buf: ByteArray): Int {
        val n = input?.read(buf) ?: -1
        if (n <= 0) return -1
        delivered += n
        val dueNs = (Pcm.byteToMs(delivered) / speed * 1_000_000).toLong()
        val waitNs = dueNs - (System.nanoTime() - started)
        if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
        return n
    }

    override fun close() { input?.close(); input = null }
}
