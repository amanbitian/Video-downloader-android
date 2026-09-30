package app.fetch.detection

import app.fetch.download.MediaCandidate
import app.fetch.download.StreamType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where a candidate was seen. Several can apply; page-declared signals are the strongest evidence of the main video. */
enum class MediaSignal { NETWORK, DOM_ELEMENT, OG_VIDEO, STRUCTURED_DATA }

/**
 * Answers "which video is this page about?" rather than "what media did the page request?". Scores every candidate from
 * page metadata (og:video, JSON-LD), the player's size and position, its surroundings (related rails, sidebars, ad slots)
 * and the stream itself, then keeps only confident winners. Conservative by design: one sure video beats a list of maybes.
 */
object PrimaryMediaResolver {
    /** Candidates scoring below this are never shown, even as the only one (hidden players, preview loops, related rails). */
    const val REJECT_BELOW = -30
    /** A second video is shown only if it is independently strong and close to the best one. */
    private const val SECOND_MIN = 60

    data class Scored(val candidate: MediaCandidate, val score: Int, val element: PageMediaSnapshot.DomMedia?)

    fun select(candidates: List<MediaCandidate>, snapshot: PageMediaSnapshot?, pageTitle: String?, host: String?): List<MediaCandidate> {
        val (explicit, sniffed) = candidates.partition { it.explicit }
        val scored = sniffed.filterNot { isAd(it, snapshot) }.map { score(it, snapshot, sniffed) }.filter { it.score >= REJECT_BELOW }
        val best = scored.maxOfOrNull { it.score }
        val primary = if (best == null) emptyList() else scored
            .filter { it.score == best || (it.score >= SECOND_MIN && it.score * 4 >= best * 3) }
            .sortedByDescending { it.score }
        return explicit + primary.mapIndexed { index, it -> enrich(it, snapshot, pageTitle, host, isTop = index == 0) }
    }

    /**
     * Whether an on-page player could be the page's video at all. Hidden, tiny, related-rail and ad-slot players are
     * not even turned into candidates — that keeps preview grids from flooding detection with probes.
     */
    fun isPlausiblePlayer(element: PageMediaSnapshot.DomMedia): Boolean =
        element.visible && (element.isAudio || (element.width >= 200 && element.height >= 120)) &&
            !AdMediaClassifier.isAdContext(element.contextWords) && !AdMediaClassifier.isRelatedContext(element.contextWords) &&
            !(element.muted && element.loop && element.autoplay && (element.durationSeconds ?: 0.0) < 30)

    fun isAd(candidate: MediaCandidate, snapshot: PageMediaSnapshot?): Boolean =
        candidate.isAd || AdMediaClassifier.isAdUrl(candidate.url) ||
            elementFor(candidate, snapshot, emptyList())?.let { AdMediaClassifier.isAdContext(it.contextWords) } == true

    fun score(candidate: MediaCandidate, snapshot: PageMediaSnapshot?, all: List<MediaCandidate>): Scored {
        var score = 0
        val paths = pathsOf(candidate)
        if (MediaSignal.OG_VIDEO in candidate.signals || snapshot?.ogVideos?.any { path(it) in paths } == true) score += 100
        if (MediaSignal.STRUCTURED_DATA in candidate.signals || snapshot?.structuredVideos?.any { it.contentUrl?.let(::path) in paths } == true) score += 100

        val element = elementFor(candidate, snapshot, all)
        if (element == null || snapshot == null) {
            // Seen only on the network (e.g. inside a cross-origin player iframe): plausible, not proven.
            score += 20
        } else {
            val videos = snapshot.elements.filter { it.visible && !it.isAudio }
            val largest = videos.maxOfOrNull { it.area } ?: 0.0
            when {
                !element.visible -> score -= 100
                element.isAudio -> score += 30
                else -> {
                    score += if (largest > 0 && element.area >= largest * 0.95) 60 else if (largest > 0) (40 * element.area / largest).toInt() else 0
                    val pageWidth = snapshot.pageWidth.takeIf { it > 0 } ?: snapshot.viewportWidth
                    val horizontal = if (pageWidth > 0) 1 - min(1.0, abs(element.x + element.width / 2 - pageWidth / 2) / (pageWidth / 2)) else 0.5
                    val vertical = if (snapshot.viewportHeight > 0) 1 - min(1.0, max(0.0, element.y - snapshot.viewportHeight * 0.5) / (snapshot.viewportHeight * 2)) else 0.5
                    score += (50 * horizontal * vertical).toInt()
                    if (element.width < 200 || element.height < 120) score -= 50
                }
            }
            if (AdMediaClassifier.isRelatedContext(element.contextWords)) score -= 70
            if (AdMediaClassifier.isMainContext(element.contextWords)) score += 30
            if (element.poster != null && element.poster == snapshot.ogImage) score += 30
            // Silent looping autoplay clips are hover previews, not the content.
            if (element.muted && element.loop && element.autoplay && (element.durationSeconds ?: 0.0) < 30) score -= 40
        }

        if (candidate.variants.size >= 2) score += 25
        val bytes = candidate.sizeBytes ?: candidate.variants.mapNotNull { it.estimatedBytes }.maxOrNull()
        if (bytes != null && bytes < 300 * 1024) score -= 40
        if (bytes != null && bytes > 5L * 1024 * 1024) score += 10
        val duration = candidate.durationSeconds ?: element?.durationSeconds
        if (duration != null && duration < 10) score -= 20
        return Scored(candidate, score, element)
    }

    /** The on-page player showing this candidate: by URL, or — for MSE players (blob:) — the stream whose length matches. */
    fun elementFor(candidate: MediaCandidate, snapshot: PageMediaSnapshot?, all: List<MediaCandidate>): PageMediaSnapshot.DomMedia? {
        snapshot ?: return null
        candidate.elementKey?.let { key -> snapshot.elements.firstOrNull { it.key == key }?.let { return it } }
        val paths = pathsOf(candidate)
        snapshot.elements.firstOrNull { element -> element.sources.any { path(it) in paths } }?.let { return it }
        if (candidate.streamType == StreamType.DIRECT && !candidate.sliceFetch) return null
        val players = snapshot.elements.filter { it.isBlob && !it.isAudio }
        if (players.isEmpty()) return null
        val duration = candidate.durationSeconds
        if (duration != null) {
            players.filter { it.durationSeconds != null && abs(it.durationSeconds - duration) < 2.0 }.maxByOrNull { it.area }?.let { return it }
        }
        // One stream on the page and one MSE player: they belong together.
        val streams = all.count { it.streamType != StreamType.DIRECT || it.sliceFetch }
        return if (streams <= 1) players.maxByOrNull { it.area } else null
    }

    /** Title, thumbnail and length from the best metadata available — never a CDN file name. */
    private fun enrich(scored: Scored, snapshot: PageMediaSnapshot?, pageTitle: String?, host: String?, isTop: Boolean): MediaCandidate {
        val candidate = scored.candidate
        val paths = pathsOf(candidate)
        val structured = snapshot?.structuredVideos?.firstOrNull { it.contentUrl?.let(::path) in paths }
            ?: snapshot?.structuredVideos?.singleOrNull()?.takeIf { isTop }
        return candidate.copy(
            title = FilenameResolver.title(structured?.name, pageTitle, candidate.title, host),
            thumbnailUrl = scored.element?.poster ?: candidate.thumbnailUrl ?: structured?.thumbnailUrl ?: snapshot?.ogImage,
            durationSeconds = candidate.durationSeconds ?: scored.element?.durationSeconds ?: structured?.durationSeconds,
        )
    }

    private fun pathsOf(candidate: MediaCandidate) = (candidate.variants.map { it.url } + candidate.url).map(::path).toSet()
    private fun path(url: String) = url.substringBefore('#').substringBefore('?')
}
