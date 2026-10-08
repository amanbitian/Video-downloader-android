package app.fetch.download

/**
 * Recognises several network files as qualities of one video: ".../clip_480p.mp4" and ".../clip_1080p.mp4" share
 * the stem ".../clip_{q}.mp4". Players that stream (MSE) fetch such files in byte ranges, often with the range in the query.
 */
object MediaGrouping {
    private val qualityToken = Regex("""(?<![A-Za-z0-9])(\d{3,4})p(?![A-Za-z0-9])|(?<![A-Za-z0-9])(\d{3,4})x(\d{3,4})(?![A-Za-z0-9])|(?<=[_-])(144|240|270|360|480|540|576|720|1080|1440|2160)(?=\.[A-Za-z0-9]+$)""", RegexOption.IGNORE_CASE)
    private val bitrateToken = Regex("""(?<![A-Za-z0-9])\d{2,6}k(?:bps)?(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val byteRangeParams =setOf("bytestart", "byteend", "range", "rn", "rbuf")
    private val closedRange = Regex("""^bytes=\d+-\d+$""")

    /** A player fetching a slice of a file (MSE) rather than the whole file. */
    fun isSliceRequest(url: String, rangeHeader: String?): Boolean =
        rangeHeader?.trim()?.let(closedRange::matches) == true || query(url).any { it.first.lowercase() in setOf("bytestart", "byteend") }

    /** Drops byte-range query parameters so the URL addresses the whole file. */
    fun stripByteRangeParams(url: String): String {
        val base = url.substringBefore('?')
        val kept = query(url).filterNot { (name, value) -> name.lowercase() in byteRangeParams && (value.isEmpty() || value.all { it.isDigit() || it == '-' }) }
        val fragment = url.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
        return if (kept.isEmpty()) base + fragment else base + "?" + kept.joinToString("&") { (n, v) -> if (v.isEmpty()) n else "$n=$v" } + fragment
    }

    /** Group key, or null when the file name carries no quality marker. */
    fun stem(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?')
        val fileStart = path.lastIndexOf('/') + 1
        // Only the last two path segments may carry the quality (".../720p/clip.mp4" or ".../clip_720p.mp4").
        val searchFrom = path.lastIndexOf('/', fileStart - 2).coerceAtLeast(0)
        val tail = path.substring(searchFrom)
        if (!qualityToken.containsMatchIn(tail)) return null
        return path.substring(0, searchFrom) + qualityToken.replace(tail, "{q}")
    }

    /**
     * Key shared by every quality of one video whose URLs differ only in quality and bitrate markers, wherever they sit
     * in the path: ".../240P_400K_123.mp4/master.m3u8" and ".../1080P_4000K_123.mp4/master.m3u8" are one family. The host
     * is left out (CDN shards differ per quality); null when the path names no quality, as then nothing proves kinship.
     */
    fun family(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
        if (!qualityToken.containsMatchIn(path)) return null
        return bitrateToken.replace(qualityToken.replace(path, "{q}"), "{b}")
    }

    /** Vertical resolution named in the URL, e.g. 720 for "clip_720p.mp4" or "1280x720/clip.mp4". */
    fun heightOf(url: String): Int? {
        val path = url.substringBefore('?')
        val match = qualityToken.findAll(path.substring((path.lastIndexOf('/', path.lastIndexOf('/') - 1)).coerceAtLeast(0))).lastOrNull() ?: return null
        return (match.groupValues[1].ifEmpty { null } ?: match.groupValues[3].ifEmpty { null } ?: match.groupValues[4].ifEmpty { null })?.toIntOrNull()
    }

    private fun query(url: String): List<Pair<String, String>> =
        url.substringBefore('#').substringAfter('?', "").split('&').filter(String::isNotEmpty).map { it.substringBefore('=') to it.substringAfter('=', "") }
}
