package app.fetch.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Resolves non-DRM HLS master manifests into intentional, deduplicated quality choices. */
object HlsResolver {
    suspend fun resolve(candidate: MediaCandidate): List<MediaVariant> = withContext(Dispatchers.IO) {
        val connection = open(candidate.url, candidate.requestHeaders)
        val body = try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
        parse(candidate.url, body)
    }

    fun parse(manifestUrl: String, body: String): List<MediaVariant> {
        if (body.contains("#EXT-X-KEY")) return emptyList()
        val lines = body.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val variants = buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
                val uri = lines.drop(index + 1).firstOrNull { !it.startsWith('#') } ?: return@forEachIndexed
                val attributes = line.substringAfter(':').split(',').associate { item ->
                    val pair = item.split('=', limit = 2)
                    pair.first() to pair.getOrElse(1) { "" }.trim('"')
                }
                val resolution = attributes["RESOLUTION"]
                val bandwidth = attributes["BANDWIDTH"]?.toLongOrNull()
                val codec = attributes["CODECS"]?.substringBefore(',')?.replace("avc1", "H.264")?.replace("hvc1", "H.265")
                val label = resolution?.substringAfter('x')?.let { "${it}p" } ?: attributes["NAME"] ?: "Stream"
                val detail = listOfNotNull(resolution, codec, bandwidth?.let { "${it / 1_000_000.0f} Mbps" }).joinToString(" · ")
                add(MediaVariant(URL(URL(manifestUrl), uri).toString(), label, detail, StreamType.HLS))
            }
        }
        return if (variants.isEmpty() && body.contains("#EXTINF")) listOf(MediaVariant(manifestUrl, "Source quality", "HLS stream", StreamType.HLS))
        else variants.distinctBy { it.url }.sortedByDescending { it.label.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
}
