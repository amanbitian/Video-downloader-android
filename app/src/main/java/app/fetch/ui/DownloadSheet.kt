package app.fetch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fetch.download.DownloadCoordinator
import app.fetch.download.MediaCandidate
import app.fetch.download.MediaFiles
import app.fetch.download.MediaKind
import app.fetch.download.MediaVariant
import app.fetch.download.StreamType
import app.fetch.download.asReadableBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadBottomSheet(
    candidates: List<MediaCandidate>,
    onDismiss: () -> Unit,
    onDownload: (MediaCandidate, MediaVariant?) -> Unit,
) {
    // Fully expanded from the start, so cards that arrive late don't shift the buttons under the user's finger.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(if (candidates.size > 1) "Download · ${candidates.size} videos found" else "Download", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 520.dp)) {
                items(candidates, key = { it.groupKey ?: it.url }) { candidate -> CandidateCard(candidate, onDownload) }
            }
        }
    }
}

@Composable
private fun CandidateCard(candidate: MediaCandidate, onDownload: (MediaCandidate, MediaVariant?) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val isStream = candidate.streamType != StreamType.DIRECT
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (candidate.thumbnailUrl != null) {
                    RemoteImage(candidate.thumbnailUrl, Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(candidate.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val fileName = candidate.url.substringBefore('?').substringAfterLast('/')
                    val meta = listOfNotNull(
                        fileName.takeIf { it.isNotBlank() && it != candidate.title && candidate.variants.isEmpty() && !isStream },
                        candidate.durationSeconds?.let(::formatDuration),
                        candidate.sizeBytes?.takeIf { candidate.variants.isEmpty() }?.asReadableBytes(),
                        "Already downloaded".takeIf { candidate.variants.isEmpty() && DownloadCoordinator.isAlreadyDownloaded(candidate.url) },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                isStream && !candidate.resolved -> Text("Finding available qualities…", color = muted, fontSize = 13.sp)
                candidate.variants.isNotEmpty() -> candidate.variants.forEachIndexed { index, variant ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onDownload(candidate, variant) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(variant.label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                val tags = listOfNotNull(
                                    "Best quality".takeIf { index == 0 && candidate.variants.size > 1 },
                                    "Downloaded".takeIf { DownloadCoordinator.isAlreadyDownloaded(variant.url, variant.videoKey) },
                                ).joinToString(" · ")
                                if (tags.isNotEmpty()) Text("  $tags", color = muted, fontSize = 12.sp)
                            }
                            if (variant.detail.isNotBlank()) Text(variant.detail, color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.Download, "Download ${variant.label}", tint = MaterialTheme.colorScheme.primary)
                    }
                    if (index < candidate.variants.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                isStream -> Text(candidate.note ?: "No downloadable quality found.", color = muted, fontSize = 13.sp)
                else -> {
                    val label = when (MediaFiles.kindOf(MediaFiles.resolveMime(candidate.streamType, candidate.url, candidate.mimeType))) {
                        MediaKind.VIDEO -> "Download video"
                        MediaKind.AUDIO -> "Download audio"
                        MediaKind.IMAGE -> "Download image"
                        MediaKind.OTHER -> "Download file"
                    }
                    Button(onClick = { onDownload(candidate, null) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
}
