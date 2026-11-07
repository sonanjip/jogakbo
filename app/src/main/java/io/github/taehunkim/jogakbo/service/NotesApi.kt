package io.github.taehunkim.jogakbo.service

import io.github.taehunkim.jogakbo.data.db.NoteDao
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.search.NoteFilter
import io.github.taehunkim.jogakbo.search.NoteSearch
import io.github.taehunkim.jogakbo.sdk.Jogakbo
import io.github.taehunkim.jogakbo.sdk.JogakboErrors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What other apps may do with the notes (the INotes service): search them and read them, and learn which changed.
 * Plain logic so tests can call it without Binder.
 */
class NotesApi(
    private val dao: NoteDao, private val search: NoteSearch, private val appVersion: String, private val zone: TimeZone = TimeZone.getDefault(),
    private val tags: io.github.taehunkim.jogakbo.data.db.TagDao? = null,
) {
    fun hello(clientJson: String): String {
        val api = runCatching { JSONObject(clientJson).optInt("api") }.getOrDefault(0)
        if (api < MIN_CLIENT_API) throw JogakboErrors.encode(Jogakbo.Errors.CLIENT_TOO_OLD, "client API $api, need $MIN_CLIENT_API")
        return JSONObject().put("api", Jogakbo.API_VERSION).put("minClientApi", MIN_CLIENT_API).put("appVersion", appVersion).toString()
    }

    fun tools(): String = JSONArray()
        .put(JSONObject().put("name", Jogakbo.Tools.SEARCH_NOTES).put("description", SEARCH_DESCRIPTION).put("parameters", JSONObject(SEARCH_PARAMETERS)).put("confirm", false))
        .put(JSONObject().put("name", Jogakbo.Tools.READ_NOTE).put("description", READ_DESCRIPTION).put("parameters", JSONObject(READ_PARAMETERS)).put("confirm", false))
        .put(JSONObject().put("name", Jogakbo.Tools.SEARCH_IN_NOTE).put("description", SEARCH_IN_DESCRIPTION).put("parameters", JSONObject(SEARCH_IN_PARAMETERS)).put("confirm", false))
        .toString()

    fun runTool(name: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrNull() ?: return "Error: arguments must be a JSON object."
        return when (name) {
            Jogakbo.Tools.SEARCH_NOTES -> runBlocking { searchNotes(args) }
            Jogakbo.Tools.READ_NOTE -> runBlocking { readNoteTool(args) }
            Jogakbo.Tools.SEARCH_IN_NOTE -> runBlocking { searchInNote(args) }
            else -> throw JogakboErrors.encode(Jogakbo.Errors.BAD_INPUT, "unknown tool $name")
        }
    }

    private suspend fun searchNotes(args: JSONObject): String {
        val query = args.optString("query").trim()
        val kinds = args.optString("kinds").split(',').mapNotNull { NoteKind.fromWire(it.trim()) }.toSet()
        val from = args.optString("date_from").takeIf { it.isNotBlank() }?.let { day(it) ?: return "Error: date_from must be YYYY-MM-DD." }
        val to = args.optString("date_to").takeIf { it.isNotBlank() }?.let { day(it)?.plus(DAY_MS) ?: return "Error: date_to must be YYYY-MM-DD." }
        val limit = args.optInt("limit", 5).coerceIn(1, 10)
        val filter = NoteFilter(kinds, from, to)
        // tag filter: notes that carry any of the named tags
        val wanted = args.optString("tags").split(',').map { it.trim().removePrefix("#").lowercase() }.filter { it.isNotEmpty() }.toSet()
        val tagged: Set<Long>? = if (wanted.isEmpty()) null else
            tags?.ofNotes(dao.all().map { it.id })?.filter { it.state.applied && it.name.lowercase() in wanted }?.map { it.noteId }?.toSet().orEmpty()
        val hits = if (query.isEmpty()) {
            // no query: newest notes that fit the filters
            dao.all().asReversed().asSequence().filter { n -> (kinds.isEmpty() || n.kind in kinds) && (from == null || n.createdAt >= from) && (to == null || n.createdAt < to) }
                .filter { tagged == null || it.id in tagged }
                .take(limit).map { it to NoteSearch.snippet(it.summary ?: it.body) }.toList()
        } else search.search(query, filter, if (tagged == null) limit else MAX_CHANGES).filter { tagged == null || it.note.id in tagged }.take(limit).map { it.note to it.snippet }
        if (hits.isEmpty()) return "No matching notes."
        val names = tagNames(hits.map { it.first.id })
        return JSONArray().apply {
            hits.forEach { (n, snippet) -> put(info(n, names[n.id]).put("snippet", snippet)) }
        }.toString()
    }

    private suspend fun readNoteTool(args: JSONObject): String {
        val id = args.optLong("note_id", -1)
        if (id < 0) return "Error: note_id is required."
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val max = args.optInt("max_chars", DEFAULT_CHARS).coerceIn(200, MAX_CHARS)
        val n = dao.get(id) ?: return "Error: no note with note_id $id."
        val text = markdown(n)
        val slice = text.drop(offset).take(max)
        val o = info(n, tagNames(listOf(n.id))[n.id]).put("text", slice)
        if (offset + slice.length < text.length) o.put("next_offset", offset + slice.length)
        return o.toString()
    }

    private suspend fun searchInNote(args: JSONObject): String {
        val id = args.optLong("note_id", -1)
        val query = args.optString("query").trim()
        if (id < 0) return "Error: note_id is required."
        if (query.isEmpty()) return "Error: query is required."
        val limit = args.optInt("limit", DEFAULT_PASSAGES).coerceIn(1, MAX_PASSAGES)
        val n = dao.get(id) ?: return "Error: no note with note_id $id."
        val r = search.within(n, query, limit)
        val out = JSONArray()
        for (h in r.hits) out.put(JSONObject().apply {
            put("ordinal", h.passage.ordinal)
            h.passage.title?.takeIf { it.isNotBlank() }?.let { put("section", it) }
            if (h.passage.summary) put("part", "summary")
            put("score", "%.2f".format(Locale.ROOT, h.score))
            put("text", h.passage.text.trim().let { if (it.length > PASSAGE_CHARS) it.take(PASSAGE_CHARS) + "…" else it })
        })
        return info(n, tagNames(listOf(n.id))[n.id]).put("method", if (r.semantic) "semantic" else "words").put("total_passages", r.total).put("passages", out)
            .also { if (r.hits.isEmpty()) it.put("note", "Nothing in this note matches; try other words or read_note.") }.toString()
    }

    fun notes(ids: LongArray): String = runBlocking {
        val names = tagNames(ids.toList())
        JSONArray().apply { dao.getAll(ids.toList()).sortedBy { ids.indexOf(it.id) }.forEach { put(info(it, names[it.id])) } }.toString()
    }

    fun readNote(id: Long, maxChars: Int): String = runBlocking {
        dao.get(id)?.let { markdown(it).take(maxChars.coerceIn(0, MAX_TEXT)) } ?: ""
    }

    fun changesSince(cursor: Long, limit: Int): String = runBlocking {
        if (cursor <= 0) return@runBlocking JSONObject().put("ids", JSONArray()).put("cursor", dao.lastUpdate()).toString()
        val changes = dao.changedAfter(cursor, limit.coerceIn(1, MAX_CHANGES))
        JSONObject().put("ids", JSONArray(changes.map { it.id })).put("cursor", changes.lastOrNull()?.updatedAt ?: cursor).toString()
    }

    private fun fmt(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }.format(Date(ms))

    private fun day(s: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone; isLenient = false }.parse(s)!!.time
    }.getOrNull()

    /** The tags shown on each note (the decision model's and the user's). */
    private suspend fun tagNames(ids: List<Long>): Map<Long, List<String>> =
        if (ids.isEmpty()) emptyMap() else tags?.ofNotes(ids)?.filter { it.state.applied }?.groupBy({ it.noteId }, { it.name }).orEmpty()

    private fun info(n: NoteEntity, tagNames: List<String>? = null) = JSONObject()
        .put("note_id", n.id).put("title", n.title).put("kind", n.kind.wire)
        .put("created", fmt(n.createdAt)).put("updated", fmt(n.updatedAt))
        .apply { n.sourceUrl?.let { put("url", it) } }
        .apply { if (!tagNames.isNullOrEmpty()) put("tags", JSONArray(tagNames)) }

    companion object {
        const val MIN_CLIENT_API = Jogakbo.Api.V1
        const val DEFAULT_CHARS = 3000
        const val MAX_CHARS = 6000
        const val MAX_TEXT = 50_000
        const val MAX_CHANGES = 500
        private const val DAY_MS = 24L * 3600 * 1000
        const val DEFAULT_PASSAGES = 3
        const val MAX_PASSAGES = 5
        const val PASSAGE_CHARS = 900

        const val SEARCH_IN_DESCRIPTION =
            "Find the passages of ONE note (found with search_notes, or attached by the user) that best answer a question or match " +
                "some words: returns the best few passages with their section (and page for documents). Use it for long notes " +
                "instead of reading everything; read_note can then read on from the start."
        const val SEARCH_IN_PARAMETERS = """{"type":"object","properties":{
            "note_id":{"type":"integer","description":"The note_id from search_notes."},
            "query":{"type":"string","description":"What to look for, as a question or a few words."},
            "limit":{"type":"integer","description":"How many passages, 1 to 5 (default 3)."}},
            "required":["note_id","query"]}"""

        const val SEARCH_DESCRIPTION =
            "Search the user's notes in Jogakbo (조각보): memos they wrote, saved web pages and YouTube videos, recordings and attached documents (PDF, Word, text), " +
                "each with an AI summary. Finds notes by meaning and by words, optionally only notes with given tags. Returns note_id, title, kind, dates, url, tags and a snippet; " +
                "use read_note for the full text."
        const val SEARCH_PARAMETERS = """{"type":"object","properties":{
            "query":{"type":"string","description":"What to look for: words or a description. Leave out to list the newest notes."},
            "kinds":{"type":"string","description":"Optional comma separated filter: memo, link, youtube, audio, document."},
            "tags":{"type":"string","description":"Optional comma separated tag names; only notes with any of them (for example 여행, 요리)."},
            "date_from":{"type":"string","description":"Optional: notes created on or after this date, YYYY-MM-DD."},
            "date_to":{"type":"string","description":"Optional: notes created on or before this date, YYYY-MM-DD."},
            "limit":{"type":"integer","description":"Number of results, 1 to 10 (default 5)."}}}"""
        const val READ_DESCRIPTION =
            "Read one note found with search_notes, as Markdown: the title, the summary, then the original (memo text, web page, " +
                "video description and subtitles, or recording transcript). Long notes are read in slices; continue with next_offset."
        const val READ_PARAMETERS = """{"type":"object","properties":{
            "note_id":{"type":"integer","description":"The note_id from search_notes."},
            "offset":{"type":"integer","description":"Character offset to continue from (default 0)."},
            "max_chars":{"type":"integer","description":"Slice length, up to 6000 (default 3000)."}},
            "required":["note_id"]}"""

        /** A note as one Markdown text: title, source, summary, original. */
        fun markdown(n: NoteEntity): String = buildString {
            append("# ").append(n.title).append("\n\n")
            n.sourceUrl?.let { append("Source: ").append(it).append("\n\n") }
            n.summary?.takeIf { it.isNotBlank() }?.let { append("## Summary\n\n").append(it.trim()).append("\n\n") }
            if (n.body.isNotBlank()) append("## Original\n\n").append(n.body.trim())
        }.trim()
    }
}
