package app.fetch.download

import java.net.HttpURLConnection
import java.net.URL

/** Asks the server what a URL is (type and size) without downloading it. */
object MediaProbe {
    data class Info(val mimeType: String?, val sizeBytes: Long?)

    fun probe(url: String, headers: Map<String, String>): Info? {
        // Some CDNs answer HEAD with a redirect to their homepage; only trust HEAD when it describes media.
        val head = head(url, headers)
        if (head != null && looksLikeMedia(head.mimeType)) return head
        return runCatching { ranged(url, headers) }.getOrNull() ?: head
    }

    private fun looksLikeMedia(mime: String?): Boolean {
        val m = mime?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return m.startsWith("video/") || m.startsWith("audio/") || m == "application/octet-stream" || "mpegurl" in m || "dash+xml" in m || m == "binary/octet-stream"
    }

    private fun ranged(url: String, headers: Map<String, String>): Info? {
        // Many CDNs reject HEAD; a one-byte ranged GET yields the same headers.
        val connection = open(url, headers, "GET").apply { setRequestProperty("Range", "bytes=0-0") }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            val size = MediaFiles.contentRangeTotal(connection.getHeaderField("Content-Range"))
                ?: connection.contentLengthLong.takeIf { code == 200 && it > 0 }
            Info(connection.contentType, size)
        } finally { connection.disconnect() }
    }

    private fun head(url: String, headers: Map<String, String>): Info? {
        val connection = open(url, headers, "HEAD")
        return try {
            if (connection.responseCode !in 200..299 || connection.contentType == null) null
            else Info(connection.contentType, connection.contentLengthLong.takeIf { it > 0 })
        } catch (_: Exception) { null } finally { connection.disconnect() }
    }

    private fun open(url: String, headers: Map<String, String>, method: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
}
