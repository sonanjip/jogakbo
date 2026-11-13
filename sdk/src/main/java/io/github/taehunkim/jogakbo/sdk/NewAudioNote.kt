package io.github.taehunkim.jogakbo.sdk

import org.json.JSONArray
import org.json.JSONObject

/** One transcript line of a [NewAudioNote], in milliseconds from the start of the recording. */
data class NoteCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * An audio note a client hands to Jogakbo with the text it already made (API 2, [JogakboClient.createAudioNote]).
 * Jogakbo keeps the audio and the transcript as ONE note and does not recognise the speech again.
 */
data class NewAudioNote(
    val title: String,
    val cues: List<NoteCue>,
    val durationMs: Long,
    /** When it was recorded (epoch ms), for the note's details. */
    val recordedAtMs: Long? = null,
    /** File extension of the audio ("m4a"). */
    val ext: String = "m4a",
    /** True/false forces/skips the summary; null follows Jogakbo's settings. */
    val summarize: Boolean? = null,
    /** Which app made it, kept in the note's details. */
    val origin: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("title", title)
        .put("cues", JSONArray().also { a -> cues.forEach { a.put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)) } })
        .put("durationMs", durationMs)
        .put("ext", ext)
        .apply {
            recordedAtMs?.let { put("recordedAt", it) }
            summarize?.let { put("summarize", it) }
            origin?.let { put("origin", it) }
        }.toString()

    companion object {
        /** Null when [json] is not a usable request (the service answers BAD_INPUT). */
        fun parse(json: String): NewAudioNote? = try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("cues") ?: JSONArray()
            val cues = List(arr.length()) { i -> arr.getJSONObject(i).let { NoteCue(it.getLong("s"), it.getLong("e"), it.getString("t")) } }
            val ext = o.optString("ext", "m4a").lowercase().takeIf { Regex("[a-z0-9]{2,5}").matches(it) } ?: "m4a"
            NewAudioNote(
                title = o.optString("title").trim(), cues = cues, durationMs = o.optLong("durationMs", 0L).coerceAtLeast(0L),
                recordedAtMs = if (o.has("recordedAt")) o.getLong("recordedAt") else null, ext = ext,
                summarize = if (o.has("summarize")) o.getBoolean("summarize") else null,
                origin = o.optString("origin").ifBlank { null },
            )
        } catch (_: Exception) { null }
    }
}
