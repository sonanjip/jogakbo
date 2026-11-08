package io.github.taehunkim.jogakbo.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.fetch.FetchException
import io.github.taehunkim.jogakbo.ml.Lease
import io.github.taehunkim.jogakbo.ml.OcrEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** The text of a document as Markdown with a `## Page N` (slide, sheet, section) heading per unit. */
class ExtractedDocument(val title: String?, val units: Int, val markdown: String, val truncated: Boolean, val ocrPages: Int)

/**
 * Text of an attached document: txt/md as is, PDF from its text layer (pages without a usable one are read by OCR, e.g.
 * scans), docx/pptx/xlsx and OpenDocument files from their XML. Adapted from Nunsori's extractors; a note keeps only
 * text, so no page images, thumbnails or image embeddings. Units become `##` headings so the heading-aware chunker gives
 * every chunk a title with its page.
 */
class DocumentExtractor(private val context: Context, private val ocr: suspend () -> Lease<OcrEngine>) {
    /** [progress] gets (units done, units total). Throws [FetchException] (with a message for the user) when nothing can be read. */
    suspend fun extract(file: File, ext: String, progress: (Int, Int) -> Unit = { _, _ -> }): ExtractedDocument = when (ext.lowercase()) {
        "pdf" -> pdf(file, progress)
        "docx", "pptx", "xlsx", "odt", "odp", "ods" -> office(file, ext, progress)
        "txt", "md", "markdown", "text", "csv", "tsv", "log" -> plain(file)
        else -> throw FetchException(context.getString(R.string.doc_unsupported))
    }

    private fun plain(file: File): ExtractedDocument {
        if (file.length() > MAX_TEXT_BYTES) throw FetchException(context.getString(R.string.doc_too_big))
        val text = TextDecoder.decode(file.readBytes())?.trim() ?: throw FetchException(context.getString(R.string.doc_unsupported))
        if (text.isEmpty()) throw FetchException(context.getString(R.string.doc_no_text))
        val capped = text.length > MAX_BODY_CHARS
        return ExtractedDocument(null, 1, if (capped) text.take(MAX_BODY_CHARS) else text, capped, 0)
    }

    private suspend fun office(file: File, ext: String, progress: (Int, Int) -> Unit): ExtractedDocument {
        val doc = try {
            ZipSource.fromFile(file).use { OfficeParsers.parse(ext, it) }
        } catch (e: java.util.zip.ZipException) { throw FetchException(context.getString(R.string.doc_unreadable)) }
            ?: throw FetchException(context.getString(R.string.doc_unsupported))
        if (doc.units.isEmpty()) throw FetchException(context.getString(R.string.doc_no_text))
        val sb = StringBuilder()
        var truncated = doc.units.size >= OfficeParsers.MAX_UNITS
        for ((i, u) in doc.units.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress(i, doc.units.size)
            sb.append("## ").append(heading(u, doc.style, i)).append("\n\n")
            sb.append(if (u.rows != null) table(u.rows) else u.text.trim().replace(Regex("(?m)^(#{1,6}) "), "##$1 ")).append("\n\n")
            if (sb.length > MAX_BODY_CHARS) { truncated = true; break }
        }
        return ExtractedDocument(doc.title, doc.units.size, sb.toString().trim().take(MAX_BODY_CHARS), truncated, 0)
    }

    private fun heading(u: OfficeUnit, style: TextCard.Style, i: Int): String = when {
        u.page != null && style == TextCard.Style.SLIDE -> "Slide ${u.page}" + (u.label?.let { ": ${it.take(80)}" } ?: "")
        u.page != null -> "Page ${u.page}"
        else -> u.label?.take(80) ?: "Section ${i + 1}"
    }

    /** A sheet as a Markdown table (the first row is the header). */
    private fun table(rows: List<List<String>>): String {
        val width = rows.maxOf { it.size }.coerceAtLeast(1)
        fun row(r: List<String>) = "| " + (0 until width).joinToString(" | ") { c -> r.getOrElse(c) { "" }.replace("|", "\\|").replace("\n", " ") } + " |"
        val shown = rows.take(MAX_ROWS)
        return buildString {
            append(row(shown.first())).append('\n').append("|").append(" --- |".repeat(width))
            for (r in shown.drop(1)) append('\n').append(row(r))
            if (rows.size > shown.size) append("\n\n*(${rows.size - shown.size} more rows not shown)*")
        }
    }

    private suspend fun pdf(file: File, progress: (Int, Int) -> Unit): ExtractedDocument {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(pfd) } catch (e: SecurityException) {
            pfd.close(); throw FetchException(context.getString(R.string.doc_password))
        } catch (e: IOException) {
            pfd.close(); throw FetchException(context.getString(R.string.doc_unreadable))
        }
        var text: PDDocument? = null
        var lease: Lease<OcrEngine>? = null
        try {
            val total = minOf(renderer.pageCount, MAX_PAGES)
            if (total == 0) throw FetchException(context.getString(R.string.doc_no_text))
            text = openText(file)
            val title = text?.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() }
            val stripper = PDFTextStripper()
            val sb = StringBuilder()
            var ocrPages = 0
            var truncated = renderer.pageCount > MAX_PAGES
            for (i in 0 until total) {
                currentCoroutineContext().ensureActive()
                progress(i, total)
                var t = layerText(text, stripper, i)
                if (t == null) {
                    val bmp = render(renderer, i)
                    try {
                        val engine = lease ?: ocr().also { lease = it }
                        t = engine.value.recognize(bmp).trim()
                        ocrPages++
                    } finally { bmp.recycle() }
                }
                if (t.isNotBlank()) sb.append("## Page ").append(i + 1).append("\n\n").append(t).append("\n\n")
                if (sb.length > MAX_BODY_CHARS) { truncated = true; break }
            }
            if (sb.isBlank()) throw FetchException(context.getString(R.string.doc_no_text))
            return ExtractedDocument(title, renderer.pageCount, sb.toString().trim().take(MAX_BODY_CHARS), truncated, ocrPages)
        } finally {
            renderer.close(); runCatching { text?.close() }; lease?.close()
        }
    }

    private fun openText(file: File): PDDocument? = try {
        if (!initialised) { PDFBoxResourceLoader.init(context); initialised = true }
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
    } catch (e: Exception) { null }

    /** The page's text layer, or null when it has none worth keeping (the page then goes to OCR). */
    private fun layerText(doc: PDDocument?, stripper: PDFTextStripper, index: Int): String? {
        if (doc == null) return null
        val t = try { stripper.startPage = index + 1; stripper.endPage = index + 1; stripper.getText(doc) } catch (e: Exception) { return null }
        return if (PdfText.usable(t)) t.trim() else null
    }

    private fun render(renderer: PdfRenderer, index: Int): Bitmap = renderer.openPage(index).use { page ->
        val w = RENDER_WIDTH
        val h = (w.toFloat() * page.height / page.width).roundToInt().coerceIn(1, MAX_HEIGHT)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    companion object {
        const val MAX_PAGES = 500
        const val RENDER_WIDTH = 1200
        const val MAX_HEIGHT = 2400
        /** Plain text files are read up to this size (as in Nunsori). */
        const val MAX_TEXT_BYTES = 25L * 1024 * 1024
        /** The stored original stops here (the list and the detail screen load whole bodies). */
        // Room must read this row (UTF-16 body plus summary) within Android’s CursorWindow.
        const val MAX_BODY_CHARS = 400_000
        const val MAX_ROWS = 2000
        @Volatile private var initialised = false
    }
}
