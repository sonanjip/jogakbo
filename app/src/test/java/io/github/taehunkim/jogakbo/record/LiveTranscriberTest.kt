package io.github.taehunkim.jogakbo.record

import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.audio.Pcm
import io.github.taehunkim.jogakbo.audio.Span
import io.github.taehunkim.jogakbo.audio.SpeechTools
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LiveTranscriberTest {
    /** "Hears" a word every 2 s of the file it is given, each 1.5 s long, named by its absolute second. */
    private class FakeSpeech(private val offsetOf: () -> Long) : SpeechTools {
        override val canTranscribe = true
        override suspend fun speechSpans(pcm: File) = emptyList<Span>()
        override suspend fun transcribe(pcm: File, fromMs: Long, onCue: suspend (Cue) -> Unit) {
            val len = Pcm.byteToMs(pcm.length())
            val base = offsetOf()
            var t = (2000 - base % 2000) % 2000
            while (t + 1500 <= len + 400) { // the last one may run past the end (cut off)
                onCue(Cue(t, t + 1500, "w${(base + t) / 1000}"))
                t += 2000
            }
        }
    }

    @Test fun givesEachLineOnceAndHoldsTheLastOne() = runTest {
        val dir = kotlin.io.path.createTempDirectory("lt").toFile()
        val pcm = File(dir, "a.pcm")
        val scratch = File(dir, "a.tail")
        pcm.writeBytes(ByteArray(Pcm.msToByte(30_000).toInt()))
        val out = ArrayList<Cue>()
        lateinit var t: LiveTranscriber
        t = LiveTranscriber(FakeSpeech { t.committedMs }, pcm, scratch, { out += it })
        t.pass(4_000, final = false) // less than 5 s: nothing
        assertEquals(0, out.size)
        t.pass(9_000, final = false)
        assertEquals(listOf("w0", "w2", "w4", "w6"), out.map { it.text }) // w8 ends at 9.5 s: held
        t.pass(20_000, final = false)
        t.pass(30_000, final = true)
        val words = out.map { it.text }
        assertEquals(words.distinct(), words) // no line twice
        assertEquals("w28", words.last())
        assertEquals(out.sortedBy { it.startMs }, out)
        dir.deleteRecursively()
    }
}
