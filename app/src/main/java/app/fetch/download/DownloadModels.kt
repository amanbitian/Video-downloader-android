package app.fetch.download

import java.util.UUID

enum class TransferPhase {
    QUEUED, CONNECTING, DOWNLOADING, PAUSED, VERIFYING, COMPLETED, FAILED, CANCELLED;

    /** Holding a network connection or a transfer slot. */
    val isActive: Boolean get() = this == CONNECTING || this == DOWNLOADING || this == VERIFYING

    /** Owned by the download service: active, or waiting for a free slot. */
    val isRunning: Boolean get() = isActive || this == QUEUED
}

enum class StreamType { DIRECT, HLS, DASH }
enum class MediaKind { VIDEO, AUDIO, IMAGE, OTHER }

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
    /** ETag or Last-Modified of the partial file, sent as If-Range so a changed file is never appended to. */
    val validator: String? = null,
    /** HLS checkpoint: segments fully written to the .part file and the byte length at that boundary. */
    val segmentIndex: Int = 0,
    val segmentOffset: Long = 0L,
    val segmentCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

data class MediaCandidate(
    val url: String,
    val title: String,
    val mimeType: String? = null,
    val streamType: StreamType = StreamType.DIRECT,
    val requestHeaders: Map<String, String> = emptyMap(),
    val variants: List<MediaVariant> = emptyList(),
    val pageUrl: String? = null,
    /** The user asked for this file (download link, long-press, page download) rather than it being sniffed. */
    val explicit: Boolean = false,
    /** URL has no media hint; probe its Content-Type before offering it. */
    val probe: Boolean = false,
    val sizeBytes: Long? = null,
    /** HLS manifest has been fetched; [variants] is final and [note] explains an empty result. */
    val resolved: Boolean = false,
    val note: String? = null,
)

fun DownloadItem.hasKnownProgress(): Boolean = (totalBytes ?: 0L) > 0L || segmentCount > 0

fun DownloadItem.progress(): Float = when {
    (totalBytes ?: 0L) > 0L -> (downloadedBytes.toFloat() / totalBytes!!).coerceIn(0f, 1f)
    segmentCount > 0 -> (segmentIndex.toFloat() / segmentCount).coerceIn(0f, 1f)
    else -> 0f
}

fun DownloadItem.kind(): MediaKind = MediaFiles.kindOf(MediaFiles.resolveMime(streamType, sourceUrl, mimeType))

fun Long.asReadableBytes(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "%.1f KB".format(this / 1024f)
    this < 1024L * 1024 * 1024 -> "%.1f MB".format(this / (1024f * 1024))
    else -> "%.2f GB".format(this / (1024f * 1024 * 1024))
}
