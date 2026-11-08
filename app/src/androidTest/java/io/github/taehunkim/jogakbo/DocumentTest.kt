package io.github.taehunkim.jogakbo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.jogakbo.docs.DocumentExtractor
import io.github.taehunkim.jogakbo.fetch.FetchException
import io.github.taehunkim.jogakbo.ml.Lease
import io.github.taehunkim.jogakbo.ml.OcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reading attached documents: txt, a PDF with a text layer, a scanned PDF (OCR), docx. */
@RunWith(AndroidJUnit4::class)
class DocumentTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private var ocrCalls = 0
    private val ocr = object : OcrEngine {
        override suspend fun recognize(bitmap: Bitmap): String { ocrCalls++; return "Scanned page about the harbour inspection" }
    }
    private val extractor get() = DocumentExtractor(ctx) { Lease(ocr) { } }

    @Before fun setUp() { dir = File(ctx.cacheDir, "doc-test").apply { deleteRecursively(); mkdirs() } }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun pdf(name: String, pages: List<String?>): File {
        val doc = PdfDocument()
        pages.forEachIndexed { i, text ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            val paint = Paint().apply { textSize = 14f; isAntiAlias = true }
            // a page without text (null) is a picture: a scan has no text layer
            if (text != null) text.lines().forEachIndexed { k, line -> page.canvas.drawText(line, 40f, 80f + k * 22f, paint) }
            else page.canvas.drawRect(100f, 100f, 400f, 300f, Paint().apply { style = Paint.Style.FILL })
            doc.finishPage(page)
        }
        return File(dir, name).also { f -> f.outputStream().use { doc.writeTo(it) }; doc.close() }
    }

    @Test fun aLargeKoreanDocumentStillFitsARoomRow() = runBlocking {
        val f = File(dir, "large.txt").apply { writeText("한글 자료 내용입니다. ".repeat(70_000)) }
        val r = extractor.extract(f, "txt")
        assertTrue(r.truncated)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, io.github.taehunkim.jogakbo.data.db.AppDatabase::class.java).build()
        try {
            val n = io.github.taehunkim.jogakbo.data.db.NoteEntity(uuid = "large-document", kind = io.github.taehunkim.jogakbo.data.db.NoteKind.DOCUMENT,
                title = "Large", body = r.markdown, createdAt = 1, updatedAt = 1)
            val id = db.noteDao().insert(n)
            assertEquals(r.markdown.length, db.noteDao().get(id)!!.body.length)
            assertEquals(1, db.noteDao().all().size)
        } finally { db.close() }
    }

    @Test fun plainTextKeepsItsText() = runBlocking {
        val f = File(dir, "notes.md").apply { writeText("# Plan\n\nBuy tickets on Friday. 회의록 정리.") }
        val r = extractor.extract(f, "md")
        assertTrue(r.markdown.contains("Buy tickets") && r.markdown.contains("회의록"))
        assertFalse(r.truncated)
    }

    @Test fun pdfPagesBecomeSectionsFromTheTextLayer() = runBlocking {
        val f = pdf("report.pdf", listOf("The quarterly revenue grew twelve percent in the third quarter of this year.", "The new office address is Harbour Road number five in the old town."))
        val seen = ArrayList<Pair<Int, Int>>()
        val r = extractor.extract(f, "pdf") { d, t -> seen += d to t }
        assertEquals(2, r.units)
        assertEquals(0, r.ocrPages)
        assertEquals(0, ocrCalls)
        assertTrue(r.markdown.contains("## Page 1") && r.markdown.contains("## Page 2"))
        assertTrue(r.markdown.indexOf("revenue") < r.markdown.indexOf("## Page 2"))
        assertEquals(listOf(0 to 2, 1 to 2), seen)
    }

    @Test fun aScannedPageGoesToOcr() = runBlocking {
        val f = pdf("scan.pdf", listOf("A page that has real text about budgets and plans for the year.", null))
        val r = extractor.extract(f, "pdf")
        assertEquals(1, r.ocrPages)
        assertEquals(1, ocrCalls)
        assertTrue(r.markdown.contains("## Page 2") && r.markdown.contains("harbour inspection"))
    }

    @Test fun docxBecomesPageSections() = runBlocking {
        val f = File(dir, "memo.docx")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(
                ("""<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""" +
                    """<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Project memo</w:t></w:r></w:p>""" +
                    """<w:p><w:r><w:t>The launch moves to March because the vendor is late.</w:t></w:r></w:p></w:body></w:document>""").toByteArray(),
            )
            z.closeEntry()
        }
        val r = extractor.extract(f, "docx")
        assertTrue(r.markdown.contains("launch moves to March"))
        assertTrue(r.markdown.contains("### Project memo"))
        assertTrue(r.markdown.startsWith("## "))
    }

    @Test fun unknownFilesAndEmptyDocumentsAreRefusedWithAMessage() = runBlocking {
        for ((name, ext) in listOf("a.doc" to "doc", "b.hwp" to "hwp")) {
            try { extractor.extract(File(dir, name).apply { writeText("x") }, ext); fail("$ext accepted") } catch (e: FetchException) {
                assertEquals(ctx.getString(R.string.doc_unsupported), e.message)
            }
        }
        try { extractor.extract(File(dir, "empty.txt").apply { writeText("  ") }, "txt"); fail("empty accepted") } catch (e: FetchException) {
            assertEquals(ctx.getString(R.string.doc_no_text), e.message)
        }
    }
}
