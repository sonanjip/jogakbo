package io.github.taehunkim.jogakbo.notes

import io.github.taehunkim.jogakbo.data.db.NoteEntity
import org.json.JSONObject

/**
 * When a note's summary, search index (embedding) and tags are made: now, or later at the time chosen in Settings.
 * A note can override the default; the choice is kept in the note's `sourceMeta` as `"now": true|false`
 * (absent: follow the default).
 */
object Timing {
    private const val KEY = "now"

    /** The note's own choice, or null when it follows the default. */
    fun override(note: NoteEntity): Boolean? = override(note.sourceMeta)

    fun override(meta: String?): Boolean? =
        runCatching { JSONObject(meta ?: "{}").takeIf { it.has(KEY) }?.getBoolean(KEY) }.getOrNull()

    /** [meta] with the choice set; [now] null removes it. Null when nothing is left. */
    fun withOverride(meta: String?, now: Boolean?): String? {
        val o = runCatching { JSONObject(meta ?: "{}") }.getOrDefault(JSONObject())
        if (now == null) o.remove(KEY) else o.put(KEY, now)
        return if (o.length() == 0) null else o.toString()
    }

    /** True when the note's work is to be done now, given the default ([defaultNow]) from Settings. */
    fun isNow(note: NoteEntity, defaultNow: Boolean): Boolean = override(note) ?: defaultNow
}
