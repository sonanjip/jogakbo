package io.github.taehunkim.jogakbo.notes

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShareCopyTest {
    @Test fun exactLimitsAreAllowedAndAggregateCountsAcrossFiles() {
        val budget = ShareCopyBudget(perFile = 4, total = 6)
        assertEquals(4L, budget.copy(ByteArrayInputStream(ByteArray(4)), ByteArrayOutputStream()))
        assertEquals(2L, budget.copy(ByteArrayInputStream(ByteArray(2)), ByteArrayOutputStream()))
        assertThrows(IOException::class.java) { budget.copy(ByteArrayInputStream(ByteArray(1)), ByteArrayOutputStream()) }
    }

    @Test fun failedCopyDoesNotConsumeBudgetForLaterFiles() {
        val budget = ShareCopyBudget(perFile = 9000, total = 12000)
        assertThrows(IOException::class.java) { budget.copy(ByteArrayInputStream(ByteArray(9001)), ByteArrayOutputStream()) }
        assertEquals(9000L, budget.copy(ByteArrayInputStream(ByteArray(9000)), ByteArrayOutputStream()))
    }

    @Test fun limitsUseBinaryMegabytes() {
        assertEquals(10, ShareCopyBudget.ITEM_LIMIT)
        assertEquals(100L * 1024 * 1024, ShareCopyBudget.FILE_LIMIT)
        assertEquals(200L * 1024 * 1024, ShareCopyBudget.TOTAL_LIMIT)
    }

    @Test fun overLimitStreamIsRejectedEvenWithoutReportedSize() {
        val budget = ShareCopyBudget(perFile = 3, total = 8)
        val out = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { budget.copy(ByteArrayInputStream(ByteArray(4)), out) }
        assertEquals(0, out.size())
    }
}
