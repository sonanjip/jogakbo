package io.github.taehunkim.jogakbo

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.jogakbo.audio.AudioDecoder
import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.data.prefs.SettingsStore
import io.github.taehunkim.jogakbo.ml.ModelHost
import io.github.taehunkim.jogakbo.record.FileSource
import io.github.taehunkim.jogakbo.record.Recorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * With OndolSaem's real speech model (skipped without it, or without the fixture `long_talk.wav` from Nunsori's
 * make-audio-fixtures.sh copied into the app's cache: `adb push long_talk.wav /data/local/tmp/` then
 * `adb shell run-as io.github.taehunkim.jogakbo cp /data/local/tmp/long_talk.wav cache/`): the live transcript of a
 * recording fed from a file arrives while it records.
 */
@RunWith(AndroidJUnit4::class)
class RealModelTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Copied into the app's cache before the run (see docs/development.md). */
    private fun fixture(name: String): File? = File(ctx.cacheDir, name).takeIf { it.length() > 1000 }

    @Test fun liveTranscriptWithTheRealSpeechModel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val models = ModelHost(ctx, SettingsStore(ctx), scope)
            val speech = models.speech()
            assumeTrue("OndolSaem has no speech model", speech.value.canTranscribe)
            val wav = fixture("long_talk.wav")
            assumeTrue("fixture missing", wav != null)
            val pcm = File(ctx.cacheDir, "live-src.pcm")
            val duration = AudioDecoder.decode(ctx, Uri.fromFile(wav), pcm)
            val dir = File(ctx.cacheDir, "live").apply { deleteRecursively(); mkdirs() }
            val heard = ArrayList<Cue>()
            val r = Recorder(FileSource(pcm, speed = 2.0), File(dir, "rec.pcm"), File(dir, "rec.m4a"), speech.value, scope, { synchronized(heard) { heard += it } })
            r.start()
            var during = 0
            while (!r.ended) { delay(200); during = synchronized(heard) { heard.size } }
            r.stop()
            speech.close()
            android.util.Log.i("RealModelTest", "duration=$duration during=$during total=${heard.size}: " + heard.joinToString(" | ") { "${it.startMs}: ${it.text}" })
            assertTrue("lines while recording: $during", during >= 2)
            assertTrue(heard.joinToString(" ") { it.text }.length > 100)
            assertTrue(heard.map { it.startMs } == heard.map { it.startMs }.sorted())
        } finally { scope.cancel() }
    }
}
