package app.fetch.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import app.fetch.download.DownloadCoordinator
import app.fetch.download.MediaCandidate
import app.fetch.download.MediaFiles
import app.fetch.download.MediaGrouping
import app.fetch.download.StreamType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID
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

    // Read from WebView worker threads (shouldInterceptRequest, JS bridge), where Compose state must not be touched.
    internal val pageUrl = AtomicReference(url)
    internal val pageTitle = AtomicReference("")
    @Volatile internal var sessionId = -1L
    @Volatile internal var userAgent = ""
    internal var sessionUrl: String? = null
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
        // Media fetched by a site's service worker never reaches a WebViewClient; route it into the same pipeline.
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    activeTabRef.get()?.let { intercept(it, request) }
                    return null
                }
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
        webViews[id]?.evaluateJavascript(RESCAN_SCRIPT, null)
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

    /** "Download link/image" from the long-press menu. */
    fun downloadLink(menu: LinkMenu) {
        val mime = if (menu.isImage) MediaFiles.resolveMime(StreamType.DIRECT, menu.url).takeIf { it.startsWith("image/") } ?: "image/jpeg" else null
        report(activeTab, menu.url, mime, null, explicit = true, title = URLUtil.guessFileName(menu.url, null, mime))
    }

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
        if (url.substringBefore('#') != tab.sessionUrl?.substringBefore('#')) {
            tab.previousPageUrl = tab.sessionUrl
            tab.pageTitle.set("")
            startSession(tab)
        }
    }

    /** One pipeline for page and service-worker requests, so the same file is never reported by two routes. */
    private fun intercept(tab: BrowserTab, request: WebResourceRequest) {
        if (request.isForMainFrame) return
        val url = request.url.toString()
        val headers = request.requestHeaders
        val range = headers?.entries?.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        val slice = MediaGrouping.isSliceRequest(url, range)
        if (UrlUtils.isCandidateUrl(url)) {
            report(tab, url, null, headers, explicit = false, sliceFetch = slice)
        } else if (!UrlUtils.isStaticAsset(url) && range?.startsWith("bytes=0-") == true) {
            // <video>/<audio> elements fetch with "Range: bytes=0-"; it is the only hint for extension-less media URLs.
            report(tab, url, null, headers, explicit = false, probe = true, sliceFetch = slice)
        }
    }

    private fun report(
        tab: BrowserTab, url: String, mime: String?, requestHeaders: Map<String, String>?, explicit: Boolean,
        title: String? = null, sizeBytes: Long? = null, probe: Boolean = false, sliceFetch: Boolean = false, thumbnailUrl: String? = null,
        groupKey: String? = null,
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
        addJavascriptInterface(MediaBridge(tab), "FetchMedia")
        webChromeClient = ChromeClient(tab)
        webViewClient = Client(tab)
        setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            report(tab, url, mimeType, mapOf("User-Agent" to userAgent), explicit = true, title = name, sizeBytes = contentLength.takeIf { it > 0 })
            // A link that opened a window only to start a download leaves an empty tab behind.
            if (copyBackForwardList().size == 0 && tabs.size > 1) post { closeTab(tab.id) }
        }
        setOnLongClickListener { onLongPress(this) }
        tab.pendingUrl?.let { tab.pendingUrl = null; loadUrl(it) }
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

    /** Receives <video>/<audio> sources from the injected script; runs on a WebView binder thread. */
    private inner class MediaBridge(private val tab: BrowserTab) {
        @JavascriptInterface
        fun onMedia(src: String?, poster: String?, elementKey: String?) {
            val url = src?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return
            report(tab, url, null, null, explicit = false, probe = true, thumbnailUrl = poster?.takeIf { it.startsWith("http", ignoreCase = true) },
                groupKey = elementKey?.ifBlank { null }?.let { "element:${tab.sessionId}:$it" })
        }
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
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url != null) onNavigated(tab, url)
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            if (!isReload && url != null) store.recordVisit(url, view.title.orEmpty())
        }

        override fun onPageFinished(view: WebView, url: String?) {
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            view.evaluateJavascript(PAGE_INFO_SCRIPT) { result ->
                val info = runCatching { JSONObject(decodeJsString(result)) }.getOrNull() ?: return@evaluateJavascript
                val page = tab.pageUrl.get()
                val title = info.optString("title").takeIf(::isUsableTitle)?.let { TitleNormalizer.clean(it, info.optString("site").ifBlank { null }, UrlUtils.host(page)) }
                val image = info.optString("image").ifBlank { null }?.let { runCatching { URL(URL(page), it).toString() }.getOrNull() }
                title?.let(tab.pageTitle::set)
                DownloadCoordinator.applyPageInfo(tab.sessionId, title, image)
            }
            view.evaluateJavascript(MEDIA_SCRIPT, null)
            persist()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            intercept(tab, request)
            return null
        }

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
        /** Title, site name and preview image for naming downloads and showing a thumbnail in the sheet. */
        val PAGE_INFO_SCRIPT = """
            (() => {
                const meta = (selector) => document.querySelector(selector)?.content || "";
                const video = document.querySelector('video[poster]');
                return JSON.stringify({
                    title: meta('meta[property="og:title"]') || meta('meta[name="twitter:title"]') || document.title || "",
                    site: meta('meta[property="og:site_name"]'),
                    image: meta('meta[property="og:image"]') || meta('meta[name="twitter:image"]') || (video ? video.getAttribute('poster') : "")
                });
            })()
        """.trimIndent()
        /** Reports <video>/<audio> sources now and whenever one starts playing (lazy players, infinite feeds). */
        val MEDIA_SCRIPT = """
            (function() {
                if (!window.FetchMedia) return;
                function report(el) {
                    try {
                        var urls = [el.currentSrc, el.src];
                        var sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
                        for (var i = 0; i < sources.length; i++) urls.push(sources[i].src);
                        urls = urls.filter(function(u, k) { return u && /^https?:/i.test(u) && urls.indexOf(u) === k; });
                        // Alternative <source> formats of one element are one video, not several.
                        if (!el.__fetchKey) el.__fetchKey = 'v' + (window.__fetchSeq = (window.__fetchSeq || 0) + 1);
                        var key = urls.length > 1 ? el.__fetchKey : "";
                        for (var j = 0; j < urls.length; j++) FetchMedia.onMedia(urls[j], el.poster || "", key);
                    } catch (e) {}
                }
                window.__fetchScanMedia = function() {
                    var els = document.querySelectorAll('video,audio');
                    for (var i = 0; i < els.length; i++) report(els[i]);
                };
                if (!window.__fetchMediaHooked) {
                    window.__fetchMediaHooked = true;
                    ['play', 'loadedmetadata'].forEach(function(type) {
                        document.addEventListener(type, function(e) {
                            var t = e.target;
                            if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) report(t);
                        }, true);
                    });
                }
                window.__fetchScanMedia();
            })();
        """.trimIndent()
        const val RESCAN_SCRIPT = "window.__fetchScanMedia && window.__fetchScanMedia();"
    }
}
