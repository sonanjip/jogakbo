package io.github.taehunkim.jogakbo.record

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.taehunkim.jogakbo.JogakboApp
import io.github.taehunkim.jogakbo.Notifications
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.audio.SpeechTools
import io.github.taehunkim.jogakbo.data.db.CueEntity
import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.ml.Lease
import io.github.taehunkim.jogakbo.notes.Bodies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The recording in progress, for the UI (null when nothing records). */
data class RecordingSession(val noteId: Long, val state: RecState, val canTranscribe: Boolean, val finishing: Boolean = false)

/**
 * Records from the microphone as a foreground service (keeps going with the screen off), writes the lines of the
 * live transcript into the note as they come, and finishes the note when stopped.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: Recorder? = null
    private var speech: Lease<SpeechTools>? = null
    private var noteId: Long = -1
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (recorder == null && !starting) start(intent.getStringExtra(EXTRA_TITLE))
            ACTION_PAUSE -> recorder?.pause()
            ACTION_RESUME -> recorder?.resume()
            ACTION_STOP -> stop()
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    @Volatile private var starting = false

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this, 2, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun start(title: String?) {
        starting = true
        // foreground first (Android requires it within seconds of startForegroundService)
        ServiceCompat.startForeground(
            this, Notifications.ID_RECORDING, Notifications.recording(this, 0, getString(R.string.recording_starting), stopIntent()),
            if (android.os.Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        val c = (application as JogakboApp).container
        scope.launch {
            try {
                val name = title?.takeIf { it.isNotBlank() } ?: getString(R.string.recording_title, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
                val note = c.notes.newRecording(name)
                noteId = note.id
                val dir = File(filesDir, "rec").apply { mkdirs() }
                val lease = runCatching { c.models.speech() }.getOrNull()
                speech = lease
                var ordinal = 0
                val dao = c.db.noteDao()
                val r = Recorder(
                    source = sourceFactory(), pcmFile = File(dir, "${note.uuid}.pcm"), m4aFile = File(c.notes.audioDir, note.audioFile!!),
                    speech = lease?.value, scope = scope,
                    onCue = { cue: Cue -> dao.insertCues(listOf(CueEntity(noteId = note.id, ordinal = ordinal++, startMs = cue.startMs, endMs = cue.endMs, text = cue.text))) },
                )
                r.start()
                recorder = r
                stateJob = scope.launch {
                    r.state.collectLatest { s ->
                        _current.value = RecordingSession(note.id, s, r.canTranscribe)
                        if (r.ended && s.elapsedMs > 0 && sourceFactoryOverride != null) stop() // a test source ran out
                    }
                }
                Notifications.notify(this@RecordingService, Notifications.ID_RECORDING, Notifications.recording(this@RecordingService, note.id, name, stopIntent()))
            } catch (e: Exception) {
                Log.e(TAG, "cannot record", e)
                if (noteId > 0) c.db.noteDao().setSourceState(noteId, JobState.FAILED, e.message)
                _current.value = null
                stopSelf()
            } finally { starting = false }
        }
    }

    private var stopping = false

    private fun stop() {
        val r = recorder ?: run { if (!starting) stopSelf(); return }
        if (stopping) return
        stopping = true
        val c = (application as JogakboApp).container
        _current.value = _current.value?.copy(finishing = true)
        scope.launch {
            try {
                val duration = r.stop()
                val dao = c.db.noteDao()
                val n = dao.get(noteId)
                if (n != null) {
                    val pcm = File(filesDir, "rec/${n.uuid}.pcm")
                    val meta = runCatching { JSONObject(n.sourceMeta ?: "{}") }.getOrDefault(JSONObject()).apply { remove("recording") }
                    if (r.canTranscribe) {
                        val cues = dao.cues(noteId).map { Cue(it.startMs, it.endMs, it.text) }
                        dao.update(
                            n.copy(
                                body = if (cues.isEmpty()) "*${getString(R.string.audio_no_speech)}*" else Bodies.transcript(cues),
                                durationMs = duration, sourceMeta = meta.toString(), sourceState = JobState.DONE, sourceError = null,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        pcm.delete()
                        c.sourceJob.afterSource(noteId)
                    } else {
                        // no speech model while recording: transcribe the saved file later (Retry once OndolSaem has it)
                        dao.update(n.copy(durationMs = duration, sourceMeta = meta.toString(), sourceState = JobState.FAILED, sourceError = getString(R.string.source_no_asr)))
                        pcm.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "finishing the recording failed", e)
            } finally {
                speech?.close(); speech = null
                recorder = null
                _current.value = null
                ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        // killed without stop: SourceJob finishes the note from the PCM at the next start
        if (recorder != null && !stopping) _current.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Jogakbo.Recording"
        const val ACTION_START = "io.github.taehunkim.jogakbo.record.START"
        const val ACTION_PAUSE = "io.github.taehunkim.jogakbo.record.PAUSE"
        const val ACTION_RESUME = "io.github.taehunkim.jogakbo.record.RESUME"
        const val ACTION_STOP = "io.github.taehunkim.jogakbo.record.STOP"
        const val EXTRA_TITLE = "title"

        private val _current = MutableStateFlow<RecordingSession?>(null)
        val current: StateFlow<RecordingSession?> = _current.asStateFlow()

        /** Test hook: where the audio comes from instead of the microphone. */
        @Volatile var sourceFactoryOverride: (() -> PcmSource)? = null
        private fun sourceFactory(): PcmSource = sourceFactoryOverride?.invoke() ?: MicSource()

        fun start(ctx: Context, title: String? = null) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_TITLE, title))
        fun send(ctx: Context, action: String) { ctx.startService(Intent(ctx, RecordingService::class.java).setAction(action)) }
    }
}
