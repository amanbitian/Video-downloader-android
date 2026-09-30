package app.fetch.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.fetch.download.MediaCandidate

private val Shortcuts = listOf(
    Bookmark("https://www.instagram.com", "Instagram"),
    Bookmark("https://www.facebook.com", "Facebook"),
    Bookmark("https://x.com", "X"),
    Bookmark("https://www.tiktok.com", "TikTok"),
    Bookmark("https://vimeo.com", "Vimeo"),
    Bookmark("https://www.dailymotion.com", "Dailymotion"),
    Bookmark("https://www.reddit.com", "Reddit"),
    Bookmark("https://www.pinterest.com", "Pinterest"),
)

@Composable
fun BrowserScreen(
    controller: BrowserController,
    candidates: List<MediaCandidate>,
    onOpenDownloadSheet: () -> Unit,
    clipboardSuggestion: String?,
    onClipboardSuggestionHandled: () -> Unit,
) {
    val tab = controller.activeTab
    var showTabSwitcher by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AddressBar(
                controller = controller,
                tab = tab,
                onShowTabs = { showTabSwitcher = true },
                onShowLibrary = { showLibrary = true },
            )
            if (!tab.showingHome && tab.progress in 1..99) {
                LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            } else Spacer(Modifier.height(2.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab.showingHome) {
                    HomePage(
                        bookmarks = controller.store.bookmarks,
                        clipboardSuggestion = clipboardSuggestion,
                        onOpen = { url -> controller.load(url); onClipboardSuggestionHandled() },
                        onDismissSuggestion = onClipboardSuggestionHandled,
                    )
                } else {
                    // One WebView per tab lives in the controller; this only attaches the active one.
                    key(tab.id, tab.generation) {
                        AndroidView(
                            factory = { controller.attach(tab) },
                            modifier = Modifier.fillMaxSize(),
                            onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
                        )
                    }
                }
                if (!tab.showingHome && clipboardSuggestion != null) {
                    ClipboardBanner(clipboardSuggestion, onOpen = { controller.newTab(clipboardSuggestion); onClipboardSuggestionHandled() }, onDismiss = onClipboardSuggestionHandled)
                }
                if (candidates.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = onOpenDownloadSheet,
                        containerColor = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
                    ) {
                        BadgedBox(badge = { if (candidates.size > 1) Badge { Text(candidates.size.toString()) } }) {
                            Icon(Icons.Default.Download, contentDescription = "Download video")
                        }
                    }
                }
            }
            NavigationToolbar(controller, tab)
        }
    }

    if (showTabSwitcher) {
        TabSwitcherDialog(controller = controller, onDismiss = { showTabSwitcher = false })
    }
    if (showLibrary) {
        LibraryDialog(store = controller.store, onOpen = { url -> controller.load(url); showLibrary = false }, onDismiss = { showLibrary = false })
    }
    controller.linkMenu?.let { menu -> LinkMenuDialog(controller, menu) }
}

@Composable
private fun AddressBar(controller: BrowserController, tab: BrowserTab, onShowTabs: () -> Unit, onShowLibrary: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val shownUrl = if (tab.showingHome) "" else tab.url
    var focused by remember { mutableStateOf(false) }
    var input by remember(tab.id, shownUrl) { mutableStateOf(shownUrl) }
    var menuOpen by remember { mutableStateOf(false) }

    Row(Modifier.statusBarsPadding().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = if (focused) input else if (tab.showingHome) "" else UrlUtils.host(tab.url).ifBlank { tab.url },
            onValueChange = { input = it },
            modifier = Modifier.weight(1f).onFocusChanged { state ->
                if (state.isFocused && !focused) input = shownUrl
                focused = state.isFocused
            },
            singleLine = true,
            placeholder = { Text("Search or enter address") },
            leadingIcon = { Icon(if (tab.url.startsWith("https://") && !tab.showingHome) Icons.Default.Lock else Icons.Default.Search, null, Modifier.size(18.dp)) },
            trailingIcon = if (focused && input.isNotEmpty()) {
                { IconButton(onClick = { input = "" }) { Icon(Icons.Default.Close, "Clear") } }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { controller.load(input); focusManager.clearFocus(); controller.focusPage() }),
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        IconButton(onClick = onShowTabs) {
            Surface(shape = RoundedCornerShape(5.dp), color = Color.Transparent,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface), modifier = Modifier.size(22.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(controller.tabs.size.coerceAtMost(99).toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Browser menu") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("New tab") }, onClick = { menuOpen = false; controller.newTab() })
                DropdownMenuItem(text = { Text("Bookmarks & history") }, onClick = { menuOpen = false; onShowLibrary() })
                if (!tab.showingHome && tab.url.isNotBlank()) {
                    DropdownMenuItem(text = { Text("Share page") }, onClick = {
                        menuOpen = false
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url), "Share"))
                    })
                    DropdownMenuItem(text = { Text("Copy link") }, onClick = { menuOpen = false; copyToClipboard(context, tab.url) })
                }
                DropdownMenuItem(
                    text = { Text("Desktop site") },
                    trailingIcon = { Checkbox(checked = tab.desktopMode, onCheckedChange = null) },
                    onClick = { menuOpen = false; controller.toggleDesktopMode() }
                )
            }
        }
    }
}

@Composable
private fun NavigationToolbar(controller: BrowserController, tab: BrowserTab) {
    val onPage = !tab.showingHome && tab.url.isNotBlank()
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { controller.handleBack() }, enabled = controller.canHandleBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            IconButton(onClick = controller::goForward, enabled = onPage && tab.canGoForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
            IconButton(onClick = controller::reloadOrStop, enabled = onPage) {
                if (tab.progress < 100) Icon(Icons.Default.Close, "Stop loading") else Icon(Icons.Default.Refresh, "Reload")
            }
            IconButton(onClick = controller::goHome) { Icon(Icons.Default.Home, "Home") }
            val bookmarked = controller.store.isBookmarked(tab.url)
            IconButton(onClick = controller::toggleBookmark, enabled = onPage) {
                Icon(if (bookmarked) Icons.Default.Star else Icons.Default.StarBorder, if (bookmarked) "Remove bookmark" else "Add bookmark",
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
        }
    }
}

@Composable
private fun HomePage(
    bookmarks: List<Bookmark>,
    clipboardSuggestion: String?,
    onOpen: (String) -> Unit,
    onDismissSuggestion: () -> Unit,
) {
    val context = LocalContext.current
    val sites = (bookmarks + Shortcuts).distinctBy { UrlUtils.host(it.url) }.filterNot { UrlUtils.isBlockedSource(it.url) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(76.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Browse for media", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open a site and play a video. When Fetch finds something it can save, the download button appears.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp
                )
                Spacer(Modifier.height(12.dp))
                if (clipboardSuggestion != null) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Copied link", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(clipboardSuggestion, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { onOpen(clipboardSuggestion) }) { Text("Open") }
                            IconButton(onClick = onDismissSuggestion) { Icon(Icons.Default.Close, "Dismiss") }
                        }
                    }
                } else {
                    OutlinedButton(onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.coerceToText(context)?.toString() else null
                        (UrlUtils.extractUrl(text) ?: text)?.takeIf { it.isNotBlank() }?.let(onOpen)
                    }) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Paste link")
                    }
                }
            }
        }
        items(sites, key = { it.url }) { site -> SiteShortcut(site, onClick = { onOpen(site.url) }) }
    }
}

@Composable
private fun SiteShortcut(site: Bookmark, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(site.title.firstOrNull()?.uppercase() ?: "•", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(site.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ClipboardBanner(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Open copied link? $url", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpen) { Text("Open") }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}

@Composable
private fun TabSwitcherDialog(controller: BrowserController, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Tabs (${controller.tabs.size})")
                Button(onClick = { controller.newTab(); onDismiss() }) { Text("New tab") }
            }
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                items(controller.tabs, key = { it.id }) { tab ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { controller.selectTab(tab.id); onDismiss() },
                        color = if (tab.id == controller.activeTabId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tab.title.ifBlank { "New tab" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(tab.url.ifBlank { "Home" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { controller.closeTab(tab.id) }) { Icon(Icons.Default.Close, "Close tab") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun LibraryDialog(store: BrowserStore, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TabRow(selectedTabIndex = page) {
                Tab(selected = page == 0, onClick = { page = 0 }, text = { Text("Bookmarks") })
                Tab(selected = page == 1, onClick = { page = 1 }, text = { Text("History") })
            }
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                if (page == 0) {
                    if (store.bookmarks.isEmpty()) item { EmptyNote("Tap the star on a page to bookmark it.") }
                    items(store.bookmarks, key = { it.url }) { bookmark ->
                        LibraryRow(bookmark.title, bookmark.url, onClick = { onOpen(bookmark.url) }) {
                            IconButton(onClick = { store.removeBookmark(bookmark) }) { Icon(Icons.Default.Close, "Remove bookmark") }
                        }
                    }
                } else {
                    if (store.history.isEmpty()) item { EmptyNote("Pages you visit appear here.") }
                    items(store.history, key = { "${it.url}|${it.visitedAt}" }) { entry ->
                        LibraryRow(entry.title.ifBlank { UrlUtils.host(entry.url) }, entry.url, onClick = { onOpen(entry.url) }) {}
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { if (page == 1 && store.history.isNotEmpty()) TextButton(onClick = store::clearHistory) { Text("Clear history") } },
    )
}

@Composable
private fun LibraryRow(title: String, url: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(url, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
}

@Composable
private fun LinkMenuDialog(controller: BrowserController, menu: LinkMenu) {
    val context = LocalContext.current
    val dismiss = { controller.linkMenu = null }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(menu.url, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp) },
        text = {
            Column {
                MenuAction("Open in new tab") { controller.newTab(menu.url); dismiss() }
                MenuAction("Open in background tab") { controller.newTab(menu.url, select = false); dismiss() }
                MenuAction(if (menu.isImage) "Download image" else "Download link") { controller.downloadLink(menu); dismiss() }
                MenuAction("Copy link") { copyToClipboard(context, menu.url); dismiss() }
                MenuAction("Share link") {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, menu.url), "Share"))
                    dismiss()
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun MenuAction(label: String, onClick: () -> Unit) {
    Text(label, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp))
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("link", text))
}
