package io.github.taehunkim.jogakbo.search

import io.github.taehunkim.jogakbo.ml.Quant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WithinNoteTest {
    private fun p(ordinal: Int, text: String, vec: FloatArray? = null, summary: Boolean = false) =
        Passage(ordinal, "Section $ordinal", text, vec?.let { Quant.encode(it) }, summary)

    private val passages = listOf(
        p(0, "A short summary of the report", floatArrayOf(1f, 0f, 0f), summary = true),
        p(1, "Revenue grew twelve percent in the third quarter", floatArrayOf(0f, 1f, 0f)),
        p(2, "The office moved to a new address in May", floatArrayOf(0f, 0f, 1f)),
    )

    @Test fun meaningRanksTheClosestPassageFirst() {
        val hits = WithinNote.rank(passages, floatArrayOf(0.1f, 0.2f, 0.9f), "where is the office", 2)
        assertEquals(listOf(2, 1), hits.map { it.passage.ordinal })
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test fun summaryPassagesAreMarked() {
        val hits = WithinNote.rank(passages, floatArrayOf(1f, 0f, 0f), "x", 1)
        assertTrue(hits.single().passage.summary)
    }

    @Test fun wordsDecideWithoutVectors() {
        val fresh = passages.map { Passage(it.ordinal, it.title, it.text, null, it.summary) }
        assertEquals(listOf(1), WithinNote.rank(fresh, null, "revenue quarter", 3).map { it.passage.ordinal })
        assertTrue(WithinNote.rank(fresh, null, "zebra", 3).isEmpty())
    }

    @Test fun aVectorOfAnotherSizeFallsBackToWords() {
        assertEquals(listOf(2), WithinNote.rank(passages, floatArrayOf(1f, 0f), "office address", 3).map { it.passage.ordinal })
    }
}
