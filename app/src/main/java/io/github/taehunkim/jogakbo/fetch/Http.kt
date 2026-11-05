package io.github.taehunkim.jogakbo.fetch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/** One HTTP answer, already decoded to text. */
data class HttpResponse(val url: String, val code: Int, val contentType: String?, val body: String)

/** The network as the fetchers see it (tests pass saved pages). */
interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

class FetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** [HttpURLConnection] with redirects (also http → https), a size cap and charset detection. */
class UrlConnectionHttp(private val maxBytes: Int = MAX_BYTES, private val timeoutMs: Int = 20_000) : HttpClient {
    override suspend fun get(url: String, headers: Map<String, String>) = request(url, "GET", null, headers)
    override suspend fun postJson(url: String, json: String, headers: Map<String, String>) =
        request(url, "POST", json.toByteArray(), headers + ("Content-Type" to "application/json"))

    private suspend fun request(start: String, method: String, body: ByteArray?, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        var url = start
        repeat(MAX_REDIRECTS) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.requestMethod = method
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.8,ko;q=0.6")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw FetchException("redirect without location")
                    url = URL(URL(url), loc).toString()
                    return@repeat
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val bytes = stream?.use { readCapped(it) } ?: ByteArray(0)
                val type = c.contentType
                return@withContext HttpResponse(url, code, type, decode(bytes, type))
            } finally { c.disconnect() }
        }
        throw FetchException("too many redirects")
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > maxBytes) break // a huge page: the start is enough
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        const val MAX_REDIRECTS = 8
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        /** Charset from the header, else from a meta tag near the start, else UTF-8. */
        fun decode(bytes: ByteArray, contentType: String?): String {
            val fromHeader = contentType?.let { Regex("charset=([\\w.-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
            val fromMeta = Regex("<meta[^>]+charset=[\"']?([\\w.-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
            val cs = listOfNotNull(fromHeader, fromMeta).firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            return String(bytes, cs)
        }
    }
}
