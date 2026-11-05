package io.github.taehunkim.jogakbo.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.taehunkim.jogakbo.audio.Pcm
import java.io.File

/**
 * Encodes 16 kHz mono 16-bit PCM to AAC in an .m4a file, fed piece by piece (while recording). Presentation
 * times follow the number of samples fed, so the file's timeline matches the PCM's (pauses are left out of both).
 */
class AacEncoder(private val out: File, bitrate: Int = BITRATE) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var samples = 0L
    private val info = MediaCodec.BufferInfo()
    private var finished = false

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Pcm.RATE, 1)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Feeds [len] bytes of PCM. */
    fun feed(pcm: ByteArray, len: Int = pcm.size) {
        var off = 0
        while (off < len) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                val n = minOf(buf.remaining(), len - off) and 1.inv()
                buf.put(pcm, off, n)
                codec.queueInputBuffer(idx, 0, n, samples * 1_000_000 / Pcm.RATE, 0)
                samples += n / 2
                off += n
            }
            drain(false)
        }
    }

    /** Ends the stream and closes the file. */
    fun finish() {
        if (finished) return
        finished = true
        try {
            val idx = codec.dequeueInputBuffer(100_000)
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, samples * 1_000_000 / Pcm.RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            runCatching { codec.stop() }; codec.release()
            if (track >= 0) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return else continue
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer.writeSampleData(track, buf, info) }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        const val BITRATE = 48_000

        /** Encodes a whole PCM file (recovering a recording the app was killed during). */
        fun encodeFile(pcm: File, out: File) {
            val enc = AacEncoder(out)
            try {
                pcm.inputStream().buffered().use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) { val n = input.read(buf); if (n <= 0) break; enc.feed(buf, n) }
                }
            } finally { enc.finish() }
        }
    }
}
