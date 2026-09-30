package app.fetch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fetch.download.Codecs
import app.fetch.download.DownloadCoordinator
import app.fetch.download.MediaCandidate
import app.fetch.download.MediaFiles
import app.fetch.download.MediaVariant
import app.fetch.download.StreamType
import app.fetch.download.asEstimatedSize
import app.fetch.download.asReadableBytes

/** A downloadable choice: one quality of a video (or the single file), shown as "1080p  MP4  ~94 MB". */
private data class DownloadOption(val variant: MediaVariant?, val label: String, val format: String, val size: String?, val downloaded: Boolean, val extension: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadBottomSheet(
    candidates: List<MediaCandidate>,
    onDismiss: () -> Unit,
    onDownload: (MediaCandidate, MediaVariant?, String) -> Unit,
) {
    // Fully expanded from the start, so rows that arrive late don't shift the controls under the user's finger.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        var selectedKey by remember { mutableStateOf(keyOf(candidates.first())) }
        val selected = candidates.firstOrNull { keyOf(it) == selectedKey } ?: candidates.first()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (candidates.size > 1) "Download · ${candidates.size} videos" else "Download video", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }
            if (candidates.size == 1) {
                VideoHeader(selected, isSelected = false, onClick = null)
            } else {
                candidates.forEach { candidate ->
                    VideoHeader(candidate, isSelected = keyOf(candidate) == keyOf(selected), onClick = { selectedKey = keyOf(candidate) })
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            VideoOptions(selected, onDownload)
        }
    }
}

@Composable
private fun VideoHeader(candidate: MediaCandidate, isSelected: Boolean, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(12.dp)
    val modifier = Modifier.fillMaxWidth().clip(shape)
        .let { if (isSelected) it.background(MaterialTheme.colorScheme.primaryContainer) else it }
        .let { if (onClick != null) it.selectable(selected = isSelected, onClick = onClick, role = Role.RadioButton) else it }
        .padding(if (onClick != null) 8.dp else 0.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.width(68.dp).height(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                RemoteImage(candidate.thumbnailUrl, Modifier.width(68.dp).height(44.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(candidate.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(candidate.durationSeconds?.let(::formatDuration), candidate.note?.takeIf { candidate.variants.isEmpty() })
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun VideoOptions(candidate: MediaCandidate, onDownload: (MediaCandidate, MediaVariant?, String) -> Unit) {
    val options = remember(candidate) { optionsFor(candidate) }
    if (options.isEmpty()) {
        Text(candidate.note ?: "No downloadable version of this video was found.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var selectedIndex by remember(candidate.groupKey ?: candidate.url) { mutableIntStateOf(0) }
    val option = options[selectedIndex.coerceIn(options.indices)]
    options.forEachIndexed { index, it ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                .selectable(selected = index == selectedIndex, onClick = { selectedIndex = index }, role = Role.RadioButton)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = index == selectedIndex, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
            // Columns grow with large system text instead of wrapping; the downloaded mark is an icon, which can't wrap.
            Text(it.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 72.dp).padding(end = 8.dp))
            Text(it.format, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 48.dp).padding(end = 8.dp))
            Box(Modifier.weight(1f)) {
                if (it.downloaded) Icon(Icons.Default.CheckCircle, "Already downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
            Text(it.size.orEmpty(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
    }
    Spacer(Modifier.height(12.dp))
    val extension = option.extension
    var name by remember(candidate.groupKey ?: candidate.url) { mutableStateOf(candidate.title.removeSuffix(".$extension").trim()) }
    OutlinedTextField(
        value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("File name") }, suffix = { Text(".$extension") }, textStyle = TextStyle(fontSize = 14.sp),
        shape = RoundedCornerShape(12.dp),
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = { onDownload(candidate, option.variant, name) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
        Text("Download", fontSize = 15.sp)
    }
}

private fun optionsFor(candidate: MediaCandidate): List<DownloadOption> {
    if (candidate.variants.isEmpty()) {
        if (candidate.streamType != StreamType.DIRECT) return emptyList()
        val mime = MediaFiles.resolveMime(candidate.streamType, candidate.url, candidate.mimeType)
        val format = (MediaFiles.extensionFor(mime) ?: MediaFiles.extensionOf(candidate.url).ifBlank { "file" }).uppercase()
        return listOf(DownloadOption(null, "Original", format, candidate.sizeBytes?.asReadableBytes(), DownloadCoordinator.isAlreadyDownloaded(candidate.url), format.lowercase()))
    }
    return candidate.variants.map { variant ->
        val format = when {
            variant.streamType == StreamType.DIRECT -> MediaFiles.extensionOf(variant.url).uppercase().ifBlank { "MP4" }
            variant.videoKey == null && variant.streamType == StreamType.DASH -> "M4A"
            listOf("VP9", "VP8").any { it in variant.detail } -> Codecs.Container.WEBM.name
            else -> Codecs.Container.MP4.name
        }
        val size = variant.estimatedBytes?.let { if (variant.streamType == StreamType.DIRECT && variant.audioKey == null) it.asReadableBytes() else it.asEstimatedSize() }
        // Alternatives that differ only by format (a <video>'s MP4 and OGG sources) are labelled by format alone.
        DownloadOption(variant, variant.label, if (variant.label.equals(format, ignoreCase = true)) "" else format, size,
            DownloadCoordinator.isAlreadyDownloaded(variant.url, variant.videoKey), format.lowercase())
    }
}

private fun keyOf(candidate: MediaCandidate) = candidate.groupKey ?: candidate.url

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
}
