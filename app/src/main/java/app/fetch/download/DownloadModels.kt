package app.fetch.download

import java.util.UUID

enum class TransferPhase { QUEUED, CONNECTING, DOWNLOADING, PAUSED, VERIFYING, COMPLETED, FAILED, CANCELLED }
enum class StreamType { DIRECT, HLS, DASH }

data class MediaVariant(
    val url: String,
    val label: String,
    val detail: String,
    val streamType: StreamType,
)

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sourceUrl: String,
    val mimeType: String? = null,
    val streamType: StreamType = StreamType.DIRECT,
    val requestHeaders: Map<String, String> = emptyMap(),
    val phase: TransferPhase = TransferPhase.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long = 0L,
    val error: String? = null,
    val destination: String? = null,
)

data class MediaCandidate(
    val url: String,
    val title: String,
    val mimeType: String? = null,
    val streamType: StreamType = StreamType.DIRECT,
    val requestHeaders: Map<String, String> = emptyMap(),
    val variants: List<MediaVariant> = emptyList(),
)

fun DownloadItem.progress(): Float = totalBytes?.let {
    if (it <= 0L) 0f else (downloadedBytes.toFloat() / it).coerceIn(0f, 1f)
} ?: 0f

fun Long.asReadableBytes(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "%.1f KB".format(this / 1024f)
    this < 1024L * 1024 * 1024 -> "%.1f MB".format(this / (1024f * 1024))
    else -> "%.2f GB".format(this / (1024f * 1024 * 1024))
}
