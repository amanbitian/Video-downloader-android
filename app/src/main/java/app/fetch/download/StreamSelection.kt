package app.fetch.download

import java.util.Locale

/** What a resolved manifest produced: user-facing qualities plus the raw URLs it accounts for. */
data class ResolvedStream(
    val variants: List<MediaVariant>,
    val note: String? = null,
    /** URLs (without query) of representations/segments this manifest owns; sniffed copies of them are not separate videos. */
    val claimedPaths: Set<String> = emptySet(),
    /** Directories holding only this stream's files (e.g. "./v720/"), for layouts whose file names aren't all known up front. */
    val claimedPrefixes: Set<String> = emptySet(),
    val durationSeconds: Double? = null,
    /** The "manifest" was a VAST/VMAP ad document. */
    val isAd: Boolean = false,
)

/** Codec families and which ones Android's MediaMuxer can put in one file together, without re-encoding. */
object Codecs {
    enum class Container(val mime: String) { MP4("video/mp4"), WEBM("video/webm") }

    private val videoFamilies = mapOf("avc1" to "H.264", "avc3" to "H.264", "hvc1" to "H.265", "hev1" to "H.265", "vp09" to "VP9", "vp9" to "VP9", "vp8" to "VP8", "av01" to "AV1")
    private val audioFamilies = mapOf("mp4a" to "AAC", "opus" to "Opus", "vorbis" to "Vorbis", "ac-3" to "AC-3", "ec-3" to "E-AC-3", "flac" to "FLAC")

    fun video(codecs: String?): String? = split(codecs).firstNotNullOfOrNull { videoFamilies[it.substringBefore('.').lowercase()] }
    fun audio(codecs: String?): String? = split(codecs).firstNotNullOfOrNull { audioFamilies[it.substringBefore('.').lowercase()] }

    /** The container both codecs fit in, or null if they can't be combined. Unknown (null) codecs are assumed to fit. */
    fun container(videoFamily: String?, audioFamily: String?): Container? = when {
        videoFamily in setOf(null, "H.264", "H.265") && audioFamily in setOf(null, "AAC") -> Container.MP4
        videoFamily in setOf(null, "VP8", "VP9") && audioFamily in setOf(null, "Opus", "Vorbis") -> Container.WEBM
        else -> null
    }

    /** Lower is preferred when two representations offer the same resolution: the most widely playable wins. */
    fun videoPreference(family: String?): Int = when (family) {
        "H.264", null -> 0
        "H.265" -> 1
        "VP9" -> 2
        "VP8" -> 3
        else -> 9
    }

    private fun split(codecs: String?) = codecs.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
}

data class AudioOption(
    val key: String,
    val codecFamily: String?,
    val language: String?,
    val bitrate: Long?,
    val isDefault: Boolean,
    val label: String?,
    /** Commentary / audio description tracks are never picked automatically when a main track exists. */
    val isAccessory: Boolean = false,
)

/** Picks the audio that goes with a video: combinable codec, main track, default or device language, then best bitrate. */
object AudioPolicy {
    fun choose(options: List<AudioOption>, videoFamily: String?, deviceLanguage: String = Locale.getDefault().language): AudioOption? {
        val compatible = options.filter { Codecs.container(videoFamily, it.codecFamily) != null }
        val main = compatible.filterNot { it.isAccessory }.ifEmpty { compatible }
        if (main.isEmpty()) return null
        val byLanguage = main.filter { it.isDefault }.ifEmpty { main.filter { it.language.matches(deviceLanguage) } }.ifEmpty { main }
        return byLanguage.maxByOrNull { it.bitrate ?: 0L }
    }

    private fun String?.matches(language: String) = this != null && substringBefore('-').equals(language, ignoreCase = true)
}

fun Long.asEstimatedSize(): String = "~${asReadableBytes()}"
