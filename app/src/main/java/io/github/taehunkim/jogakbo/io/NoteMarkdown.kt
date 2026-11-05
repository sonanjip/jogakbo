package io.github.taehunkim.jogakbo.io

import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A note read from a Markdown file (export format, or any Markdown/text file). */
data class ParsedNote(
    val uuid: String?,
    val kind: NoteKind,
    val title: String,
    val body: String,
    val summary: String?,
    val summaryEdited: Boolean,
    val summaryPrompt: String?,
    val sourceUrl: String?,
    val sourceMeta: String?,
    val createdAt: Long?,
    val updatedAt: Long?,
    val durationMs: Long?,
    /** Paths inside the archive. */
    val audioPath: String?,
    val cuesPath: String?,
)

/**
 * One note as one Markdown file: YAML front matter with the facts, the title as `#` heading, then the summary and
 * the original, each after a marker comment so they read back exactly. Any other Markdown or text file reads as a
 * memo.
 */
object NoteMarkdown {
    const val FORMAT = 1
    const val SUMMARY_MARK = "<!-- jogakbo:summary -->"
    const val ORIGINAL_MARK = "<!-- jogakbo:original -->"
    private const val SUMMARY_HEAD = "## Summary\n\n"
    private const val ORIGINAL_HEAD = "## Original\n\n"

    fun write(n: NoteEntity, zone: ZoneId, includeSummary: Boolean, audioPath: String?, cuesPath: String?): String = buildString {
        append("---\n")
        field("jogakbo", FORMAT.toString())
        field("uuid", n.uuid)
        field("kind", n.kind.wire)
        field("title", q(n.title))
        field("created", time(n.createdAt, zone))
        field("updated", time(n.updatedAt, zone))
        n.sourceUrl?.let { field("source_url", q(it)) }
        if (includeSummary) {
            n.summaryPrompt?.let { field("summary_prompt", q(it)) }
            if (n.summaryEdited) field("summary_edited", "true")
        }
        n.durationMs?.let { field("duration_ms", it.toString()) }
        n.sourceMeta?.takeIf { it.isNotBlank() && it != "{}" }?.let { field("source_meta", q(it)) }
        audioPath?.let { field("audio", q(it)) }
        cuesPath?.let { field("cues", q(it)) }
        append("---\n\n")
        append("# ").append(n.title.replace("\n", " ")).append("\n\n")
        if (includeSummary && !n.summary.isNullOrBlank()) {
            append(SUMMARY_MARK).append('\n').append(SUMMARY_HEAD).append(n.summary.trim()).append("\n\n")
        }
        append(ORIGINAL_MARK).append('\n').append(ORIGINAL_HEAD).append(n.body.trim()).append('\n')
    }

    private fun StringBuilder.field(k: String, v: String) { append(k).append(": ").append(v).append('\n') }

    /** A YAML scalar as a JSON string (valid YAML double-quoted). */
    private fun q(s: String) = JSONObject.quote(s)

    private fun time(ms: Long, zone: ZoneId) = OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun read(text: String, fileName: String): ParsedNote {
        val src = text.replace("\r\n", "\n").removePrefix("\uFEFF")
        val (front, rest) = frontMatter(src)
        val f = front.orEmpty()
        val mine = f["jogakbo"] != null
        var content = rest.trimStart('\n')
        // the title heading we wrote
        var title = f["title"]
        val firstLine = content.lineSequence().firstOrNull().orEmpty()
        if (firstLine.startsWith("# ")) {
            val heading = firstLine.removePrefix("# ").trim()
            if (title == null || heading == title.replace("\n", " ")) { title = title ?: heading; content = content.substringAfter('\n', "").trimStart('\n') }
        }
        var summary: String? = null
        var body = content
        if (mine && content.contains(ORIGINAL_MARK)) {
            val beforeOriginal = content.substringBefore(ORIGINAL_MARK)
            body = content.substringAfter(ORIGINAL_MARK).removePrefix("\n").removePrefix(ORIGINAL_HEAD).trim()
            if (beforeOriginal.contains(SUMMARY_MARK)) {
                summary = beforeOriginal.substringAfter(SUMMARY_MARK).removePrefix("\n").removePrefix(SUMMARY_HEAD).trim().ifEmpty { null }
            }
        } else body = content.trim()
        val name = fileName.substringAfterLast('/').substringBeforeLast('.')
        return ParsedNote(
            uuid = f["uuid"]?.takeIf { mine },
            kind = (if (mine) NoteKind.fromWire(f["kind"]) else null) ?: NoteKind.MEMO,
            title = title?.takeIf { it.isNotBlank() } ?: name.ifBlank { "Note" },
            body = body,
            summary = summary,
            summaryEdited = f["summary_edited"] == "true",
            summaryPrompt = f["summary_prompt"],
            sourceUrl = f["source_url"],
            sourceMeta = f["source_meta"],
            createdAt = f["created"]?.let(::parseTime),
            updatedAt = f["updated"]?.let(::parseTime),
            durationMs = f["duration_ms"]?.toLongOrNull(),
            audioPath = f["audio"],
            cuesPath = f["cues"],
        )
    }

    private fun parseTime(s: String): Long? = runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

    /** Front matter as key → value (JSON-quoted values unquoted), and the text after it. */
    fun frontMatter(text: String): Pair<Map<String, String>?, String> {
        if (!text.startsWith("---\n")) return null to text
        val end = text.indexOf("\n---", 4)
        if (end < 0) return null to text
        val block = text.substring(4, end)
        val after = text.substring(end + 4).removePrefix("\n")
        val map = LinkedHashMap<String, String>()
        for (line in block.lines()) {
            val i = line.indexOf(':')
            if (i <= 0 || line.startsWith(" ") || line.startsWith("#")) continue
            val k = line.substring(0, i).trim()
            var v = line.substring(i + 1).trim()
            if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) {
                v = runCatching { org.json.JSONArray("[$v]").getString(0) }.getOrDefault(v.substring(1, v.length - 1))
            } else if (v.startsWith("'") && v.endsWith("'") && v.length >= 2) v = v.substring(1, v.length - 1).replace("''", "'")
            map[k] = v
        }
        return map to after
    }

    /** A file name for a note: date, a short slug of the title, and the start of the uuid. */
    fun fileName(n: NoteEntity, zone: ZoneId): String {
        val date = OffsetDateTime.ofInstant(Instant.ofEpochMilli(n.createdAt), zone).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val slug = n.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40).trim('-').ifEmpty { "note" }
        return "$date-$slug-${n.uuid.take(8)}.md"
    }
}
