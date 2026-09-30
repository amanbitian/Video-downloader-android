package app.fetch.adblock

import app.fetch.browser.UrlUtils
import java.util.concurrent.ConcurrentHashMap

enum class RequestDecision { ALLOW, BLOCK_AD, BLOCK_TRACKER }

/**
 * Curated, deliberately small rule set: well-known ad networks and trackers, not a full filter-list engine.
 *
 * This runs for every sub-resource of every page on WebView worker threads, so it is built for the hot path: host rules
 * are hash sets looked up by walking the host's own labels (a handful of lookups, independent of list size), and each
 * host is decided once and remembered.
 */
object AdRules {
    private val adHosts = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "googletagservices.com",
        "imasdk.googleapis.com", "pagead2.googlesyndication.com", "adnxs.com", "amazon-adsystem.com", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
        "smartadserver.com", "teads.tv", "spotxchange.com", "spotx.tv", "springserve.com", "3lift.com", "sharethrough.com",
        "media.net", "mgid.com", "propellerads.com", "propellerclick.com", "popads.net", "popcash.net", "adsterra.com",
        "exoclick.com", "juicyads.com", "trafficjunky.net", "onclickads.net", "adform.net", "bidswitch.net", "33across.com",
        "yieldmo.com", "zedo.com", "adcolony.com", "applovin.com", "inmobi.com", "unityads.unity3d.com", "adsafeprotected.com",
        "doubleverify.com", "moatads.com", "serving-sys.com", "flashtalking.com", "innovid.com", "tremorhub.com", "lijit.com",
        "sonobi.com", "indexww.com", "adroll.com", "revcontent.com", "zergnet.com", "vidoomy.com", "aniview.com", "connatix.com",
    )
    private val trackerHosts = hashSetOf(
        "google-analytics.com", "googletagmanager.com", "scorecardresearch.com", "quantserve.com", "chartbeat.com",
        "hotjar.com", "mixpanel.com", "segment.io", "amplitude.com", "newrelic.com", "nr-data.net", "bat.bing.com",
        "clarity.ms", "krxd.net", "bluekai.com", "demdex.net", "omtrdc.net", "everesttech.net", "agkn.com", "rlcdn.com",
        "addthis.com", "sharethis.com", "tapad.com", "adsymptotic.com", "mc.yandex.ru",
    )

    /** Path markers of ad servers; only applied to third-party requests, where false positives on a site's own paths can't happen. */
    private val adPath = Regex("""(^|[/_.-])(ads?|adserver|adframe|adtag|pagead|prebid|vast|vmap|preroll|midroll)([/_.?=-]|$)""", RegexOption.IGNORE_CASE)

    /** Host → verdict. Pages reuse a few dozen hosts thousands of times; bounded so a crawler-like page can't grow it forever. */
    private val hostVerdicts = ConcurrentHashMap<String, RequestDecision>()
    private const val MAX_CACHED_HOSTS = 4_096

    fun classifyHost(host: String): RequestDecision {
        if (host.isEmpty()) return RequestDecision.ALLOW
        hostVerdicts[host]?.let { return it }
        val verdict = lookup(host)
        if (hostVerdicts.size < MAX_CACHED_HOSTS) hostVerdicts[host] = verdict
        return verdict
    }

    /** "a.b.doubleclick.net" → tries "a.b.doubleclick.net", "b.doubleclick.net", "doubleclick.net", "net". */
    private fun lookup(host: String): RequestDecision {
        var start = 0
        while (true) {
            val suffix = if (start == 0) host else host.substring(start)
            if (suffix in adHosts) return RequestDecision.BLOCK_AD
            if (suffix in trackerHosts) return RequestDecision.BLOCK_TRACKER
            val dot = host.indexOf('.', start)
            if (dot < 0) return RequestDecision.ALLOW
            start = dot + 1
        }
    }

    fun hasAdPath(url: String): Boolean {
        val pathStart = url.indexOf('/', url.indexOf("://") + 3)
        return pathStart >= 0 && adPath.containsMatchIn(url.subSequence(pathStart + 1, url.length))
    }

    internal fun cachedHostCount() = hostVerdicts.size
    internal fun clearCache() = hostVerdicts.clear()
}

/** Decides whether the browser loads a request at all. Independent of media detection, which filters ads regardless. */
object RequestBlocker {
    private val domainCache = ConcurrentHashMap<String, String>()

    /** Convenience for callers holding full URLs (tests, tools). The browser uses the host-based overload directly. */
    fun classify(pageUrl: String?, requestUrl: String, isMainFrame: Boolean, enabled: Boolean, allowedSites: Set<String>): RequestDecision =
        classify(UrlUtils.host(pageUrl), UrlUtils.host(requestUrl), requestUrl, isMainFrame, enabled, allowedSites)

    /**
     * Hot path. Hosts must already be lower-case without "www.". Cheapest checks first: switches, exceptions, a hash-set
     * host verdict; only then the third-party test and the path pattern.
     */
    fun classify(pageHost: String, requestHost: String, requestUrl: String, isMainFrame: Boolean, enabled: Boolean, allowedSites: Set<String>): RequestDecision {
        if (!enabled || isMainFrame) return RequestDecision.ALLOW
        if (allowedSites.isNotEmpty() && allowedSites.any { pageHost == it || pageHost.endsWith(".$it") }) return RequestDecision.ALLOW
        val byHost = AdRules.classifyHost(requestHost)
        if (byHost != RequestDecision.ALLOW) return byHost
        if (pageHost.isEmpty() || requestHost.isEmpty() || pageHost == requestHost) return RequestDecision.ALLOW
        if (domainOf(requestHost) == domainOf(pageHost)) return RequestDecision.ALLOW
        return if (AdRules.hasAdPath(requestUrl)) RequestDecision.BLOCK_AD else RequestDecision.ALLOW
    }

    private fun domainOf(host: String): String {
        domainCache[host]?.let { return it }
        val domain = UrlUtils.registrableDomain(host)
        if (domainCache.size < 2_048) domainCache[host] = domain
        return domain
    }
}
