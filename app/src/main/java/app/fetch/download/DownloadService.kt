package app.fetch.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** A direct-media transfer engine with range-based restart/resume for servers that support it. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val transferSlots = Semaphore(MAX_CONCURRENT_TRANSFERS)
    private val lastNotificationUpdate = AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_START, ACTION_RETRY -> start(id)
            ACTION_PAUSE -> pause(id)
            ACTION_CANCEL -> cancel(id)
        }
        return START_NOT_STICKY
    }

    private fun start(id: String) {
        if (jobs[id]?.isCompleted == false) return
        DownloadCoordinator.find(id) ?: return
        DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.CONNECTING, error = null, bytesPerSecond = 0L) }
        refreshNotification(force = true)
        val job = scope.launch(start = CoroutineStart.LAZY) { transferSlots.withPermit { transfer(id) } }
        if (jobs.putIfAbsent(id, job) == null) job.start() else job.cancel()
    }

    private fun pause(id: String) {
        jobs[id]?.cancel()
        DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.PAUSED) }
        refreshNotification()
    }

    private fun cancel(id: String) {
        jobs[id]?.cancel()
        DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.CANCELLED, error = null) }
        refreshNotification()
    }

    private suspend fun transfer(id: String) {
        val item = DownloadCoordinator.find(id) ?: return
        val part = File(cacheDir, "$id.part")
        var connection: HttpURLConnection? = null
        currentCoroutineContext()[Job]?.invokeOnCompletion { connection?.disconnect() }
        try {
            when (item.streamType) {
                StreamType.DASH -> error("DASH downloads are not supported yet.")
                StreamType.HLS -> {
                    transferHls(item, id, part)
                    return
                }
                StreamType.DIRECT -> Unit
            }
            DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.CONNECTING, error = null, downloadedBytes = part.length()) }
            refreshNotification()
            val activeConnection = (URL(item.sourceUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                item.requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                if (item.requestHeaders["User-Agent"] == null) setRequestProperty("User-Agent", "Fetch/1.0 Android")
                if (part.exists() && part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            connection = activeConnection
            val response = activeConnection.responseCode
            if (response !in 200..299 && response != 206) error("Server returned HTTP $response")
            val append = response == HttpURLConnection.HTTP_PARTIAL && part.exists()
            if (!append && part.exists()) part.delete()
            val length = activeConnection.contentLengthLong.takeIf { it >= 0 }?.let { if (append) it + part.length() else it }
            DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.DOWNLOADING, totalBytes = length, downloadedBytes = part.length()) }
            refreshNotification()
            var received = part.length()
            var checkpoint = received
            var tickBytes = received
            var tickAt = System.currentTimeMillis()
            activeConnection.inputStream.use { input ->
                FileOutputStream(part, append).buffered(64 * 1024).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        val now = System.currentTimeMillis()
                        if (now - tickAt >= 1000L) {
                            val speed = ((received - tickBytes) * 1000L / max(now - tickAt, 1L))
                            DownloadCoordinator.update(id) { it.copy(downloadedBytes = received, bytesPerSecond = speed) }
                            refreshNotification()
                            tickBytes = received; tickAt = now
                        }
                        if (received - checkpoint >= 4L * 1024 * 1024) { output.flush(); checkpoint = received }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.VERIFYING, downloadedBytes = received) }
            refreshNotification()
            val destination = finalizeToMediaStore(item, part)
            part.delete()
            DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.COMPLETED, downloadedBytes = received, destination = destination, bytesPerSecond = 0L) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!currentCoroutineContext().isActive) throw CancellationException()
            DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.FAILED, error = failure.message ?: "The transfer stopped unexpectedly.", bytesPerSecond = 0L) }
        } finally {
            jobs.remove(id)
            connection?.disconnect()
            if (DownloadCoordinator.find(id)?.phase == TransferPhase.CANCELLED) part.delete()
            refreshNotification(force = true)
        }
    }

    /** Downloads unencrypted transport-stream HLS in playlist order. fMP4/encrypted manifests remain explicitly unsupported. */
    private suspend fun transferHls(item: DownloadItem, id: String, part: File) {
        DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.CONNECTING, error = null, downloadedBytes = 0L, totalBytes = null) }
        refreshNotification(force = true)
        val manifest = openConnection(item.sourceUrl, item.requestHeaders).useText()
        if (manifest.contains("#EXT-X-KEY")) error("This HLS stream is encrypted and cannot be downloaded.")
        if (manifest.contains("#EXT-X-MAP")) error("This fMP4 HLS stream requires remuxing and is not supported yet.")
        val segments = manifest.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }.toList()
        if (segments.isEmpty()) error("The HLS playlist has no downloadable segments.")
        part.delete()
        var received = 0L
        var tickBytes = 0L
        var tickAt = System.currentTimeMillis()
        FileOutputStream(part, false).buffered(64 * 1024).use { output ->
            segments.forEachIndexed { index, segment ->
                currentCoroutineContext().ensureActive()
                val segmentUrl = URL(URL(item.sourceUrl), segment).toString()
                val segmentConnection = openConnection(segmentUrl, item.requestHeaders)
                try {
                    if (segmentConnection.responseCode !in 200..299) error("Segment ${index + 1} returned HTTP ${segmentConnection.responseCode}")
                    segmentConnection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            val now = System.currentTimeMillis()
                            if (now - tickAt >= 1000L) {
                                val speed = (received - tickBytes) * 1000L / max(now - tickAt, 1L)
                                DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.DOWNLOADING, downloadedBytes = received, bytesPerSecond = speed) }
                                refreshNotification(); tickAt = now; tickBytes = received
                            }
                        }
                    }
                } finally {
                    segmentConnection.disconnect()
                }
            }
        }
        DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.VERIFYING, downloadedBytes = received) }
        val destination = finalizeToMediaStore(item, part)
        part.delete()
        DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.COMPLETED, downloadedBytes = received, destination = destination, bytesPerSecond = 0L) }
    }

    private fun openConnection(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (headers["User-Agent"] == null) setRequestProperty("User-Agent", "Fetch/1.0 Android")
        }

    private fun HttpURLConnection.useText(): String = try {
        if (responseCode !in 200..299) error("Server returned HTTP $responseCode")
        inputStream.bufferedReader().use { it.readText() }
    } finally { disconnect() }

    private fun finalizeToMediaStore(item: DownloadItem, file: File): String {
        val mime = if (item.streamType == StreamType.HLS) "video/mp2t" else item.mimeType ?: guessMime(item.sourceUrl)
        val collection = if (mime.startsWith("audio/")) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val name = if (item.title.contains('.')) item.title else "${item.title}.${extensionFor(mime)}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (mime.startsWith("audio/")) "Music/Fetch" else "Movies/Fetch")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values) ?: error("Could not create the media file")
        contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } } ?: error("Could not write the media file")
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri.toString()
    }

    private fun refreshNotification(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationUpdate.get() < NOTIFICATION_INTERVAL_MS) return
        lastNotificationUpdate.set(now)
        val active = DownloadCoordinator.downloads.value.filter { it.phase in setOf(TransferPhase.CONNECTING, TransferPhase.DOWNLOADING, TransferPhase.VERIFYING) }
        if (active.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); return }
        val first = active.first()
        val percentage = (first.progress() * 100).toInt()
        val pause = PendingIntent.getService(this, 31, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_ID, first.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${active.size} file${if (active.size == 1) "" else "s"}")
            .setContentText("${first.title} · $percentage%")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percentage, first.totalBytes == null)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pause)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Active downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_START = "app.fetch.START"; const val ACTION_PAUSE = "app.fetch.PAUSE"; const val ACTION_CANCEL = "app.fetch.CANCEL"; const val ACTION_RETRY = "app.fetch.RETRY"; const val EXTRA_ID = "download_id"
        private const val CHANNEL = "downloads"; private const val NOTIFICATION_ID = 17
        private const val MAX_CONCURRENT_TRANSFERS = 3
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private fun guessMime(url: String) = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) { "mp3", "m4a", "aac", "ogg" -> "audio/mpeg"; "webm" -> "video/webm"; "m3u8" -> "video/mp2t"; else -> "video/mp4" }
        private fun extensionFor(mime: String) = when { mime == "video/webm" -> "webm"; mime == "video/mp2t" -> "ts"; mime.startsWith("audio/") -> "mp3"; else -> "mp4" }
    }
}
