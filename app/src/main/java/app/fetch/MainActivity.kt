package app.fetch

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fetch.browser.BrowserController
import app.fetch.browser.BrowserScreen
import app.fetch.browser.BrowserStore
import app.fetch.browser.UrlUtils
import app.fetch.download.DownloadCoordinator
import app.fetch.settings.AppSettings
import app.fetch.ui.DownloadBottomSheet
import app.fetch.ui.DownloadsScreen
import app.fetch.ui.FetchTheme
import app.fetch.ui.SettingsScreen
import app.fetch.ui.isDarkTheme

private const val SCREEN_BROWSE = 0
private const val SCREEN_DOWNLOADS = 1
private const val SCREEN_SETTINGS = 2

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private lateinit var browser: BrowserController
    private var screen by mutableIntStateOf(SCREEN_BROWSE)
    private var clipboardSuggestion by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadCoordinator.initialize(applicationContext)
        AppSettings.initialize(applicationContext)
        browser = BrowserController(this, BrowserStore(this))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        savedInstanceState?.let { screen = it.getInt(STATE_SCREEN, SCREEN_BROWSE) }
        // A recreated activity gets its original intent again; only act on it the first time.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by AppSettings.values.collectAsStateWithLifecycle()
            val dark = isDarkTheme(settings.theme)
            LaunchedEffect(dark) {
                val transparent = AndroidColor.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            FetchTheme(dark) {
                FetchApp(
                    browser = browser,
                    screen = screen,
                    onScreenChange = { screen = it },
                    clipboardSuggestion = clipboardSuggestion,
                    onClipboardSuggestionHandled = { clipboardSuggestion = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        browser.onResume()
    }

    override fun onPause() {
        browser.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SCREEN, screen)
    }

    /** Android 10+ only exposes the clipboard to the focused app, so check when focus arrives rather than in onResume. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = runCatching { getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull()
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.coerceToText(this)?.toString() else null
        val url = UrlUtils.extractUrl(text) ?: return
        val prefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_CLIPBOARD, null) == url || url == browser.activeTab.url) return
        prefs.edit().putString(KEY_LAST_CLIPBOARD, url).apply()
        clipboardSuggestion = url
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when {
            intent.getBooleanExtra(EXTRA_SHOW_DOWNLOADS, false) -> screen = SCREEN_DOWNLOADS
            intent.action == Intent.ACTION_SEND -> UrlUtils.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let(::openInNewTab)
            intent.action == Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.startsWith("http", ignoreCase = true) }?.let(::openInNewTab)
        }
    }

    private fun openInNewTab(url: String) {
        browser.newTab(url)
        screen = SCREEN_BROWSE
    }

    companion object {
        const val EXTRA_SHOW_DOWNLOADS = "app.fetch.SHOW_DOWNLOADS"
        private const val STATE_SCREEN = "screen"
        private const val PREFERENCES = "fetch_ui"
        private const val KEY_LAST_CLIPBOARD = "last_clipboard_url"
    }
}

@Composable
private fun FetchApp(
    browser: BrowserController,
    screen: Int,
    onScreenChange: (Int) -> Unit,
    clipboardSuggestion: String?,
    onClipboardSuggestionHandled: () -> Unit,
) {
    val downloads by DownloadCoordinator.downloads.collectAsStateWithLifecycle()
    val candidates by DownloadCoordinator.candidates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDownloadSheet by remember { mutableStateOf(false) }
    val fullscreenView = browser.fullscreenView

    LaunchedEffect(Unit) {
        DownloadCoordinator.sheetRequests.collect {
            onScreenChange(SCREEN_BROWSE)
            showDownloadSheet = true
        }
    }
    BackHandler(enabled = screen != SCREEN_BROWSE) { onScreenChange(SCREEN_BROWSE) }
    BackHandler(enabled = screen == SCREEN_BROWSE && browser.canHandleBack) { browser.handleBack() }

    Box(Modifier.fillMaxSize()) {
        // No bottom navigation bar: Downloads and Settings are reached from the browser's top bar and menu, and have a back arrow.
        // Each screen draws under the system bars itself; the keyboard shrinks the whole page.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
            when (screen) {
                SCREEN_BROWSE -> BrowserScreen(
                    controller = browser,
                    candidates = candidates,
                    runningDownloads = downloads.count { it.phase.isRunning },
                    onOpenDownloadSheet = { showDownloadSheet = true },
                    onOpenDownloads = { onScreenChange(SCREEN_DOWNLOADS) },
                    onOpenSettings = { onScreenChange(SCREEN_SETTINGS) },
                )
                SCREEN_DOWNLOADS -> DownloadsScreen(downloads, onBack = { onScreenChange(SCREEN_BROWSE) })
                else -> SettingsScreen(onBack = { onScreenChange(SCREEN_BROWSE) }, onClearBrowsingData = browser::clearBrowsingData)
            }
        }

        if (fullscreenView != null) FullscreenVideo(fullscreenView)
    }

    if (showDownloadSheet && candidates.isNotEmpty()) {
        DownloadBottomSheet(
            candidates = candidates,
            onDismiss = { showDownloadSheet = false },
            onDownload = { candidate, variant, name ->
                DownloadCoordinator.enqueue(context, candidate, variant, name)
                showDownloadSheet = false
            }
        )
    }
}

/** Hosts the page's fullscreen <video> element above everything, with system bars hidden. */
@Composable
private fun FullscreenVideo(view: View) {
    val hostView = LocalView.current
    DisposableEffect(view) {
        val window = (hostView.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    AndroidView(
        factory = { view.also { (it.parent as? ViewGroup)?.removeView(it) } },
        modifier = Modifier.fillMaxSize().background(Color.Black),
        onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
    )
}
