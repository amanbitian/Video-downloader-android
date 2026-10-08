package app.fetch.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fetch.download.DownloadCoordinator
import app.fetch.download.DownloadItem
import app.fetch.download.DownloadService
import app.fetch.download.MediaActions
import app.fetch.download.MediaKind
import app.fetch.download.TransferPhase
import app.fetch.download.asEstimatedSize
import app.fetch.download.asReadableBytes
import app.fetch.download.hasKnownProgress
import app.fetch.download.kind
import app.fetch.download.progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class DownloadFilter(val label: String, val kind: MediaKind?) {
    ALL("All", null), VIDEO("Videos", MediaKind.VIDEO), AUDIO("Audio", MediaKind.AUDIO), OTHER("Files", MediaKind.OTHER)
}

@Composable
fun DownloadsScreen(downloads: List<DownloadItem>, onBack: () -> Unit) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }
    val kinds = remember(downloads) { downloads.associate { it.id to it.kind() } }
    val visible = downloads.filter { filter.kind == null || kinds[it.id] == filter.kind }

    ScreenFrame(
        title = "Downloads",
        onBack = onBack,
        actions = {
            if (downloads.any { it.phase == TransferPhase.COMPLETED || it.phase == TransferPhase.CANCELLED }) {
                IconButton(onClick = DownloadCoordinator::clearFinished) { Icon(Icons.Default.DeleteSweep, "Clear finished from list") }
            }
        },
    ) {
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadFilter.entries.forEach { option ->
                FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.label) })
            }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("No downloads yet", fontWeight = FontWeight.Medium)
                    Text("Play a video in the browser, then tap the download button.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            items(visible, key = { it.id }) { item ->
                DownloadRow(
                    item = item,
                    kind = kinds[item.id] ?: MediaKind.OTHER,
                    onCommand = { action -> DownloadCoordinator.command(context, action, item.id) },
                    onOpen = { MediaActions.open(context, item, visible) },
                    onShare = { MediaActions.share(context, item) },
                    onRemove = { DownloadCoordinator.remove(context, item.id, deleteFile = false) },
                    onDelete = { pendingDelete = item },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
      }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete file?") },
            text = { Text("\"${item.title}\" will be removed from your device.") },
            confirmButton = { TextButton(onClick = { DownloadCoordinator.remove(context, item.id, deleteFile = true); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    kind: MediaKind,
    onCommand: (String) -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    val completed = item.phase == TransferPhase.COMPLETED
    val failed = item.phase == TransferPhase.FAILED
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(enabled = completed, onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(item, kind)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.phase.isRunning) {
                Spacer(Modifier.height(8.dp))
                if (item.phase == TransferPhase.DOWNLOADING && item.hasKnownProgress()) {
                    LinearProgressIndicator(progress = { item.progress() }, modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.primaryContainer)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                detailText(item, kind),
                color = if (failed) MaterialTheme.colorScheme.error else muted,
                fontSize = 13.sp,
                fontFamily = if (item.phase == TransferPhase.DOWNLOADING) FontFamily.Monospace else FontFamily.Default,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        when {
            item.phase.isRunning -> {
                IconButton(onClick = { onCommand(DownloadService.ACTION_PAUSE) }) { Icon(Icons.Default.Pause, "Pause") }
                IconButton(onClick = { onCommand(DownloadService.ACTION_CANCEL) }) { Icon(Icons.Default.Close, "Cancel download") }
            }
            item.phase == TransferPhase.PAUSED || failed || item.phase == TransferPhase.CANCELLED -> {
                IconButton(onClick = { onCommand(DownloadService.ACTION_RETRY) }) { Icon(Icons.Default.Refresh, "Retry") }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove from list") }
            }
            else -> RowMenu(onOpen, onShare, onRemove, onDelete)
        }
    }
}

@Composable
private fun RowMenu(onOpen: () -> Unit, onShare: () -> Unit, onRemove: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "More actions") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { expanded = false; onOpen() })
            DropdownMenuItem(text = { Text("Share") }, onClick = { expanded = false; onShare() })
            DropdownMenuItem(text = { Text("Remove from list") }, onClick = { expanded = false; onRemove() })
            DropdownMenuItem(text = { Text("Delete file") }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun Thumbnail(item: DownloadItem, kind: MediaKind) {
    val context = LocalContext.current
    var thumbnail by remember(item.destination) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.destination) {
        if (item.destination != null && (kind == MediaKind.VIDEO || kind == MediaKind.IMAGE)) {
            thumbnail = withContext(Dispatchers.IO) { MediaActions.thumbnail(context, item, 192)?.asImageBitmap() }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(56.dp)) {
        val image = thumbnail
        if (image != null) {
            Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
        } else Box(contentAlignment = Alignment.Center) {
            val icon = when (kind) {
                MediaKind.VIDEO -> Icons.Default.Movie
                MediaKind.AUDIO -> Icons.Default.Audiotrack
                MediaKind.IMAGE -> Icons.Default.Image
                MediaKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
            }
            Icon(icon, null, tint = if (item.phase == TransferPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}

private fun detailText(item: DownloadItem, kind: MediaKind): String = when (item.phase) {
    TransferPhase.QUEUED -> "Queued · waiting for a free slot"
    TransferPhase.CONNECTING -> item.error ?: "Connecting…"
    TransferPhase.DOWNLOADING -> buildString {
        append(item.downloadedBytes.asReadableBytes())
        when {
            item.totalBytes != null -> append(" / ${item.totalBytes.asReadableBytes()}")
            item.estimatedBytes != null -> append(" / ${item.estimatedBytes.asEstimatedSize()}")
        }
        if (item.totalBytes == null && item.segmentCount > 0) append(" · ${(item.progress() * 100).toInt()}%")
        append(" · ${item.bytesPerSecond.asReadableBytes()}/s")
    }
    TransferPhase.VERIFYING -> "Saving to your device…"
    TransferPhase.PROCESSING -> if (item.audioKey != null) "Merging video and audio…" else "Converting to a playable file…"
    TransferPhase.COMPLETED -> item.error ?: listOfNotNull(item.totalBytes?.asReadableBytes(), if (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) "Tap to play" else "Tap to open").joinToString(" · ")
    TransferPhase.FAILED -> item.error ?: "Download failed"
    TransferPhase.PAUSED -> item.error ?: "Paused at ${item.downloadedBytes.asReadableBytes()}"
    TransferPhase.CANCELLED -> "Cancelled"
}
