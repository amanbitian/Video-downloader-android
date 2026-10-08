package app.fetch.download

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.widget.Toast
import app.fetch.player.PlayerActivity

/** What the user can do with a finished download. */
object MediaActions {
    /**
     * Opens a finished download. Videos and audio play in the built-in player with the other finished downloads of the
     * same kind as a playlist (in list order), so next / previous and auto-advance work like a media library.
     */
    fun open(context: Context, item: DownloadItem, all: List<DownloadItem> = listOf(item)) {
        val uri = item.destination?.let(Uri::parse) ?: return
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: MediaFiles.resolveMime(item.streamType, item.sourceUrl, item.mimeType)
        val kind = MediaFiles.kindOf(mime)
        if (kind != MediaKind.VIDEO && kind != MediaKind.AUDIO) {
            launch(context, Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime), "Open with")
            return
        }
        val playlist = all.filter { it.phase == TransferPhase.COMPLETED && it.destination != null && (it.id == item.id || it.kind() == kind) }
            .ifEmpty { listOf(item) }
        PlayerActivity.start(context, playlist.map { Uri.parse(it.destination) to it.title }, playlist.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
    }

    fun share(context: Context, item: DownloadItem) {
        val uri = item.destination?.let(Uri::parse) ?: return
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
        launch(context, Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri), "Share")
    }

    /** Deletes a file this app published. Files from a previous install aren't ours to delete; the row is still removed. */
    fun deleteFile(context: Context, item: DownloadItem): Boolean {
        val uri = item.destination?.let(Uri::parse) ?: return false
        return runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    fun thumbnail(context: Context, item: DownloadItem, sizePx: Int): Bitmap? {
        val uri = item.destination?.let(Uri::parse) ?: return null
        return runCatching { context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null) }.getOrNull()
    }

    private fun launch(context: Context, intent: Intent, title: String) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(chooser) } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }
}
