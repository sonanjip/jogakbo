package io.github.taehunkim.jogakbo.record

import android.util.Log
import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.audio.Pcm
import io.github.taehunkim.jogakbo.audio.SpeechTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** What the recording screen shows. */
data class RecState(val elapsedMs: Long = 0, val level: Float = 0f, val paused: Boolean = false, val transcribing: Boolean = false)

/**
 * One recording: reads [source], keeps the raw audio in [pcmFile] (for transcription), encodes it to [m4aFile]
 * (for playback) and transcribes it while it runs when [speech] can ([LiveTranscriber]).
 */
class Recorder(
    private val source: PcmSource,
    private val pcmFile: File,
    private val m4aFile: File,
    private val speech: SpeechTools?,
    private val scope: CoroutineScope,
    private val onCue: suspend (Cue) -> Unit,
    private val passEveryMs: Long = 1_000,
) {
    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()
    private val written = AtomicLong(0)
    @Volatile private var paused = false
    @Volatile private var running = false
    private var reader: Job? = null
    private var live: Job? = null
    private val passLock = Mutex()
    private val transcriber = speech?.takeIf { it.canTranscribe }?.let { LiveTranscriber(it, pcmFile, File(pcmFile.parentFile, pcmFile.name + ".tail"), onCue) }

    val canTranscribe: Boolean get() = transcriber != null
    val recordedMs: Long get() = Pcm.byteToMs(written.get())

    fun start() {
        check(!running)
        running = true
        source.start()
        val encoder = AacEncoder(m4aFile)
        reader = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(Pcm.BYTES_PER_SECOND / 10) // 100 ms
            try {
                pcmFile.outputStream().use { out ->
                    while (running && isActive) {
                        val n = source.read(buf)
                        if (n < 0) break
                        if (n == 0 || paused) continue
                        out.write(buf, 0, n); out.flush()
                        encoder.feed(buf, n)
                        written.addAndGet(n.toLong())
                        _state.value = _state.value.copy(elapsedMs = recordedMs, level = Pcm.rms(buf.copyOf(n)).toFloat())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "recording stopped", e)
            } finally {
                runCatching { encoder.finish() }.onFailure { Log.w(TAG, "encoder", it) }
                runCatching { source.close() }
            }
        }
        transcriber?.let { t ->
            live = scope.launch(Dispatchers.IO) {
                while (isActive && running) {
                    delay(passEveryMs)
                    passLock.withLock {
                        _state.value = _state.value.copy(transcribing = true)
                        runCatching { t.pass(recordedMs, final = false) }.onFailure { Log.w(TAG, "live pass", it) }
                        _state.value = _state.value.copy(transcribing = false)
                    }
                }
            }
        }
    }

    fun pause() { paused = true; _state.value = _state.value.copy(paused = true, level = 0f) }
    fun resume() { paused = false; _state.value = _state.value.copy(paused = false) }

    /** Ends the recording: closes the files and transcribes what is left. Returns the length in ms. */
    suspend fun stop(): Long {
        running = false
        reader?.join()
        live?.cancel(); live?.join()
        transcriber?.let { t -> passLock.withLock { t.pass(recordedMs, final = true) } }
        File(pcmFile.parentFile, pcmFile.name + ".tail").delete()
        return recordedMs
    }

    /** True when the source ended by itself (a file source in tests, a microphone error). */
    val ended: Boolean get() = reader?.isCompleted == true

    companion object { private const val TAG = "Jogakbo.Recorder" }
}
