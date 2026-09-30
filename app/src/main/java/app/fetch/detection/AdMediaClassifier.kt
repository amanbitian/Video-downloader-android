package app.fetch.detection

import app.fetch.adblock.AdRules
import app.fetch.adblock.RequestDecision
import app.fetch.browser.UrlUtils

/**
 * Keeps advertisements out of the download list. Always on — independent of whether the browser blocks ad requests —
 * because an ad the page did load must still never be offered as "the video".
 */
object AdMediaClassifier {
    private val adPathInMedia = Regex("""(^|[/_.-])(vast|vmap|preroll|midroll|postroll|adserver|adtag|ad[_-]?creative|ads?)([/_.?=-]|$)""", RegexOption.IGNORE_CASE)
    private val vastDocument = Regex("""<\s*(?:[A-Za-z]+:)?(VAST|VMAP)\b""")
    private val adWords = setOf("ad", "ads", "advert", "advertisement", "adslot", "adunit", "sponsor", "sponsored", "preroll", "midroll", "ima", "vast", "outstream", "instream")
    private val relatedWords = setOf(
        "related", "recommended", "recommendation", "recommendations", "suggested", "suggestion", "suggestions", "upnext", "sidebar",
        "aside", "playlist", "carousel", "preview", "previews", "trending", "rail", "shelf", "teaser",
    )
    private val mainWords = setOf("main", "article", "player", "hero", "primary", "watch", "content", "story", "post")

    /** Ad servers, ad CDNs and ad-shaped paths (VAST/VMAP, pre-rolls, creatives). */
    fun isAdUrl(url: String): Boolean {
        if (AdRules.classifyHost(UrlUtils.host(url)) != RequestDecision.ALLOW) return true
        return adPathInMedia.containsMatchIn(url.substringAfter("://").substringAfter('/', "").substringBefore('#'))
    }

    /** A manifest we fetched turned out to be a VAST/VMAP ad document. */
    fun isVastDocument(text: String): Boolean = vastDocument.containsMatchIn(text.take(4_096))

    /** Players flag themselves while an ad runs ("vjs-ad-playing", "jw-flag-ads"); that describes a state, not an ad slot. */
    private val stateWords = setOf("playing", "active", "flag", "mode", "loading", "loaded", "started", "paused", "enabled", "disabled", "free", "blocker")

    fun isAdContext(words: List<String>) = words.indices.any { i ->
        words[i] in adWords && words.getOrNull(i - 1) !in stateWords && words.getOrNull(i + 1) !in stateWords
    }
    fun isRelatedContext(words: List<String>) = words.any { it in relatedWords } || words.windowed(2).any { (a, b) -> a == "up" && b == "next" }
    fun isMainContext(words: List<String>) = words.any { it in mainWords }
}
