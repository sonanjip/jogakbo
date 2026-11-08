package io.github.taehunkim.jogakbo.notes

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A single share's streaming limit; never trusts a provider's reported length. */
class ShareCopyBudget(
    private val perFile: Long = FILE_LIMIT,
    private val total: Long = TOTAL_LIMIT,
) {
    private var copied = 0L

    fun copy(input: InputStream, output: OutputStream): Long {
        var size = 0L
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return size
                if (count == 0) continue
                if (count.toLong() > perFile - size || count.toLong() > total - copied) {
                    throw IOException("Shared file exceeds the import size limit")
                }
                output.write(buffer, 0, count)
                size += count
                copied += count
            }
        } catch (e: Exception) {
            copied -= size
            throw e
        }
    }

    companion object {
        const val ITEM_LIMIT = 10
        const val FILE_LIMIT = 100L * 1024 * 1024
        const val TOTAL_LIMIT = 200L * 1024 * 1024
    }
}
