package io.github.taehunkim.jogakbo.summary

import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.data.prefs.SummaryLanguage

/** What to summarize and how. */
data class SummaryRequest(
    val kind: NoteKind,
    val title: String,
    val text: String,
    /** The user's instruction for this note (may be blank). */
    val notePrompt: String? = null,
    /** The user's instruction for every summary (Settings; may be blank). */
    val globalPrompt: String = "",
    val language: SummaryLanguage = SummaryLanguage.ORIGINAL,
    /** The app's language in English ("Korean"), used when [language] is [SummaryLanguage.APP]. */
    val appLanguage: String = "English",
)

/**
 * Builds the prompts. Order in the system prompt: what to do (by kind) and the output format (Markdown), the
 * user's global instructions, then the language line last so it wins. The note's own instruction goes in the user
 * turn right above the text.
 */
object PromptBuilder {
    fun source(kind: NoteKind): String = when (kind) {
        NoteKind.MEMO -> "The text is a personal note the user wrote."
        NoteKind.LINK -> "The text is a web page the user saved."
        NoteKind.YOUTUBE -> "The text is a YouTube video's title, description and subtitles (auto-generated subtitles may contain errors)."
        NoteKind.AUDIO -> "The text is a speech-recognition transcript of a recording. It may contain recognition errors: read past obvious ones."
    }

    const val FORMAT =
        "Write the summary in Markdown: one or two sentences with the gist first, then the key points as a bullet list (\"- \"). " +
            "Use short headings (\"### \") only when the text has clearly separate parts, and **bold** only for the most important terms. " +
            "Keep names, numbers, dates, decisions and to-dos exact. Do not add anything that is not in the text. " +
            "Do not repeat the title, do not wrap the answer in a code block, and output only the summary."

    fun languageLine(req: SummaryRequest): String = when (req.language) {
        SummaryLanguage.ORIGINAL -> "Write in the same language as the text (if it mixes languages, use the main one)."
        SummaryLanguage.APP -> "Write in ${req.appLanguage}, even when the text is in another language."
    }

    private fun globalBlock(req: SummaryRequest): String? =
        req.globalPrompt.trim().takeIf { it.isNotEmpty() }?.let { "Instructions from the user for every summary:\n$it" }

    /** System prompt for a summary of the whole text (or of the notes on its parts). */
    fun system(req: SummaryRequest, fromPartNotes: Boolean = false): String = listOfNotNull(
        "You summarize the user's saved notes.",
        source(req.kind),
        if (fromPartNotes) "The text was too long to read at once: below are notes on its parts, in order. Combine them into one summary of the whole." else null,
        FORMAT,
        globalBlock(req),
        languageLine(req),
    ).joinToString("\n\n")

    /** System prompt for notes on one part of a long text (the map step). */
    fun partSystem(req: SummaryRequest, part: Int, parts: Int): String = listOfNotNull(
        "You take notes on part $part of $parts of a long text, so that a summary of the whole can be written from all the notes later.",
        source(req.kind),
        "Write Markdown bullet points (\"- \") with every important fact, name, number, date, decision and to-do in this part. No introduction, only the bullet points.",
        globalBlock(req),
        languageLine(req),
    ).joinToString("\n\n")

    /** The user turn: the note's own instruction, the title, then the text. */
    fun user(req: SummaryRequest, text: String): String = buildString {
        req.notePrompt?.trim()?.takeIf { it.isNotEmpty() }?.let { append("Instructions for this summary:\n").append(it).append("\n\n") }
        if (req.title.isNotBlank()) append("Title: ").append(req.title.trim()).append("\n\n")
        append("Text:\n").append(text.trim())
    }
}
