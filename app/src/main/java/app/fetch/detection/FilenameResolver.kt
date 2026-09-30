package app.fetch.detection

import app.fetch.download.MediaFiles

/**
 * Names a detected video from the best human-readable source: the video's own metadata (JSON-LD name), then the page
 * (og:title / document title, already cleaned of site suffixes), then whatever the detector had. Representation and CDN
 * names ("m2-res_1160p.mp4", "193039199_mp4_h264") never become the title.
 */
object FilenameResolver {
    private val mediaFileName = Regex("""^[^\s/]+\.(mp4|m4v|webm|mkv|mov|m3u8|mpd|ts|m4s|m4a|mp3|ogg|ogv|flv|avi|aac)$""", RegexOption.IGNORE_CASE)
    private val machineToken = Regex("""^[A-Za-z0-9_\-.]*\d[A-Za-z0-9_\-.]*$""")

    fun title(structuredName: String?, pageTitle: String?, detectedName: String?, host: String?): String =
        listOf(structuredName, pageTitle, detectedName).firstOrNull { !it.isNullOrBlank() && !looksMachineMade(it) }?.trim()
            ?: host?.removePrefix("www.")?.takeIf { it.isNotBlank() }?.let { "Video from $it" }
            ?: "Video"

    /** "Amazing Trip" + "mp4" → "Amazing Trip.mp4", with characters Android storage rejects replaced. */
    fun fileName(title: String, extension: String?): String =
        MediaFiles.safeTitle(title) + (extension?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: "")

    fun looksMachineMade(text: String): Boolean {
        val t = text.trim()
        return mediaFileName.matches(t) || ('/' in t && t.none(Char::isWhitespace)) || (t.none(Char::isWhitespace) && machineToken.matches(t) && ('_' in t || '-' in t || t.length > 12))
    }
}
