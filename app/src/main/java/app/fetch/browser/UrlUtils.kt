package app.fetch.browser

import java.net.URLEncoder

object UrlUtils {
    private val urlPattern = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val hostPattern = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^/?#@]*@)?([^/?#:]+)""")
    /** Google Play forbids downloading from YouTube; Play-listed downloaders all block it. */
    private val blockedHosts = listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com")

    /** Turns address-bar input into a URL: keeps URLs, adds https:// to domains, sends everything else to search. */
    fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) return trimmed
        val isDomain = !trimmed.contains(' ') && (trimmed.contains('.') || trimmed == "localhost" || trimmed.contains(':'))
        return if (isDomain) "https://$trimmed" else "https://duckduckgo.com/?q=${URLEncoder.encode(trimmed, "UTF-8")}"
    }

    /** First http(s) URL inside shared text such as "Check this out https://… via App". */
    fun extractUrl(text: String?): String? =
        text?.let { urlPattern.find(it)?.value?.trimEnd('.', ',', ')', '!', '?', ';', ']') }

    /** "news.bbc.co.uk" → "bbc.co.uk", "m.youtube.com" → "youtube.com": the part a site owns. */
    fun registrableDomain(host: String): String {
        val labels = host.lowercase().removePrefix("www.").split('.').filter(String::isNotEmpty)
        if (labels.size <= 2) return labels.joinToString(".")
        val keep = if (labels.last().length == 2 && labels[labels.size - 2] in secondLevelLabels) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    private val secondLevelLabels = setOf("co", "com", "org", "net", "ac", "gov", "edu", "or", "ne")

    fun host(url: String?): String =url?.let { hostPattern.find(it)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.") }.orEmpty()

    fun isBlockedSource(vararg urls: String?): Boolean = urls.any { url ->
        val host = host(url)
        host.isNotEmpty() && blockedHosts.any { host == it || host.endsWith(".$it") }
    }

    enum class RequestKind { STATIC, MEDIA, OTHER }

    /** One pass (one lower-casing) for the request hot path: static asset, media-looking, or anything else. */
    fun classifyRequest(url: String): RequestKind {
        val u = url.lowercase().substringBefore('#')
        val path = u.substringBefore('?')
        if (staticExtensions.any(path::endsWith) || u.contains("favicon") || u.contains("analytics")) return RequestKind.STATIC
        return if (mediaHints.any(u::contains)) RequestKind.MEDIA else RequestKind.OTHER
    }

    /** Host as the blocker expects it (lower-case, no "www."), from an already-parsed URI host. */
    fun normalizeHost(host: String?): String = host?.lowercase()?.removePrefix("www.").orEmpty()

    /** Images, scripts, styles, fonts and stream segments: never offered as downloads on their own. */
    fun isStaticAsset(url: String): Boolean {
        val u = url.lowercase().substringBefore('#')
        val path = u.substringBefore('?')
        return staticExtensions.any(path::endsWith) || u.contains("favicon") || u.contains("analytics")
    }

    /** Cheap pre-filter for sub-resource requests; only URLs that look like media reach the coordinator. */
    fun isCandidateUrl(url: String): Boolean {
        if (isStaticAsset(url)) return false
        val u = url.lowercase().substringBefore('#')
        return mediaHints.any(u::contains)
    }

    private val staticExtensions = listOf(".svg", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".css", ".js", ".json", ".ts", ".m4s", ".woff", ".woff2", ".ttf", ".html")
    private val mediaHints = listOf(".mp4", ".webm", ".m3u8", ".mpd", ".m4a", ".mp3", ".mov", ".mkv", "video/", "audio/")
}
