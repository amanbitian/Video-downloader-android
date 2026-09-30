package app.fetch.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Thrown for streams we recognise but cannot save; the message is shown to the user as-is. */
class UnsupportedStreamException(message: String) : IllegalStateException(message)

/** A media playlist ready to fetch: optional fMP4 init segment, then segments in order. */
data class HlsMediaPlaylist(val initUrl: String?, val segments: List<String>, val durationSeconds: Double, val isFragmentedMp4: Boolean = initUrl != null)

/** Resolves non-DRM HLS master manifests into intentional, deduplicated quality choices. */
object HlsResolver {
    private val attributePattern = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    suspend fun resolve(candidate: MediaCandidate): ResolvedStream = withContext(Dispatchers.IO) {
        val body = fetchText(candidate.url, candidate.requestHeaders)
        val variants = parse(candidate.url, body)
        if (variants.isEmpty()) {
            return@withContext ResolvedStream(emptyList(), if (isEncrypted(body)) "This stream is encrypted or protected and can't be downloaded." else "No downloadable quality found.")
        }
        // Duration comes from one media playlist (all qualities share it); a small extra request buys honest size estimates.
        val first = if (isMaster(body)) runCatching { mediaPlaylist(variants.first().url, fetchText(variants.first().url, candidate.requestHeaders)) }.getOrNull()
        else runCatching { mediaPlaylist(candidate.url, body) }.getOrNull()
        val claims = buildSet {
            variants.forEach { add(it.url.substringBefore('?')); it.audioKey?.let { audio -> add(audio.substringBefore('?')) } }
            first?.let { playlist -> (playlist.segments + listOfNotNull(playlist.initUrl)).forEach { add(it.substringBefore('?')) } }
        }
        // Each quality usually lives in its own directory; everything there belongs to this stream. The master's own directory may hold unrelated files.
        val masterDirectory = candidate.url.substringBefore('?').substringBeforeLast('/') + "/"
        val prefixes = variants.flatMap { listOfNotNull(it.url, it.audioKey) }.map { it.substringBefore('?').substringBeforeLast('/') + "/" }
            .filter { it != masterDirectory && it.startsWith(masterDirectory) }.toSet()
        val duration = first?.durationSeconds?.takeIf { it > 0 }
        ResolvedStream(variants.map { it.withEstimate(duration) }, null, claims, prefixes, duration)
    }

    fun parse(manifestUrl: String, body: String): List<MediaVariant> {
        if (isEncrypted(body)) return emptyList()
        val lines = body.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val audioRenditions = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map(::attributes).filter { it["TYPE"] == "AUDIO" }
        val languages = audioRenditions.mapNotNull { it["LANGUAGE"] }.toSet()
        val variants = buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
                val uri = lines.drop(index + 1).firstOrNull { !it.startsWith('#') } ?: return@forEachIndexed
                val attributes = attributes(line)
                val resolution = attributes["RESOLUTION"]
                val bandwidth = attributes["BANDWIDTH"]?.toLongOrNull()
                val videoFamily = Codecs.video(attributes["CODECS"])
                val audioFamily = Codecs.audio(attributes["CODECS"])
                // Renditions with a URI are separate audio playlists; without one the audio is inside the video stream.
                val group = audioRenditions.filter { it["GROUP-ID"] == attributes["AUDIO"] && !it["URI"].isNullOrBlank() }
                val audio = AudioPolicy.choose(group.map { rendition ->
                    AudioOption(
                        key = URL(URL(manifestUrl), rendition["URI"]!!).toString(), codecFamily = audioFamily,
                        language = rendition["LANGUAGE"], bitrate = null, isDefault = rendition["DEFAULT"] == "YES",
                        label = rendition["NAME"], isAccessory = rendition["CHARACTERISTICS"].orEmpty().contains("describes"),
                    )
                }, videoFamily)
                // Masters often repeat a video with AC-3/E-AC-3 audio groups; those can't be merged into an MP4, so skip them.
                if (group.isNotEmpty() && audio == null) return@forEachIndexed
                val height = resolution?.substringAfter('x')?.toIntOrNull()
                val label = height?.let { "${it}p" } ?: attributes["NAME"] ?: "Stream"
                val codecs = listOfNotNull(videoFamily, audioFamily).joinToString(" + ").ifBlank { null }
                val detail = listOfNotNull(
                    resolution, codecs, bandwidth?.let { "%.1f Mbps".format(it / 1_000_000.0) },
                    audio?.let { (it.label ?: it.language)?.takeIf { languages.size > 1 }?.let { name -> "$name audio" } },
                ).joinToString(" · ")
                add(MediaVariant(URL(URL(manifestUrl), uri).toString(), label, detail, StreamType.HLS, audioKey = audio?.key, bitrate = bandwidth, height = height))
            }
        }
        if (variants.isEmpty() && body.contains("#EXTINF")) return listOf(MediaVariant(manifestUrl, "Source quality", "HLS stream", StreamType.HLS))
        // One entry per resolution: the most compatible codec, then the higher bitrate.
        return variants.distinctBy { it.url }
            .groupBy { it.height ?: it.label.hashCode() }
            .map { (_, same) -> same.sortedWith(compareBy<MediaVariant> { Codecs.videoPreference(videoFamilyOf(it)) }.thenByDescending { it.bitrate ?: 0 }).first() }
            .sortedWith(compareByDescending<MediaVariant> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 })
    }

    /** Parses a media playlist. Throws [UnsupportedStreamException] for layouts we can't fetch. */
    fun mediaPlaylist(playlistUrl: String, body: String): HlsMediaPlaylist {
        if (isEncrypted(body)) throw UnsupportedStreamException("This HLS stream is encrypted and cannot be downloaded.")
        if (body.contains("#EXT-X-STREAM-INF")) throw UnsupportedStreamException("This is a quality list, not a stream. Pick a quality from the download sheet.")
        val lines = body.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val maps = lines.filter { it.startsWith("#EXT-X-MAP:") }.map(::attributes)
        val initUrl = maps.firstOrNull()?.get("URI")?.let { URL(URL(playlistUrl), it).toString() }
        val segments = lines.filter { !it.startsWith('#') }.map { URL(URL(playlistUrl), it).toString() }
        if (segments.isEmpty()) throw UnsupportedStreamException("The HLS playlist has no downloadable segments.")
        val duration = lines.filter { it.startsWith("#EXTINF:") }.sumOf { it.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0 }
        val fragmented = initUrl != null
        if (body.contains("#EXT-X-BYTERANGE") || maps.any { it["BYTERANGE"] != null }) {
            // Byte ranges of one file (a common fMP4 layout): that file is the whole stream, so fetch it whole.
            val files = (segments + listOfNotNull(initUrl)).distinct()
            if (files.size != 1) throw UnsupportedStreamException("This HLS stream splits byte ranges across files and is not supported yet.")
            return HlsMediaPlaylist(null, files, duration, fragmented)
        }
        if (maps.mapNotNull { it["URI"] }.distinct().size > 1) {
            throw UnsupportedStreamException("This HLS stream switches initialization segments and is not supported yet.")
        }
        return HlsMediaPlaylist(initUrl, segments, duration, fragmented)
    }

    fun fetchText(url: String, headers: Map<String, String>): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            if (connection.responseCode !in 200..299) throw HttpStatusException(connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun MediaVariant.withEstimate(seconds: Double?): MediaVariant {
        val bytes = if (seconds != null && bitrate != null) (bitrate / 8.0 * seconds).toLong() else return this
        return copy(estimatedBytes = bytes, detail = listOf(detail, bytes.asEstimatedSize()).filter(String::isNotBlank).joinToString(" · "))
    }

    private fun videoFamilyOf(variant: MediaVariant): String? = when {
        "H.265" in variant.detail -> "H.265"
        "VP9" in variant.detail -> "VP9"
        else -> "H.264"
    }

    private fun isMaster(body: String) = body.contains("#EXT-X-STREAM-INF")

    private fun isEncrypted(body: String): Boolean = body.lineSequence().map(String::trim)
        .filter { it.startsWith("#EXT-X-KEY:") || it.startsWith("#EXT-X-SESSION-KEY:") }
        .any { attributes(it)["METHOD"]?.uppercase() != "NONE" }

    private fun attributes(line: String): Map<String, String> =
        attributePattern.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
}
