package app.fetch

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fetch.download.DownloadCoordinator
import app.fetch.download.DownloadItem
import app.fetch.download.DownloadService
import app.fetch.download.MediaCandidate
import app.fetch.download.MediaVariant
import app.fetch.download.TransferPhase
import app.fetch.download.StreamType
import app.fetch.download.asReadableBytes
import app.fetch.download.progress
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadCoordinator.initialize(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { FetchTheme { FetchApp() } }
    }
}

private val Ink = Color(0xFF16181D)
private val Blue = Color(0xFF2F6BFF)
private val Canvas = Color(0xFFFAFAFA)

@Composable
private fun FetchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Blue, onPrimary = Color.White, background = Canvas, surface = Color.White, onSurface = Ink), content = content)
}

@Composable
private fun FetchApp() {
    val context = LocalContext.current
    val downloads by DownloadCoordinator.downloads.collectAsStateWithLifecycle()
    val candidates by DownloadCoordinator.candidates.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var showDownloadSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Canvas,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Language, null) }, label = { Text("Browse") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("Downloads") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> BrowserScreen(
                    candidates = candidates,
                    onDetected = { DownloadCoordinator.detect(it) },
                    onOpenDownloadSheet = { showDownloadSheet = true }
                )
                1 -> DownloadsScreen(downloads)
                else -> SettingsScreen()
            }
        }
    }

    if (showDownloadSheet && candidates.isNotEmpty()) {
        DownloadBottomSheet(
            candidates = candidates,
            onDismiss = { showDownloadSheet = false },
            onDownload = { candidate, variant ->
                DownloadCoordinator.enqueue(context, candidate, variant)
                DownloadCoordinator.dismissCandidate(candidate.url)
                showDownloadSheet = false
            }
        )
    }
}

data class TabItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "",
    val title: String = "New tab"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(
    candidates: List<MediaCandidate>,
    onDetected: (MediaCandidate) -> Unit,
    onOpenDownloadSheet: () -> Unit
) {
    var tabs by remember { mutableStateOf(listOf(TabItem())) }
    var activeTabId by remember { mutableStateOf(tabs.first().id) }
    var showTabSwitcher by remember { mutableStateOf(false) }

    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    var addressInput by remember { mutableStateOf(activeTab.url) }

    LaunchedEffect(activeTabId) {
        addressInput = activeTab.url
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { 
                    Text(
                        activeTab.title.ifBlank { "New tab" }, 
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    ) 
                },
                navigationIcon = { 
                    IconButton(onClick = { showTabSwitcher = true }) { 
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Language, "Tabs")
                            Surface(
                                color = Blue,
                                contentColor = Color.White,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Text(
                                    text = tabs.size.toString(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                            }
                        }
                    } 
                },
                actions = {
                    IconButton(onClick = {
                        val newTab = TabItem()
                        tabs = tabs + newTab
                        activeTabId = newTab.id
                        addressInput = ""
                    }) {
                        Icon(Icons.Default.Refresh, "New Tab")
                    }
                }
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = addressInput,
                    onValueChange = { addressInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search or enter address") }
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val finalUrl = normalizeUrl(addressInput)
                        tabs = tabs.map { if (it.id == activeTabId) it.copy(url = finalUrl) else it }
                    },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Go")
                }
            }
            if (activeTab.url.isBlank()) {
                BrowserEmptyState { pasted ->
                    val finalUrl = normalizeUrl(pasted)
                    addressInput = finalUrl
                    tabs = tabs.map { if (it.id == activeTabId) it.copy(url = finalUrl) else it }
                }
            } else {
                BrowserView(
                    url = activeTab.url,
                    onTitle = { newTitle ->
                        tabs = tabs.map { if (it.id == activeTabId) it.copy(title = newTitle) else it }
                    },
                    onDetected = onDetected
                )
            }
        }

        // Native Floating Action Button layered above WebView
        if (candidates.isNotEmpty()) {
            FloatingActionButton(
                onClick = onOpenDownloadSheet,
                containerColor = Blue,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            ) {
                BadgedBox(
                    badge = {
                        if (candidates.size > 1) {
                            Badge(containerColor = Ink, contentColor = Color.White) {
                                Text(candidates.size.toString())
                            }
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download video"
                    )
                }
            }
        }
    }

    if (showTabSwitcher) {
        TabSwitcherDialog(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelect = { id ->
                activeTabId = id
                showTabSwitcher = false
            },
            onClose = { id ->
                if (tabs.size > 1) {
                    tabs = tabs.filterNot { it.id == id }
                    if (activeTabId == id) {
                        activeTabId = tabs.maxOfOrNull { it.id } ?: tabs.first().id
                    }
                } else {
                    tabs = listOf(TabItem())
                    activeTabId = tabs.first().id
                }
            },
            onNewTab = {
                val newTab = TabItem()
                tabs = tabs + newTab
                activeTabId = newTab.id
                addressInput = ""
                showTabSwitcher = false
            },
            onDismiss = { showTabSwitcher = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadBottomSheet(
    candidates: List<MediaCandidate>,
    onDismiss: () -> Unit,
    onDownload: (MediaCandidate, MediaVariant?) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text("Download video", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 400.dp)) {
                items(candidates, key = { it.url }) { candidate ->
                    Surface(
                        color = Color(0xFFF7F8FA),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(candidate.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))

                            when {
                                candidate.streamType == StreamType.HLS && candidate.variants.isEmpty() -> {
                                    Text("Resolving available qualities…", color = Color(0xFF626873), fontSize = 13.sp)
                                }
                                candidate.streamType == StreamType.HLS -> {
                                    candidate.variants.forEach { variant ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { onDownload(candidate, variant); onDismiss() }
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(variant.label, fontWeight = FontWeight.SemiBold, color = Blue)
                                                Text(variant.detail, color = Color(0xFF626873), fontSize = 12.sp)
                                            }
                                            Icon(Icons.Default.Download, "Download", tint = Blue)
                                        }
                                        HorizontalDivider(color = Color(0xFFE8E9EC))
                                    }
                                }
                                candidate.streamType == StreamType.DASH -> {
                                    Button(
                                        onClick = { onDownload(candidate, null); onDismiss() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                                    ) {
                                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Download Video Stream")
                                    }
                                }
                                else -> {
                                    Button(
                                        onClick = { onDownload(candidate, null); onDismiss() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                                    ) {
                                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Download Direct Stream")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TabSwitcherDialog(
    tabs: List<TabItem>,
    activeTabId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Open Tabs (${tabs.size})")
                Button(onClick = onNewTab) {
                    Text("New Tab")
                }
            }
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
                items(tabs, key = { it.id }) { tab ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSelect(tab.id) },
                        color = if (tab.id == activeTabId) Color(0xFFE1E6F4) else Color(0xFFF0F1F5),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tab.title.ifBlank { "New tab" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Text(tab.url.ifBlank { "About:blank" }, color = Color(0xFF626873), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onClose(tab.id) }) {
                                Icon(Icons.Default.Close, "Close tab")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun BrowserEmptyState(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Language, null, tint = Blue, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(20.dp))
        Text("Browse for media", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Open a page or paste a direct link. Fetch detects ordinary, non-protected audio and video resources as they load.", color = Color(0xFF626873), lineHeight = 20.sp)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clipData = clipboard?.primaryClip
            val clip = if (clipData != null && clipData.itemCount > 0) {
                clipData.getItemAt(0)?.coerceToText(context)?.toString()
            } else null
            if (!clip.isNullOrBlank()) onOpen(clip)
        }) { Text("Paste from clipboard") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserView(url: String, onTitle: (String) -> Unit, onDetected: (MediaCandidate) -> Unit) {
    var webViewGeneration by remember { mutableIntStateOf(0) }
    val webViewBundle = remember { Bundle() }
    val lastRequestedUrl = remember { AtomicReference<String?>(null) }
    val isRendererGone = remember { AtomicBoolean(false) }

    key(webViewGeneration) {
        AndroidView(factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        if (!url.isNullOrBlank()) onTitle(url.removePrefix("https://").removePrefix("http://").substringBefore('/'))
                    }
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val urlStr = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                        if (isCandidateUrl(urlStr)) {
                            val acceptHeader = request.requestHeaders?.get("Accept")
                            val defaultTitle = urlStr.substringBefore('?').substringAfterLast('/').ifBlank { "Detected media" }
                            onDetected(browserCandidate(urlStr, defaultTitle, acceptHeader, "Fetch/1.0 Android", url))
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        isRendererGone.set(true)
                        webViewGeneration++
                        return true
                    }
                }
                setDownloadListener(DownloadListener { detectedUrl, userAgent, contentDisposition, mimeType, _ ->
                    val title = Regex("filename=\\\"?([^;\\\"]+)").find(contentDisposition.orEmpty())?.groupValues?.getOrNull(1) ?: detectedUrl.substringBefore('?').substringAfterLast('/').ifBlank { "media" }
                    onDetected(browserCandidate(detectedUrl, title, mimeType, userAgent, url))
                })
                if (!webViewBundle.isEmpty && !isRendererGone.get()) {
                    restoreState(webViewBundle)
                } else if (url.isNotBlank()) {
                    lastRequestedUrl.set(url)
                    loadUrl(url)
                }
            }
        }, update = { webView ->
            if (url.isNotBlank() && lastRequestedUrl.getAndSet(url) != url) {
                webView.loadUrl(url)
            }
        }, modifier = Modifier.fillMaxSize(),
        onRelease = { webView ->
            if (!isRendererGone.get()) {
                runCatching { webView.saveState(webViewBundle) }
            }
            runCatching { webView.destroy() }
        })
    }
}

private fun isCandidateUrl(url: String): Boolean {
    val u = url.lowercase()
    if (u.endsWith(".svg") || u.endsWith(".png") || u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".webp") ||
        u.endsWith(".ico") || u.endsWith(".css") || u.endsWith(".js") || u.endsWith(".ts") || u.contains(".ts?") ||
        u.endsWith(".m4s") || u.contains(".m4s?") || u.contains("favicon") || u.contains("analytics")) {
        return false
    }
    return u.contains(".mp4") || u.contains(".webm") || u.contains(".m3u8") || u.contains(".mpd") || u.contains(".m4a") || u.contains(".mp3") || u.contains("video/") || u.contains("audio/")
}

@Composable
private fun CandidateDialog(candidate: MediaCandidate, onDownload: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Media detected") }, text = {
        Column {
            Text(candidate.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            when {
                candidate.streamType == StreamType.HLS && candidate.variants.isEmpty() -> Text("Resolving available qualities…", color = Color(0xFF626873))
                candidate.streamType == StreamType.HLS -> candidate.variants.forEach { variant ->
                    TextButton(onClick = { DownloadCoordinator.enqueue(context, candidate, variant); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { Text(variant.label, fontWeight = FontWeight.SemiBold); Text(variant.detail, color = Color(0xFF626873), fontSize = 13.sp) }
                        Icon(Icons.Default.Download, "Download ${variant.label}")
                    }
                }
                candidate.streamType == StreamType.DASH -> Text("DASH streams are detected but are not yet supported for download.", color = Color(0xFF626873))
                else -> Text(candidate.mimeType ?: "Direct media resource", color = Color(0xFF626873))
            }
            Text("Protected streams are not supported.", color = Color(0xFF626873), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }, confirmButton = { if (candidate.streamType == StreamType.DIRECT) Button(onClick = onDownload) { Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Download") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Dismiss") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreen(downloads: List<DownloadItem>) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Downloads", fontWeight = FontWeight.SemiBold) })
        if (downloads.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Download, null, tint = Color(0xFF8B919C), modifier = Modifier.size(38.dp)); Spacer(Modifier.height(14.dp)); Text("No downloads yet", fontWeight = FontWeight.Medium); Text("Detected files will appear here.", color = Color(0xFF626873)) } }
        else LazyColumn(Modifier.fillMaxSize()) { items(downloads, key = { it.id }) { item -> DownloadRow(item, onAction = { action -> DownloadCoordinator.command(context, action, item.id) }); HorizontalDivider(color = Color(0xFFE8E9EC)) } }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onAction: (String) -> Unit) {
    val active = item.phase in setOf(TransferPhase.CONNECTING, TransferPhase.DOWNLOADING, TransferPhase.VERIFYING)
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
        Icon(if (item.phase == TransferPhase.COMPLETED) Icons.Default.PlayArrow else Icons.Default.Download, null, tint = if (item.phase == TransferPhase.FAILED) Color(0xFFB3261E) else Blue, modifier = Modifier.padding(top = 3.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            if (active) LinearProgressIndicator(progress = { item.progress() }, Modifier.fillMaxWidth(), color = Blue, trackColor = Color(0xFFE1E6F4))
            Spacer(Modifier.height(7.dp))
            val detail = when (item.phase) {
                TransferPhase.DOWNLOADING -> "${item.downloadedBytes.asReadableBytes()}${item.totalBytes?.let { " / ${it.asReadableBytes()}" }.orEmpty()} · ${item.bytesPerSecond.asReadableBytes()}/s"
                TransferPhase.COMPLETED -> "Completed · saved to your media library"
                TransferPhase.FAILED -> item.error ?: "Download failed"
                TransferPhase.PAUSED -> "Paused at ${item.downloadedBytes.asReadableBytes()}"
                else -> item.phase.name.lowercase().replaceFirstChar { it.uppercaseChar() }
            }
            Text(detail, color = if (item.phase == TransferPhase.FAILED) Color(0xFFB3261E) else Color(0xFF626873), fontSize = 13.sp, fontFamily = if (active) FontFamily.Monospace else FontFamily.Default)
        }
        when {
            active -> IconButton(onClick = { onAction(DownloadService.ACTION_PAUSE) }) { Icon(Icons.Default.Pause, "Pause") }
            item.phase == TransferPhase.PAUSED || item.phase == TransferPhase.FAILED -> IconButton(onClick = { onAction(DownloadService.ACTION_RETRY) }) { Icon(Icons.Default.Refresh, "Retry") }
            else -> IconButton(onClick = { onAction(DownloadService.ACTION_CANCEL) }) { Icon(Icons.Default.Close, "Remove") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize()) { TopAppBar(title = { Text("Settings", fontWeight = FontWeight.SemiBold) }); SettingLine("Downloads", "Save completed videos to Movies/Fetch"); SettingLine("Privacy", "Browser data stays on this device"); SettingLine("Network", "Downloads can use Wi-Fi or mobile data"); Spacer(Modifier.height(12.dp)); Text("Fetch only downloads direct, non-DRM media resources. Respect content rights and website terms.", color = Color(0xFF626873), fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
}

@Composable
private fun SettingLine(title: String, summary: String) { Column(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 16.dp, vertical = 18.dp)) { Text(title, fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp)); Text(summary, color = Color(0xFF626873), fontSize = 14.sp) }; HorizontalDivider(color = Color(0xFFE8E9EC)) }

private fun normalizeUrl(input: String): String = input.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }

private fun browserCandidate(url: String, title: String, mime: String?, userAgent: String?, referer: String?): MediaCandidate {
    val headers = buildMap {
        put("User-Agent", userAgent ?: "Fetch/1.0 Android")
        referer?.takeIf { it.startsWith("http") }?.let { put("Referer", it) }
        CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
    }
    val lowered = "$url ${mime.orEmpty()}".lowercase()
    val stream = when {
        ".m3u8" in lowered || "mpegurl" in lowered -> StreamType.HLS
        ".mpd" in lowered || "dash+xml" in lowered -> StreamType.DASH
        else -> StreamType.DIRECT
    }
    return MediaCandidate(url, title, mime, stream, headers)
}
