package io.github.taehunkim.jogakbo.notes

import io.github.taehunkim.jogakbo.sdk.NewAudioNote
import io.github.taehunkim.jogakbo.sdk.NoteCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewAudioNoteTest {
    @Test fun roundTrips() {
        val note = NewAudioNote("Voice memo", listOf(NoteCue(0, 1500, "hello"), NoteCue(1600, 3000, "world")), 3000, 1_700_000_000_000, "m4a", true, "dorangori")
        assertEquals(note, NewAudioNote.parse(note.toJson()))
    }

    @Test fun optionalFieldsMayBeMissing() {
        val note = NewAudioNote.parse("""{"title":"x","cues":[]}""")!!
        assertEquals(NewAudioNote("x", emptyList(), 0, null, "m4a", null, null), note)
    }

    @Test fun badExtensionFallsBackToM4a() {
        assertEquals("m4a", NewAudioNote.parse("""{"ext":"../x"}""")!!.ext)
    }

    @Test fun garbageIsRejected() {
        assertNull(NewAudioNote.parse("not json"))
        assertNull(NewAudioNote.parse("""{"cues":[{"s":1}]}"""))
    }
}
