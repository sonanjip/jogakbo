package io.github.taehunkim.jogakbo.notes

import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.fetch.Article
import io.github.taehunkim.jogakbo.fetch.Video

/** The Markdown originals of notes made from a source. */
object Bodies {
    fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun article(a: Article): String = a.markdown

    /** Description, then the subtitles in paragraphs of about [paragraphMs], each starting with its time. */
    fun video(v: Video, noSubtitles: String, descriptionHeading: String, transcriptHeading: String): String = buildString {
        if (v.description.isNotBlank()) {
            append("## ").append(descriptionHeading).append("\n\n").append(v.description.trim().replace("\n", "  \n")).append("\n\n")
        }
        append("## ").append(transcriptHeading).append("\n\n")
        if (v.cues.isEmpty()) append("*").append(noSubtitles).append("*\n") else append(transcript(v.cues))
    }.trim()

    /** Transcript lines in paragraphs of about [paragraphMs], each starting with its time in bold. */
    fun transcript(cues: List<Cue>, paragraphMs: Long = 30_000): String {
        val sb = StringBuilder()
        var paraStart = -1L
        for (c in cues) {
            if (paraStart < 0 || c.startMs - paraStart >= paragraphMs) {
                if (paraStart >= 0) sb.append("\n\n")
                sb.append("**").append(time(c.startMs)).append("** ")
                paraStart = c.startMs
            } else sb.append(' ')
            sb.append(c.text.trim())
        }
        return sb.toString().trim()
    }

    /** Plain text of the summary input for notes with a transcript (times dropped: they waste tokens). */
    fun transcriptText(cues: List<Cue>): String = cues.joinToString(" ") { it.text.trim() }
}
