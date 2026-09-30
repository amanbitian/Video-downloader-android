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
    fun open(context: Context, item: DownloadItem) {
        val uri = item.destination?.let(Uri::parse) ?: return
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: MediaFiles.resolveMime(item.streamType, item.sourceUrl, item.mimeType)
        when (MediaFiles.kindOf(mime)) {
            MediaKind.VIDEO, MediaKind.AUDIO -> PlayerActivity.start(context, uri, item.title)
            else -> launch(context, Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime), "Open with")
        }
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
