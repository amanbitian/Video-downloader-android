package app.fetch.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.fetch.BuildConfig
import app.fetch.adblock.CosmeticFilter
import app.fetch.adblock.RequestBlocker
import app.fetch.adblock.RequestDecision
import app.fetch.detection.MediaSignal
import app.fetch.detection.PageMediaSnapshot
import app.fetch.detection.PrimaryMediaResolver
import app.fetch.download.DownloadCoordinator
import app.fetch.settings.AppSettings
import app.fetch.download.MediaCandidate
import app.fetch.download.MediaGrouping
import app.fetch.download.StreamType
import org.json.JSONArray
import java.util.UUID
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Stable
class BrowserTab(val id: String, url: String, title: String) {
    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var desktopMode by mutableStateOf(false)
    /** The home page covers the tab; its WebView and history stay alive underneath. */
    var showingHome by mutableStateOf(url.isBlank())
    /** Bumped when the renderer died and the WebView had to be replaced. */
    var generation by mutableIntStateOf(0)
    /** The browser bar is shown; it hides while the user scrolls down through the page. */
    var barVisible by mutableStateOf(true)
    internal var scrollTravel = 0

    // Read from WebView worker threads (shouldInterceptRequest, JS bridge), where Compose state must not be touched.
    internal val pageUrl = AtomicReference(url)
    /** Host of [pageUrl] (lower-case, no "www."), kept alongside it so the request blocker never re-parses the page URL. */
    @Volatile internal var pageHost: String = UrlUtils.host(url)
    internal val pageTitle = AtomicReference("")
    @Volatile internal var sessionId = -1L
    @Volatile internal var userAgent = ""
    internal var sessionUrl: String? = null
    /** Requests refused by the ad/tracker blocker on this page. */
    internal val blockedRequests = AtomicInteger(0)
    /** A debounced rescan is already queued (set from worker threads). */
    internal val scanPending = AtomicBoolean(false)
    /** The page just left. A media document (e.g. a bare .mp4) keeps fetching itself briefly after navigation. */
    @Volatile internal var previousPageUrl: String? = null
    /** Loaded when the tab's WebView is first created; tabs restored from disk load lazily. */
    internal var pendingUrl: String? = url.ifBlank { null }
}

data class LinkMenu(val url: String, val isImage: Boolean)

/**
 * Owns one WebView per tab for the activity's lifetime, so switching tabs or screens never reloads a page or loses its
 * back stack. Everything except the WebView worker callbacks runs on the main thread.
 */
class BrowserController(private val activity: ComponentActivity, val store: BrowserStore) {
    val tabs = mutableStateListOf<BrowserTab>()
    var activeTabId by mutableStateOf("")
        private set
    var fullscreenView by mutableStateOf<View?>(null)
        private set
    var linkMenu by mutableStateOf<LinkMenu?>(null)

    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingPageFocus = false
    private val mainHandler = Handler(Looper.getMainLooper())
    /** The visible tab, for callbacks on worker threads (service worker) that can't read Compose state. */
    private val activeTabRef = AtomicReference<BrowserTab?>(null)
    private val webViews = HashMap<String, WebView>()
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    init {
        val (saved, activeId) = store.loadTabs()
        saved.forEach { tabs += BrowserTab(it.id, it.url, it.title) }
        if (tabs.isEmpty()) tabs += BrowserTab(UUID.randomUUID().toString(), "", "")
        activeTabId = tabs.firstOrNull { it.id == activeId }?.id ?: tabs.first().id
        activeTabRef.set(activeTab)
        startSession(activeTab)
        // Lets chrome://inspect attach to pages in debug builds only.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        // Media fetched by a site's service worker never reaches a WebViewClient; route it into the same pipeline.
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    activeTabRef.get()?.let { intercept(it, request) }
            })
        }
    }

    val activeTab: BrowserTab get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()

    val canHandleBack: Boolean
        get() {
            val tab = activeTab
            return fullscreenView != null || (tab.showingHome && tab.id in webViews && tab.url.isNotBlank()) || (!tab.showingHome && tab.canGoBack)
        }

    fun handleBack(): Boolean {
        val tab = activeTab
        when {
            fullscreenView != null -> exitFullscreen()
            tab.showingHome && tab.id in webViews && tab.url.isNotBlank() -> tab.showingHome = false
            !tab.showingHome && tab.canGoBack -> webViews[tab.id]?.goBack()
            else -> return false
        }
        return true
    }

    fun webViewFor(tab: BrowserTab): WebView = webViews.getOrPut(tab.id) { createWebView(tab) }

    fun newTab(url: String = "", select: Boolean = true): BrowserTab {
        val tab = BrowserTab(UUID.randomUUID().toString(), "", "")
        tabs += tab
        if (select) selectTab(tab.id)
        if (url.isNotBlank()) load(tab, url)
        persist()
        return tab
    }

    fun selectTab(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        if (id != activeTabId) webViews[activeTabId]?.onPause()
        activeTabId = id
        activeTabRef.set(tab)
        webViews[id]?.onResume()
        startSession(tab)
        scheduleScan(tab, 0)
        persist()
    }

    fun closeTab(id: String) {
        val index = tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        tabs.removeAt(index)
        webViews.remove(id)?.let(::destroyWebView)
        if (tabs.isEmpty()) tabs += BrowserTab(UUID.randomUUID().toString(), "", "")
        // Focus the neighbour that slid into the closed tab's place, like every mobile browser.
        if (id == activeTabId) selectTab(tabs[minOf(index, tabs.lastIndex)].id)
        persist()
    }

    fun load(input: String) = load(activeTab, input)

    private fun load(tab: BrowserTab, input: String) {
        val url = UrlUtils.normalize(input)
        if (url.isBlank()) return
        tab.showingHome = false
        tab.url = url
        val webView = webViews[tab.id]
        if (webView == null) tab.pendingUrl = url else webView.loadUrl(url)
    }

    /**
     * After Go, focus moves to the page like in Chrome. Merely clearing Compose focus isn't enough with a hardware
     * keyboard: outside touch mode Compose re-focuses the first focusable, which is the address bar.
     */
    fun focusPage() {
        val webView = webViews[activeTabId]
        if (webView?.isAttachedToWindow == true) webView.requestFocus() else pendingPageFocus = true
    }

    /** Hands the tab's WebView to the UI for attaching, detached from any previous host. */
    fun attach(tab: BrowserTab): WebView = webViewFor(tab).also { webView ->
        (webView.parent as? ViewGroup)?.removeView(webView)
        if (pendingPageFocus) {
            pendingPageFocus = false
            webView.post { webView.requestFocus() }
        }
    }

    fun goBack() { webViews[activeTabId]?.goBack() }
    fun goForward() { webViews[activeTabId]?.goForward() }
    fun goHome() { activeTab.showingHome = true }

    fun reloadOrStop() {
        val tab = activeTab
        val webView = webViews[tab.id] ?: return
        if (tab.progress < 100) webView.stopLoading() else webView.reload()
    }

    fun toggleDesktopMode() {
        val tab = activeTab
        tab.desktopMode = !tab.desktopMode
        webViews[tab.id]?.let { applyUserAgent(it, tab); it.reload() }
    }

    fun toggleBookmark() {
        val tab = activeTab
        store.toggleBookmark(tab.url, tab.title)
    }

    fun exitFullscreen() {
        val callback = fullscreenCallback
        fullscreenView = null
        fullscreenCallback = null
        callback?.onCustomViewHidden()
    }

    /** "Download link" from the long-press menu. Images are not offered: photos are never downloads. */
    fun downloadLink(menu: LinkMenu) {
        if (menu.isImage) return
        report(activeTab, menu.url, null, null, explicit = true, title = URLUtil.guessFileName(menu.url, null, null))
    }

    /** Whether ads are allowed on the current site (a per-site exception to the blocker). */
    val adsAllowedHere: Boolean
        get() = isAllowedSite(UrlUtils.host(activeTab.url), AppSettings.values.value.adAllowedSites)

    fun toggleAdsOnThisSite() {
        val site = UrlUtils.registrableDomain(UrlUtils.host(activeTab.url)).ifBlank { return }
        AppSettings.update { it.copy(adAllowedSites = if (site in it.adAllowedSites) it.adAllowedSites - site else it.adAllowedSites + site) }
        webViews[activeTabId]?.reload()
    }

    private fun isAllowedSite(host: String, sites: Set<String>) = sites.any { host == it || host.endsWith(".$it") }

    fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        webViews.values.forEach { it.clearCache(true); it.clearHistory(); it.clearFormData() }
        tabs.forEach { it.canGoBack = false; it.canGoForward = false }
        store.clearHistory()
    }

    fun persist() = store.saveTabs(tabs.map { SavedTab(it.id, it.url, it.title) }, activeTabId)

    fun onPause() {
        webViews.values.forEach { it.onPause() }
        CookieManager.getInstance().flush()
        persist()
    }

    fun onResume() { webViews[activeTabId]?.onResume() }

    fun destroy() {
        runCatching { ServiceWorkerController.getInstance().setServiceWorkerClient(null) }
        exitFullscreen()
        webViews.values.forEach(::destroyWebView)
        webViews.clear()
    }

    private fun startSession(tab: BrowserTab) {
        tab.sessionUrl = tab.pageUrl.get()
        // Only the visible tab feeds the download button; background tabs' media is dropped until they are selected.
        tab.sessionId = if (tab.id == activeTabId) DownloadCoordinator.startNewSession() else -1L
    }

    /** Called for full loads and for single-page-app URL changes; a new page means a fresh list of detected media. */
    private fun onNavigated(tab: BrowserTab, url: String) {
        tab.url = url
        tab.pageUrl.set(url)
        tab.pageHost = UrlUtils.host(url)
        if (url.substringBefore('#') != tab.sessionUrl?.substringBefore('#')) {
            tab.previousPageUrl = tab.sessionUrl
            tab.pageTitle.set("")
            tab.blockedRequests.set(0)
            startSession(tab)
        }
    }

    /**
     * One pipeline for page and service-worker requests (so the same file is never reported by two routes). Runs on
     * WebView worker threads: first the optional ad/tracker blocker, then media sniffing. Returns a response only to block.
     */
    private fun intercept(tab: BrowserTab, request: WebResourceRequest): WebResourceResponse? {
        // Page loads themselves are never blocked or sniffed.
        if (request.isForMainFrame) return null
        val uri = request.url
        val url = uri.toString()
        val settings = AppSettings.values.value
        if (settings.blockAds) {
            // The page host is cached per tab and the request host comes from the already-parsed Uri: no URL parsing here.
            val decision = RequestBlocker.classify(tab.pageHost, UrlUtils.normalizeHost(uri.host), url, isMainFrame = false, enabled = true, settings.adAllowedSites)
            if (decision != RequestDecision.ALLOW) {
                tab.blockedRequests.incrementAndGet()
                return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(EMPTY_BODY))
            }
        }
        val kind = UrlUtils.classifyRequest(url)
        if (kind == UrlUtils.RequestKind.STATIC) return null
        val headers = request.requestHeaders
        val range = headers?.entries?.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        if (kind == UrlUtils.RequestKind.MEDIA) {
            report(tab, url, null, headers, explicit = false, sliceFetch = MediaGrouping.isSliceRequest(url, range))
            requestScanSoon(tab)
        } else if (range?.startsWith("bytes=0-") == true) {
            // <video>/<audio> elements fetch with "Range: bytes=0-"; it is the only hint for extension-less media URLs.
            report(tab, url, null, headers, explicit = false, probe = true, sliceFetch = MediaGrouping.isSliceRequest(url, range))
            requestScanSoon(tab)
        }
        return null
    }

    /** Staged DOM/metadata scans. Players often appear after load, so look again shortly after — never by polling. */
    private fun scheduleScan(tab: BrowserTab, delayMs: Long) {
        val webView = webViews[tab.id] ?: return
        val session = tab.sessionId
        if (session <= 0) return
        webView.postDelayed({ if (tab.sessionId == session && webViews[tab.id] === webView) runScan(tab, webView, session) }, delayMs)
    }

    /** Media just showed up on the network (e.g. the user pressed play); rescan once, debounced, to tie it to its player. */
    private fun requestScanSoon(tab: BrowserTab) {
        if (!tab.scanPending.compareAndSet(false, true)) return
        mainHandler.postDelayed({ tab.scanPending.set(false); scheduleScan(tab, 0) }, NETWORK_RESCAN_DELAY_MS)
    }

    private fun runScan(tab: BrowserTab, webView: WebView, session: Long) {
        webView.evaluateJavascript(PageMediaScanner.SCRIPT) { result ->
            // Every result carries the session it was taken in; a scan of a page the user already left is dropped.
            if (tab.sessionId != session) return@evaluateJavascript
            val snapshot = PageMediaSnapshot.parse(decodeJsString(result))
            if (snapshot == null) {
                Log.w(TAG, "Page scan returned no data: ${result?.take(200)}")
                return@evaluateJavascript
            }
            Log.d(TAG, "Page scan: ${snapshot.elements.size} media elements, ${snapshot.ogVideos.size} og:video, ${snapshot.structuredVideos.size} JSON-LD")
            onSnapshot(tab, session, snapshot)
        }
    }

    private fun onSnapshot(tab: BrowserTab, session: Long, snapshot: PageMediaSnapshot) {
        val host = UrlUtils.host(snapshot.pageUrl)
        val title = listOfNotNull(snapshot.ogTitle, snapshot.documentTitle).firstOrNull(::isUsableTitle)?.let { TitleNormalizer.clean(it, snapshot.siteName, host) }
        title?.let(tab.pageTitle::set)
        DownloadCoordinator.applyPageInfo(session, title, snapshot.ogImage)
        DownloadCoordinator.applySnapshot(session, snapshot)
        // Detect before playback: what the page declares, whether or not it has fetched it yet.
        snapshot.elements.filter(PrimaryMediaResolver::isPlausiblePlayer).forEach { element ->
            val group = element.key.takeIf { element.sources.size > 1 }?.let { "element:$session:$it" }
            element.sources.forEach { source ->
                report(tab, source, null, null, explicit = false, probe = true, thumbnailUrl = element.poster, groupKey = group,
                    elementKey = element.key, signal = MediaSignal.DOM_ELEMENT)
            }
        }
        snapshot.ogVideos.forEach { report(tab, it, null, null, explicit = false, probe = true, thumbnailUrl = snapshot.ogImage, signal = MediaSignal.OG_VIDEO) }
        snapshot.structuredVideos.forEach { video ->
            video.contentUrl?.let { report(tab, it, null, null, explicit = false, probe = true, thumbnailUrl = video.thumbnailUrl, signal = MediaSignal.STRUCTURED_DATA) }
        }
    }

    private fun report(
        tab: BrowserTab, url: String, mime: String?, requestHeaders: Map<String, String>?, explicit: Boolean,
        title: String? = null, sizeBytes: Long? = null, probe: Boolean = false, sliceFetch: Boolean = false, thumbnailUrl: String? = null,
        groupKey: String? = null, elementKey: String? = null, signal: MediaSignal = MediaSignal.NETWORK,
    ) {
        if (!url.startsWith("http", ignoreCase = true)) return
        if (!explicit && url == tab.previousPageUrl) return
        val fileName = url.substringBefore('?').substringAfterLast('/').ifBlank { "media" }
        val lowered = "$url ${mime.orEmpty()}".lowercase()
        val streamType = when {
            ".m3u8" in lowered || "mpegurl" in lowered -> StreamType.HLS
            ".mpd" in lowered || "dash+xml" in lowered -> StreamType.DASH
            else -> StreamType.DIRECT
        }
        val candidate = MediaCandidate(
            url = url, title = title ?: tab.pageTitle.get().takeIf(::isUsableTitle) ?: fileName, mimeType = mime, streamType = streamType,
            requestHeaders = headersFor(tab, url, requestHeaders), pageUrl = tab.pageUrl.get(), explicit = explicit,
            probe = probe, sizeBytes = sizeBytes, sliceFetch = sliceFetch, thumbnailUrl = thumbnailUrl, groupKey = groupKey,
            elementKey = elementKey, signals = setOf(signal),
        )
        if (explicit) DownloadCoordinator.detect(candidate) else DownloadCoordinator.detect(candidate, tab.sessionId)
    }

    /** Replays what the page itself sent (real User-Agent, Referer, Origin, cookies), since CDNs often bind access to them. */
    private fun headersFor(tab: BrowserTab, mediaUrl: String, requestHeaders: Map<String, String>?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        requestHeaders?.forEach { (name, value) -> if (name.lowercase() !in DROPPED_HEADERS) headers[name] = value }
        fun has(name: String) = headers.keys.any { it.equals(name, ignoreCase = true) }
        if (!has("User-Agent")) tab.userAgent.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        if (!has("Referer")) tab.pageUrl.get().takeIf { it.startsWith("http") }?.let { headers["Referer"] = it }
        CookieManager.getInstance().getCookie(mediaUrl)?.let { headers["Cookie"] = it }
        return headers
    }

    private fun applyUserAgent(webView: WebView, tab: BrowserTab) {
        webView.settings.userAgentString = if (tab.desktopMode) DESKTOP_USER_AGENT else null
        tab.userAgent = webView.settings.userAgentString
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: BrowserTab): WebView = WebView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        applyUserAgent(this, tab)
        webChromeClient = ChromeClient(tab)
        webViewClient = Client(tab)
        setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            report(tab, url, mimeType, mapOf("User-Agent" to userAgent), explicit = true, title = name, sizeBytes = contentLength.takeIf { it > 0 })
            // A link that opened a window only to start a download leaves an empty tab behind.
            if (copyBackForwardList().size == 0 && tabs.size > 1) post { closeTab(tab.id) }
        }
        setOnLongClickListener { onLongPress(this) }
        setOnScrollChangeListener { _, _, scrollY, _, oldScrollY -> onPageScrolled(tab, scrollY, scrollY - oldScrollY) }
        tab.pendingUrl?.let { tab.pendingUrl = null; loadUrl(it) }
    }

    /**
     * Hides the bar after a deliberate scroll down and brings it back on any clear scroll up or at the top, so reading
     * gets the full screen. Travel is accumulated so small jitters and flings reversing by a few pixels don't flicker it.
     */
    private fun onPageScrolled(tab: BrowserTab, scrollY: Int, delta: Int) {
        if (scrollY <= 0) { tab.barVisible = true; tab.scrollTravel = 0; return }
        tab.scrollTravel = if ((delta > 0) == (tab.scrollTravel > 0)) tab.scrollTravel + delta else delta
        val density = activity.resources.displayMetrics.density
        if (tab.scrollTravel > HIDE_AFTER_DP * density) tab.barVisible = false
        else if (tab.scrollTravel < -SHOW_AFTER_DP * density) tab.barVisible = true
    }

    private fun onLongPress(webView: WebView): Boolean {
        val hit = webView.hitTestResult
        val extra = hit.extra?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return false
        linkMenu = when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> LinkMenu(extra, isImage = false)
            WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> LinkMenu(extra, isImage = true)
            else -> return false
        }
        return true
    }

    private fun destroyWebView(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        runCatching { webView.destroy() }
    }

    private fun onRendererGone(tab: BrowserTab, dead: WebView) {
        if (webViews[tab.id] === dead) webViews.remove(tab.id)
        destroyWebView(dead)
        tab.pendingUrl = tab.url.ifBlank { null }
        tab.progress = 100
        tab.generation++
    }

    private inner class Client(private val tab: BrowserTab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme in WEB_SCHEMES) return false
            // Apps links (intent:, market:, tel:, mailto:) only open on a real tap, never from a redirect.
            if (request.hasGesture()) openExternal(view, request.url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != null) onNavigated(tab, url)
            tab.barVisible = true
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            val before = tab.sessionId
            if (url != null) onNavigated(tab, url)
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            if (!isReload && url != null) store.recordVisit(url, view.title.orEmpty())
            // Single-page apps swap the video without a page load; the new content renders shortly after the URL changes.
            if (tab.sessionId != before && tab.progress >= 100) SPA_SCAN_DELAYS_MS.forEach { scheduleScan(tab, it) }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            PAGE_SCAN_DELAYS_MS.forEach { scheduleScan(tab, it) }
            // Blocked ads leave their reserved boxes behind; collapse them unless the user allowed ads on this site.
            val settings = AppSettings.values.value
            if (settings.blockAds && !isAllowedSite(tab.pageHost, settings.adAllowedSites)) view.evaluateJavascript(CosmeticFilter.SCRIPT, null)
            persist()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = intercept(tab, request)

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            onRendererGone(tab, view)
            return true
        }
    }

    private inner class ChromeClient(private val tab: BrowserTab) : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            if (title.isNullOrBlank()) return
            tab.title = title
            if (tab.pageTitle.get().isBlank() && isUsableTitle(title)) {
                val clean = TitleNormalizer.clean(title, null, UrlUtils.host(tab.pageUrl.get()))
                tab.pageTitle.set(clean)
                DownloadCoordinator.applyPageInfo(tab.sessionId, clean, null)
            }
            store.updateTitle(view.url.orEmpty(), title)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) { callback.onCustomViewHidden(); return }
            fullscreenView = view
            fullscreenCallback = callback
        }

        override fun onHideCustomView() {
            fullscreenView = null
            fullscreenCallback = null
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Pop-ups without a tap are almost always ads.
            if (!isUserGesture) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val child = newTab()
            child.showingHome = false
            transport.webView = webViewFor(child)
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            webViews.entries.firstOrNull { it.value === window }?.key?.let(::closeTab)
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                fileChooser.launch(params.createIntent())
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    private fun openExternal(view: WebView, url: String) {
        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
        // A web page must never be able to address a specific component of an installed app.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            intent.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("http") }?.let(view::loadUrl)
        }
    }

    /** WebView titles a bare media or file URL with the URL itself; that makes a poor file name. */
    private fun isUsableTitle(title: String) = title.isNotBlank() && !(title.none(Char::isWhitespace) && '/' in title)

    private fun decodeJsString(result: String?): String =
        if (result == null || result == "null") "" else runCatching { JSONArray("[$result]").optString(0) }.getOrDefault("").trim()

    private companion object {
        val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")
        val DROPPED_HEADERS = setOf("range", "if-range", "if-none-match", "if-modified-since", "accept-encoding", "cookie")
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        /** Right after load, then again for players that render late. */
        val PAGE_SCAN_DELAYS_MS = longArrayOf(0L, 1_000L, 3_500L)
        val SPA_SCAN_DELAYS_MS = longArrayOf(800L, 2_500L)
        const val NETWORK_RESCAN_DELAY_MS = 700L
        const val HIDE_AFTER_DP = 48
        const val SHOW_AFTER_DP = 24
        const val TAG = "FetchDetect"
        val EMPTY_BODY = ByteArray(0)
    }
}
