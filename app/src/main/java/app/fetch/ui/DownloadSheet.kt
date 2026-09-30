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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Download", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 460.dp)) {
                items(candidates, key = { it.url }) { candidate -> CandidateCard(candidate, onDownload) }
            }
        }
    }
}

@Composable
private fun CandidateCard(candidate: MediaCandidate, onDownload: (MediaCandidate, MediaVariant?) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(candidate.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val fileName = candidate.url.substringBefore('?').substringAfterLast('/')
            val meta = listOfNotNull(
                fileName.takeIf { it.isNotBlank() && it != candidate.title },
                candidate.sizeBytes?.asReadableBytes(),
                "Already downloaded".takeIf { DownloadCoordinator.isAlreadyDownloaded(candidate.url) },
            ).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            when {
                candidate.streamType == StreamType.HLS && !candidate.resolved ->
                    Text("Resolving available qualities…", color = muted, fontSize = 13.sp)
                candidate.streamType == StreamType.HLS && candidate.variants.isEmpty() ->
                    Text(candidate.note ?: "No downloadable quality found.", color = muted, fontSize = 13.sp)
                candidate.streamType == StreamType.HLS -> candidate.variants.forEach { variant ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onDownload(candidate, variant) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(variant.label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            Text(variant.detail, color = muted, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.Download, "Download ${variant.label}", tint = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                candidate.streamType == StreamType.DASH ->
                    Text("DASH streams are detected but can't be downloaded yet.", color = muted, fontSize = 13.sp)
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
