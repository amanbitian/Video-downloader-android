package app.fetch.detection

import org.json.JSONArray
import org.json.JSONObject

/** What the page itself says about its media, captured by one scoped DOM scan (no JavaScript bridge). */
data class PageMediaSnapshot(
    val pageUrl: String,
    val documentTitle: String?,
    val ogTitle: String?,
    val siteName: String?,
    val ogImage: String?,
    /** og:video / og:video:url / og:video:secure_url / twitter:player:stream. */
    val ogVideos: List<String>,
    val structuredVideos: List<StructuredVideo>,
    val elements: List<DomMedia>,
    val viewportWidth: Double,
    val viewportHeight: Double,
    val pageWidth: Double,
) {
    data class StructuredVideo(val name: String?, val contentUrl: String?, val embedUrl: String?, val thumbnailUrl: String?, val durationSeconds: Double?)

    data class DomMedia(
        val key: String,
        val isAudio: Boolean,
        /** Fetchable http(s) sources: currentSrc, src and <source> children. */
        val sources: List<String>,
        /** Backed by MediaSource (blob:) — the real files arrive as network requests. */
        val isBlob: Boolean,
        val poster: String?,
        val x: Double, val y: Double, val width: Double, val height: Double,
        val visible: Boolean,
        val durationSeconds: Double?,
        val muted: Boolean, val autoplay: Boolean, val loop: Boolean,
        /** Words from the element and its ancestors' tag, id, class, role and aria-label, nearest first. */
        val contextWords: List<String>,
    ) {
        val area get() = if (visible) width * height else 0.0
    }

    companion object {
        private val camelBoundary = Regex("([a-z])([A-Z])")
        private val nonWord = Regex("[^A-Za-z0-9]+")

        fun parse(json: String): PageMediaSnapshot? = runCatching {
            val root = JSONObject(json)
            PageMediaSnapshot(
                pageUrl = root.optString("url"),
                documentTitle = root.optString("title").ifBlank { null },
                ogTitle = root.optString("ogTitle").ifBlank { null },
                siteName = root.optString("site").ifBlank { null },
                ogImage = root.optString("image").httpOrNull(),
                ogVideos = root.optJSONArray("ogVideos").strings().mapNotNull { it.httpOrNull() }.distinct(),
                structuredVideos = root.optJSONArray("ld").objects().map {
                    StructuredVideo(
                        name = it.optString("name").ifBlank { null }, contentUrl = it.optString("contentUrl").httpOrNull(),
                        embedUrl = it.optString("embedUrl").httpOrNull(), thumbnailUrl = it.optString("thumbnailUrl").httpOrNull(),
                        durationSeconds = parseIsoDuration(it.optString("duration")),
                    )
                },
                elements = root.optJSONArray("videos").objects().map { v ->
                    val sources = v.optJSONArray("sources").strings()
                    DomMedia(
                        key = v.optString("key"), isAudio = v.optString("tag") == "audio",
                        sources = sources.mapNotNull { it.httpOrNull() }.distinct(), isBlob = sources.any { it.startsWith("blob:") },
                        poster = v.optString("poster").httpOrNull(),
                        x = v.optDouble("x", 0.0), y = v.optDouble("y", 0.0), width = v.optDouble("w", 0.0), height = v.optDouble("h", 0.0),
                        visible = v.optBoolean("visible"), durationSeconds = v.optDouble("duration", -1.0).takeIf { it > 0 && it.isFinite() },
                        muted = v.optBoolean("muted"), autoplay = v.optBoolean("autoplay"), loop = v.optBoolean("loop"),
                        contextWords = v.optJSONArray("context").strings().flatMap(::words),
                    )
                },
                viewportWidth = root.optDouble("vw", 0.0), viewportHeight = root.optDouble("vh", 0.0), pageWidth = root.optDouble("pw", 0.0),
            )
        }.getOrNull()

        /** "relatedVideos ad-slot" → [related, videos, ad, slot]. */
        fun words(text: String): List<String> =
            camelBoundary.replace(text, "$1 $2").split(nonWord).filter(String::isNotEmpty).map(String::lowercase)

        fun parseIsoDuration(value: String?): Double? {
            val match = Regex("""^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""").matchEntire(value?.trim().orEmpty()) ?: return null
            val (d, h, m, s) = match.destructured
            return ((d.toDoubleOrNull() ?: 0.0) * 86_400 + (h.toDoubleOrNull() ?: 0.0) * 3_600 + (m.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0)).takeIf { it > 0 }
        }

        private fun String.httpOrNull() = takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }
        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
