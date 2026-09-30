package app.fetch.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Thrown for streams we recognise but cannot save; the message is shown to the user as-is. */
class UnsupportedStreamException(message: String) : IllegalStateException(message)

/** Resolves non-DRM HLS master manifests into intentional, deduplicated quality choices. */
object HlsResolver {
    private val attributePattern = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    suspend fun resolve(candidate: MediaCandidate): List<MediaVariant> = withContext(Dispatchers.IO) {
        val connection = open(candidate.url, candidate.requestHeaders)
        val body = try {
            if (connection.responseCode !in 200..299) throw IOException("Server returned HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
        parse(candidate.url, body)
    }

    fun parse(manifestUrl: String, body: String): List<MediaVariant> {
        if (isEncrypted(body)) return emptyList()
        val lines = body.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        // Audio renditions with their own URI mean the video variants carry no sound track.
        val separateAudioGroups = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map(::attributes)
            .filter { it["TYPE"] == "AUDIO" && !it["URI"].isNullOrBlank() }.mapNotNull { it["GROUP-ID"] }.toSet()
        val variants = buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
                val uri = lines.drop(index + 1).firstOrNull { !it.startsWith('#') } ?: return@forEachIndexed
                val attributes = attributes(line)
                val resolution = attributes["RESOLUTION"]
                val bandwidth = attributes["BANDWIDTH"]?.toLongOrNull()
                val codec = attributes["CODECS"]?.substringBefore(',')?.let {
                    when {
                        it.startsWith("avc1") -> "H.264"
                        it.startsWith("hvc1") || it.startsWith("hev1") -> "H.265"
                        else -> it
                    }
                }
                val noAudio = attributes["AUDIO"]?.let { it in separateAudioGroups } == true
                val label = resolution?.substringAfter('x')?.let { "${it}p" } ?: attributes["NAME"] ?: "Stream"
                val detail = listOfNotNull(
                    resolution, codec, bandwidth?.let { "%.1f Mbps".format(it / 1_000_000.0) }, "no audio".takeIf { noAudio }
                ).joinToString(" · ")
                add(MediaVariant(URL(URL(manifestUrl), uri).toString(), label, detail, StreamType.HLS))
            }
        }
        return if (variants.isEmpty() && body.contains("#EXTINF")) listOf(MediaVariant(manifestUrl, "Source quality", "HLS stream", StreamType.HLS))
        else variants.distinctBy { it.url }.sortedByDescending { it.label.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }

    /** Absolute segment URLs of a media playlist, in order. Throws [UnsupportedStreamException] for layouts we can't concatenate. */
    fun mediaSegments(playlistUrl: String, body: String): List<String> {
        if (isEncrypted(body)) throw UnsupportedStreamException("This HLS stream is encrypted and cannot be downloaded.")
        if (body.contains("#EXT-X-MAP")) throw UnsupportedStreamException("This fMP4 HLS stream requires remuxing and is not supported yet.")
        if (body.contains("#EXT-X-BYTERANGE")) throw UnsupportedStreamException("Byte-range HLS streams are not supported yet.")
        if (body.contains("#EXT-X-STREAM-INF")) throw UnsupportedStreamException("This is a quality list, not a stream. Pick a quality from the download sheet.")
        val segments = body.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }
            .map { URL(URL(playlistUrl), it).toString() }.toList()
        if (segments.isEmpty()) throw UnsupportedStreamException("The HLS playlist has no downloadable segments.")
        return segments
    }

    private fun isEncrypted(body: String): Boolean = body.lineSequence().map(String::trim)
        .filter { it.startsWith("#EXT-X-KEY:") || it.startsWith("#EXT-X-SESSION-KEY:") }
        .any { attributes(it)["METHOD"]?.uppercase() != "NONE" }

    private fun attributes(line: String): Map<String, String> =
        attributePattern.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
}
