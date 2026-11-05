package io.github.taehunkim.jogakbo.fetch

import io.github.taehunkim.jogakbo.audio.Cue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTest {
    @Test fun videoIds() {
        val id = "dQw4w9WgXcQ"
        for (u in listOf(
            "https://www.youtube.com/watch?v=$id", "https://m.youtube.com/watch?feature=share&v=$id&t=10s", "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id", "https://youtube.com/live/$id", "https://music.youtube.com/watch?v=$id", "https://www.youtube.com/embed/$id",
        )) assertEquals(u, id, YouTubeFetcher.videoId(u))
        assertNull(YouTubeFetcher.videoId("https://www.youtube.com/channel/UC123"))
        assertNull(YouTubeFetcher.videoId("https://example.com/watch?v=$id"))
    }

    @Test fun trackChoice() {
        val tracks = listOf(
            CaptionTrack("u1", "en", null, "English"), CaptionTrack("u2", "ko", "asr", "Korean (auto)"), CaptionTrack("u3", "ko", null, "Korean"),
        )
        assertEquals("u3", YouTubeFetcher.chooseTrack(tracks)!!.baseUrl) // manual track in the spoken language
        assertEquals("u2", YouTubeFetcher.chooseTrack(tracks.take(2))!!.baseUrl) // else the generated one
        assertEquals("u1", YouTubeFetcher.chooseTrack(tracks.take(1))!!.baseUrl)
    }

    @Test fun timedTextFormats() {
        val json3 = """{"events":[{"tStartMs":0,"dDurationMs":1500,"segs":[{"utf8":"Hello"},{"utf8":" world."}]},{"tStartMs":1600,"dDurationMs":900,"segs":[{"utf8":"\n"}]}]}"""
        assertEquals(listOf(Cue(0, 1500, "Hello world.")), YouTubeFetcher.parseTimedText(json3))
        val xml = """<?xml version="1.0"?><transcript><text start="1.5" dur="2">It&amp;#39;s here</text><text start="4" dur="1">bye</text></transcript>"""
        val c = YouTubeFetcher.parseTimedText(xml)
        assertEquals(2, c.size); assertEquals(1500L, c[0].startMs); assertEquals("It's here", c[0].text)
        val srv3 = """<timedtext format="3"><body><p t="100" d="200">a</p><p t="400" d="100">b</p></body></timedtext>"""
        assertEquals(listOf(Cue(100, 300, "a"), Cue(400, 500, "b")), YouTubeFetcher.parseTimedText(srv3))
    }

    @Test fun mergesFragmentsIntoSentences() {
        val m = YouTubeFetcher.mergeCues(listOf(Cue(0, 1000, "so today we"), Cue(1000, 2000, "talk about"), Cue(2000, 3000, "notes."), Cue(3000, 4000, "next")))
        assertEquals(listOf("so today we talk about notes.", "next"), m.map { it.text })
        assertEquals(0L, m[0].startMs)
    }

    private val page = """<html><script>var ytInitialPlayerResponse = {"videoDetails":{"title":"A {test} \"video\"","author":"Chan","shortDescription":"Desc","lengthSeconds":"62"},
        "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=en&fmt=srv3","languageCode":"en","kind":"asr"}]}},
        "microformat":{"playerMicroformatRenderer":{"publishDate":"2025-01-02"}}};var x = 1;</script>"INNERTUBE_API_KEY":"KEY"</html>"""

    @Test fun readsPlayerResponseFromPage() {
        val p = YouTubeFetcher.playerResponse(page)!!
        assertEquals("A {test} \"video\"", p.getJSONObject("videoDetails").getString("title"))
        assertEquals(1, YouTubeFetcher.tracks(p).size)
    }

    @Test fun fetchWithFakeNetwork() = runTest {
        val http = object : HttpClient {
            val asked = ArrayList<String>()
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
                asked += url
                return when {
                    "watch" in url -> HttpResponse(url, 200, "text/html", page)
                    "timedtext" in url -> HttpResponse(url, 200, "text/xml", """<transcript><text start="0" dur="1">Hi there.</text></transcript>""")
                    else -> HttpResponse(url, 404, null, "")
                }
            }
            override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = HttpResponse(url, 403, null, "")
        }
        val v = YouTubeFetcher(http).fetch("https://youtu.be/dQw4w9WgXcQ")
        assertEquals("A {test} \"video\"", v.title)
        assertEquals("Chan", v.channel)
        assertEquals(62L, v.lengthSec)
        assertEquals(listOf("Hi there."), v.cues.map { it.text })
        assertTrue(v.autoCaptions)
        assertTrue(http.asked.none { "fmt=srv3" in it }) // the default format was asked for
    }
}
