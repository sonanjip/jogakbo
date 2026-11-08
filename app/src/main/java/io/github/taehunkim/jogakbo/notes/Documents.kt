package io.github.taehunkim.jogakbo.notes

import io.github.taehunkim.jogakbo.data.db.NoteEntity
import org.json.JSONObject

/** What a document note remembers about its attached file, kept in `sourceMeta` (no schema column). */
object Documents {
    /** Name of the copy under files/docs. */
    const val KEY_FILE = "docFile"
    /** The file's name as the user knew it. */
    const val KEY_NAME = "docName"
    const val KEY_SIZE = "docSize"
    const val KEY_PAGES = "docPages"
    const val KEY_OCR_PAGES = "docOcrPages"
    const val KEY_TRUNCATED = "docTruncated"

    /** Extensions the extractor reads (old .doc/.ppt/.xls and hwp are not supported, as in Nunsori). */
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    val EXTENSIONS = setOf("txt", "md", "markdown", "text", "csv", "tsv", "log", "pdf", "docx", "pptx", "xlsx", "odt", "odp", "ods", "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /** MIME types for the picker and for shared files. */
    val MIME_TYPES = arrayOf(
        "image/*", "text/plain", "text/markdown", "text/csv", "text/tab-separated-values", "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.presentation", "application/vnd.oasis.opendocument.spreadsheet",
        // file managers often report .md and the like as plain binary: the extension decides then
        "application/octet-stream",
    )

    private val BY_MIME = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif",
        "image/bmp" to "bmp", "image/heic" to "heic", "image/heif" to "heif",
        "text/plain" to "txt", "text/markdown" to "md", "text/csv" to "csv", "text/tab-separated-values" to "tsv", "application/pdf" to "pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.oasis.opendocument.text" to "odt", "application/vnd.oasis.opendocument.presentation" to "odp",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
    )

    /** The extension to treat a file as: its own when known, else from the MIME type, else "bin" (rejected when read). */
    fun extensionOf(name: String, mime: String?): String {
        val own = name.substringAfterLast('.', "").lowercase()
        if (own in EXTENSIONS) return own
        return BY_MIME[mime?.lowercase()] ?: own.ifEmpty { "bin" }.take(8)
    }

    fun meta(note: NoteEntity): JSONObject = runCatching { JSONObject(note.sourceMeta ?: "{}") }.getOrDefault(JSONObject())

    fun fileName(note: NoteEntity): String? = meta(note).optString(KEY_FILE).takeIf { it.isNotEmpty() && it != "." && it != ".." && !it.contains('/') && !it.contains('\\') }
}
