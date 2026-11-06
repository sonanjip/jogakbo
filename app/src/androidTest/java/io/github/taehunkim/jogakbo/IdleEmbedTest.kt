package io.github.taehunkim.jogakbo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.taehunkim.jogakbo.index.EmbedWorker
import io.github.taehunkim.jogakbo.index.NoteIndexer
import io.github.taehunkim.ondolsaem.sdk.IdleRule
import io.github.taehunkim.ondolsaem.sdk.IdleState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real "when idle" embedding pass on the device: it does nothing until the screen has been off long enough for the
 * battery level, then embeds the waiting notes. Needs a note waiting, OndolSaem's embedding model, the screen off and a
 * charger (on the emulator: `adb shell dumpsys battery set ac 1; ... set status 2; ... set level 95` and
 * `adb shell input keyevent 223`); skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class IdleEmbedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val c get() = (ctx.applicationContext as JogakboApp).container

    private fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<EmbedWorker>(ctx.applicationContext).setInputData(workDataOf(EmbedWorker.KEY_SOURCE to "idle")).build().doWork()
    }

    @Test fun waitsForTheTierThenEmbeds() = runBlocking {
        val idle = IdleState(ctx)
        val s = idle.snapshot()
        assumeTrue("needs a charger and the screen off ($s)", s.charging && !s.screenOn)
        val key = NoteIndexer.key(c.settings.embeddingModelId.first())
        val waiting = c.db.noteDao().needingEmbedding(key).size
        assumeTrue("no note waiting for embedding", waiting > 0)
        val preset = c.settings.idlePreset.first()
        val needMin = IdleRule.requiredOffMinutes(preset, s.batteryPct)
        val now = System.currentTimeMillis()
        try {
            idle.setScreenOffSince(now - (needMin - 1) * 60_000L)
            assertEquals(ListenableWorker.Result.success(), run())
            assertEquals("embedded before $needMin min off at ${s.batteryPct} %", waiting, c.db.noteDao().needingEmbedding(key).size)

            idle.setScreenOffSince(now - (needMin + 1) * 60_000L)
            assertEquals(ListenableWorker.Result.success(), run())
            assertEquals(0, c.db.noteDao().needingEmbedding(key).size)
        } finally {
            idle.setScreenOffSince(null)
        }
    }
}
