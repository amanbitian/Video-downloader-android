package app.fetch.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import app.fetch.MainActivity
import app.fetch.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class HttpStatusException(val code: Int, message: String = "Server returned HTTP $code") : IOException(message)

/** A direct-media and HLS transfer engine with validated range resume, HLS checkpoints and automatic retries. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private lateinit var transferSlots: Semaphore
    private val lastNotificationUpdate = AtomicLong(0L)
    private val latestStartId = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        // Notification actions can start the service after the app process has died.
        DownloadCoordinator.initialize(this)
        AppSettings.initialize(this)
        transferSlots = Semaphore(AppSettings.values.value.maxConcurrentDownloads)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every command arrives through startForegroundService(), so promote before any early return.
        startForeground(NOTIFICATION_ID, progressNotification())
        val id = intent?.getStringExtra(EXTRA_ID)
        if (id != null) when (intent.action) {
            ACTION_START, ACTION_RETRY -> start(id)
            ACTION_PAUSE -> pause(id)
            ACTION_CANCEL -> cancel(id)
        }
        // Published only after start() registered its job, so a worker that reads this id also sees the job (see refreshNotification).
        latestStartId.set(startId)
        refreshNotification(force = true)
        return START_NOT_STICKY
    }

    private fun start(id: String) {
        val existing = jobs[id]
        if (existing != null && !existing.isCompleted) return
        val item = DownloadCoordinator.find(id) ?: return
        if (item.phase == TransferPhase.COMPLETED) return
        DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.QUEUED, error = null, bytesPerSecond = 0L) }
        val job = scope.launch(start = CoroutineStart.LAZY) { transferSlots.withPermit { run(id) } }
        jobs[id] = job
        job.start()
    }

    private fun pause(id: String) {
        jobs.remove(id)?.cancel()
        DownloadCoordinator.update(id) { if (it.phase.isRunning) it.copy(phase = TransferPhase.PAUSED, error = null, bytesPerSecond = 0L) else it }
    }

    private fun cancel(id: String) {
        val job = jobs.remove(id)
        job?.cancel()
        DownloadCoordinator.update(id) { it.copy(phase = TransferPhase.CANCELLED, error = null, bytesPerSecond = 0L) }
        // A running job deletes its own files once its streams are closed.
        if (job == null) DownloadCoordinator.deleteParts(this, id)
    }

    private suspend fun run(id: String) {
        val self = currentCoroutineContext()[Job]
        val part = DownloadCoordinator.partFile(this, id)
        var attempt = 0
        try {
            while (true) {
                val item = DownloadCoordinator.find(id) ?: return
                if (AppSettings.values.value.wifiOnly && isMeteredNetwork()) {
                    progress(id) { it.copy(phase = TransferPhase.PAUSED, error = "Paused: Wi-Fi only downloads is on in Settings.", bytesPerSecond = 0L) }
                    return
                }
                try {
                    ensureFreeSpace(item)
                    if (item.streamType == StreamType.DIRECT && item.audioKey == null) transferDirect(item, id, part) else transferTracks(item, id)
                    return
                } catch (failure: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (!isRetryable(failure) || attempt >= MAX_RETRIES) throw failure
                    attempt++
                    progress(id) { it.copy(phase = TransferPhase.CONNECTING, bytesPerSecond = 0L, error = "Connection lost. Retrying ($attempt/$MAX_RETRIES)…") }
                    refreshNotification()
                    delay(RETRY_BASE_DELAY_MS shl (attempt - 1))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!currentCoroutineContext().isActive) throw CancellationException()
            progress(id) { it.copy(phase = TransferPhase.FAILED, error = failure.message ?: "The transfer stopped unexpectedly.", bytesPerSecond = 0L) }
        } finally {
            self?.let { jobs.remove(id, it) }
            val latest = DownloadCoordinator.find(id)
            if (latest == null || latest.phase == TransferPhase.CANCELLED) DownloadCoordinator.deleteParts(this, id)
            // Progress ticks are throttled; show the real resume point, not the last tick.
            else if (latest.phase == TransferPhase.PAUSED && latest.streamType == StreamType.DIRECT && latest.audioKey == null) {
                val length = part.length()
                DownloadCoordinator.update(id) { it.copy(downloadedBytes = length) }
            }
            refreshNotification(force = true)
            latest?.let(::notifyFinished)
        }
    }

    private suspend fun transferDirect(item: DownloadItem, id: String, part: File) {
        val resumeFrom = part.length()
        progress(id) { it.copy(phase = TransferPhase.CONNECTING, downloadedBytes = resumeFrom) }
        refreshNotification()
        val connection = openConnection(item.sourceUrl, item.requestHeaders).apply {
            if (resumeFrom > 0) {
                setRequestProperty("Range", "bytes=$resumeFrom-")
                // If the file changed since the partial download, the server answers 200 with the whole new file.
                item.validator?.let { setRequestProperty("If-Range", it) }
            }
        }
        val disconnectOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { connection.disconnect() }
        try {
            val code = connection.responseCode
            if (code == 416 && resumeFrom > 0) {
                val total = MediaFiles.contentRangeTotal(connection.getHeaderField("Content-Range")) ?: item.totalBytes
                if (total != null && resumeFrom >= total) {
                    progress(id) { it.copy(totalBytes = total) }
                    publish(id, part, resumeFrom)
                    return
                }
                part.delete()
                throw IOException("The server rejected the resume request; restarting.")
            }
            if (code !in 200..299) throw HttpStatusException(code)
            val append = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
            if (resumeFrom > 0) Log.i(TAG, if (append) "Resumed $id at $resumeFrom bytes" else "Server sent a full response (HTTP $code); restarting $id")
            if (!append) part.delete()
            val start = if (append) resumeFrom else 0L
            val length = connection.contentLengthLong.takeIf { it >= 0 }?.let { it + start }
            val validator = connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") } ?: connection.getHeaderField("Last-Modified")
            val mime = MediaFiles.resolveMime(StreamType.DIRECT, item.sourceUrl, item.mimeType, connection.contentType)
            progress(id) {
                it.copy(phase = TransferPhase.DOWNLOADING, totalBytes = length ?: it.totalBytes, downloadedBytes = start, mimeType = mime,
                    validator = validator ?: it.validator.takeIf { append }, error = null)
            }
            refreshNotification()
            val meter = SpeedMeter(start)
            connection.inputStream.use { input ->
                FileOutputStream(part, append).buffered(BUFFER_SIZE).use { output -> pump(input, output, id, meter) }
            }
            publish(id, part, meter.received)
        } finally {
            disconnectOnCancel?.dispose()
            connection.disconnect()
        }
    }

    /** One elementary stream fetched to its own file: a complete file, or an optional init segment followed by media segments. */
    private class Track(val initUrl: String?, val urls: List<String>, val mime: String?) {
        val isSingleFile get() = initUrl == null && urls.size == 1
        /** Progress units: segments, or 1 for a single file. */
        val units get() = if (isSingleFile) 1 else urls.size
    }

    /** Re-resolves what to fetch from the manifest on every (re)start, so expiring segment URLs are always fresh. */
    private fun planTracks(item: DownloadItem): List<Track> = when (item.streamType) {
        StreamType.HLS -> listOfNotNull(item.sourceUrl, item.audioKey).map { url ->
            val playlist = HlsResolver.mediaPlaylist(url, HlsResolver.fetchText(url, item.requestHeaders))
            Track(playlist.initUrl, playlist.segments, if (playlist.isFragmentedMp4) "video/mp4" else "video/mp2t")
        }
        StreamType.DASH -> {
            val manifest = DashManifest.parse(item.sourceUrl, HlsResolver.fetchText(item.sourceUrl, item.requestHeaders))
            if (manifest.isLive) throw UnsupportedStreamException("Live streams can't be downloaded.")
            listOfNotNull(item.videoKey, item.audioKey).map { key ->
                val rep = manifest.representation(key) ?: throw UnsupportedStreamException("This quality is no longer offered by the stream.")
                if (rep.isProtected) throw UnsupportedStreamException("This video is DRM-protected and can't be downloaded.")
                Track(rep.initUrl, rep.segmentUrls, rep.mimeType)
            }.ifEmpty { throw UnsupportedStreamException("Pick a quality from the download sheet to download this stream.") }
        }
        StreamType.DIRECT -> listOfNotNull(item.sourceUrl, item.audioKey).map { Track(null, listOf(it), null) }
    }

    /**
     * Fetches every track (checkpointing per segment), then merges them into one playable file. Checkpoints survive
     * process death: finished tracks are kept and the current one resumes at its last complete segment.
     */
    private suspend fun transferTracks(item: DownloadItem, id: String) {
        progress(id) { it.copy(phase = TransferPhase.CONNECTING) }
        refreshNotification()
        val tracks = planTracks(item)
        val totalUnits = tracks.sumOf { it.units }
        val resume = item.segmentCount == totalUnits && item.trackIndex in 0..tracks.size
        if (!resume) DownloadCoordinator.deleteParts(this, id)
        val firstTrack = if (resume) item.trackIndex else 0
        val meter = SpeedMeter((0 until firstTrack).sumOf { DownloadCoordinator.trackFile(this, id, it).length() })
        var unitsBefore = tracks.take(firstTrack).sumOf { it.units }
        val startUnits = unitsBefore
        progress(id) {
            it.copy(phase = TransferPhase.DOWNLOADING, trackIndex = firstTrack, segmentsBefore = startUnits, segmentCount = totalUnits, totalBytes = null,
                segmentIndex = if (resume) it.segmentIndex else 0, segmentOffset = if (resume) it.segmentOffset else 0L, error = null)
        }
        refreshNotification()
        for (index in firstTrack until tracks.size) {
            val track = tracks[index]
            val file = DownloadCoordinator.trackFile(this, id, index)
            val checkpoint = resume && index == item.trackIndex
            if (track.isSingleFile) fetchWholeFile(track.urls.single(), item.requestHeaders, file, id, meter)
            else fetchSegments(track, item.requestHeaders, file, id, meter, if (checkpoint) item.segmentIndex else 0, if (checkpoint) item.segmentOffset else 0L)
            unitsBefore += track.units
            val done = unitsBefore
            progress(id) { it.copy(trackIndex = index + 1, segmentIndex = 0, segmentOffset = 0L, segmentsBefore = done) }
        }

        currentCoroutineContext().ensureActive()
        progress(id) { it.copy(phase = TransferPhase.PROCESSING, bytesPerSecond = 0L) }
        refreshNotification(force = true)
        val files = tracks.indices.map { DownloadCoordinator.trackFile(this, id, it) }
        val output = DownloadCoordinator.trackFile(this, id, DownloadCoordinator.OUTPUT_TRACK)
        val job = currentCoroutineContext()[Job]
        val merged = try {
            output to MediaRemuxer.remux(files, output) { job?.isActive == false }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Merging tracks failed for $id", failure)
            null
        }
        if (merged != null) {
            publish(id, merged.first, meter.received, merged.second)
        } else {
            // Keep what was downloaded rather than failing: the untouched stream, flagged when its audio is missing.
            val note = if (files.size > 1) "Saved without audio: the audio track couldn't be merged." else null
            publish(id, files.first(), meter.received, tracks.first().mime ?: MediaFiles.resolveMime(item.streamType, item.sourceUrl, item.mimeType), note)
        }
    }

    /** Fetches one complete file, resuming a partial one with a Range request. */
    private suspend fun fetchWholeFile(url: String, headers: Map<String, String>, file: File, id: String, meter: SpeedMeter) {
        val resumeFrom = file.length()
        val connection = openConnection(url, headers).apply { if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-") }
        val disconnectOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { connection.disconnect() }
        try {
            val code = connection.responseCode
            if (code == 416 && resumeFrom > 0) { meter.received += resumeFrom; return }
            if (code !in 200..299) throw HttpStatusException(code)
            val append = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
            if (append) meter.received += resumeFrom else file.delete()
            connection.inputStream.use { input -> FileOutputStream(file, append).buffered(BUFFER_SIZE).use { pump(input, it, id, meter) } }
        } finally {
            disconnectOnCancel?.dispose()
            connection.disconnect()
        }
    }

    /** Appends init + media segments in order, checkpointing after each; a restart truncates to the last checkpoint. */
    private suspend fun fetchSegments(track: Track, headers: Map<String, String>, file: File, id: String, meter: SpeedMeter, startSegment: Int, checkpointOffset: Long) {
        val resume = startSegment in 1..track.urls.size && file.length() >= checkpointOffset
        if (resume) RandomAccessFile(file, "rw").use { it.setLength(checkpointOffset) } else file.delete()
        if (resume) meter.received += checkpointOffset
        FileOutputStream(file, resume).buffered(BUFFER_SIZE).use { output ->
            if (!resume) track.initUrl?.let { fetchInto(it, headers, output, id, meter, "The initialization segment") }
            for (index in (if (resume) startSegment else 0) until track.urls.size) {
                currentCoroutineContext().ensureActive()
                fetchInto(track.urls[index], headers, output, id, meter, "Segment ${index + 1}")
                output.flush()
                val offset = file.length()
                val received = meter.received
                progress(id) { it.copy(segmentIndex = index + 1, segmentOffset = offset, downloadedBytes = received) }
            }
        }
    }

    private suspend fun fetchInto(url: String, headers: Map<String, String>, output: OutputStream, id: String, meter: SpeedMeter, what: String) {
        val connection = openConnection(url, headers)
        val disconnectOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { connection.disconnect() }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code, "$what returned HTTP $code")
            connection.inputStream.use { pump(it, output, id, meter) }
        } finally {
            disconnectOnCancel?.dispose()
            connection.disconnect()
        }
    }

    /** Download + merged copy + the copy into shared storage must fit, with headroom left for the system. */
    private fun ensureFreeSpace(item: DownloadItem) {
        val expected = item.totalBytes ?: item.estimatedBytes ?: return
        val remaining = (expected - item.downloadedBytes).coerceAtLeast(0L)
        val copies = if (item.streamType == StreamType.DIRECT && item.audioKey == null) 1 else 2
        val needed = remaining + expected * copies + SPACE_RESERVE_BYTES
        val available = runCatching { StatFs(noBackupFilesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (available < needed) {
            throw UnsupportedStreamException("Not enough free space: needs about ${needed.asReadableBytes()}, ${available.asReadableBytes()} available.")
        }
    }

    private class SpeedMeter(var received: Long) {
        var tickBytes = received
        var tickAt = System.currentTimeMillis()
    }

    private suspend fun pump(input: InputStream, output: OutputStream, id: String, meter: SpeedMeter) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            meter.received += count
            val now = System.currentTimeMillis()
            if (now - meter.tickAt >= PROGRESS_INTERVAL_MS) {
                val received = meter.received
                val speed = (received - meter.tickBytes) * 1000L / max(now - meter.tickAt, 1L)
                progress(id) { it.copy(phase = TransferPhase.DOWNLOADING, downloadedBytes = received, bytesPerSecond = speed, error = null) }
                refreshNotification()
                meter.tickBytes = received; meter.tickAt = now
            }
        }
    }

    private suspend fun publish(id: String, file: File, received: Long, mime: String? = null, note: String? = null) {
        currentCoroutineContext().ensureActive()
        progress(id) { it.copy(phase = TransferPhase.VERIFYING, downloadedBytes = received, bytesPerSecond = 0L) }
        refreshNotification()
        val item = DownloadCoordinator.find(id) ?: return
        val finalMime = mime ?: MediaFiles.resolveMime(item.streamType, item.sourceUrl, item.mimeType)
        val destination = finalizeToMediaStore(item, file, finalMime)
        DownloadCoordinator.deleteParts(this, id)
        DownloadCoordinator.update(id) {
            if (it.phase == TransferPhase.CANCELLED) it
            else it.copy(phase = TransferPhase.COMPLETED, downloadedBytes = received, totalBytes = it.totalBytes ?: received, destination = destination,
                bytesPerSecond = 0L, error = note, mimeType = finalMime)
        }
    }

    /** Transfer-side updates never resurrect an item the user paused or cancelled while the worker was mid-write. */
    private fun progress(id: String, transform: (DownloadItem) -> DownloadItem) =
        DownloadCoordinator.update(id) { if (it.phase.isRunning) transform(it) else it }

    private fun isRetryable(failure: IOException) =
        failure !is HttpStatusException || failure.code >= 500 || failure.code == 408 || failure.code == 429

    private fun isMeteredNetwork(): Boolean =
        runCatching { getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(false)

    private fun openConnection(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) setRequestProperty("User-Agent", "Fetch/1.0 Android")
        }

    private fun finalizeToMediaStore(item: DownloadItem, file: File, mime: String): String {
        val name = MediaFiles.displayName(item.title, mime)
        val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/Fetch"
        val (collection, directory) = when (MediaFiles.kindOf(mime)) {
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MOVIES}/Fetch"
            MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MUSIC}/Fetch"
            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_PICTURES}/Fetch"
            MediaKind.OTHER -> downloads
        }
        // MediaProvider rejects some type/collection pairs (e.g. unusual video containers); Downloads accepts anything.
        val uri = insertPending(collection, name, mime, directory)
            ?: insertPending(downloads.first, name, mime, downloads.second)
            ?: error("Could not create the file in shared storage")
        try {
            contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out, BUFFER_SIZE) } } ?: error("Could not write the media file")
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (failure: Exception) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw failure
        }
        return uri.toString()
    }

    private fun insertPending(collection: Uri, name: String, mime: String, directory: String): Uri? = runCatching {
        contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })
    }.getOrNull()

    /**
     * Updates the ongoing notification, or stops the service once nothing is queued or running.
     * The start id is read before checking for work: a newer onStartCommand publishes its id only after registering its job,
     * so either we see that job, or stopSelfResult() refuses our stale id.
     */
    private fun refreshNotification(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationUpdate.get() < NOTIFICATION_INTERVAL_MS) return
        lastNotificationUpdate.set(now)
        val startId = latestStartId.get()
        val idle = jobs.isEmpty() && DownloadCoordinator.downloads.value.none { it.phase.isRunning }
        if (idle) {
            if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        runCatching { startForeground(NOTIFICATION_ID, progressNotification()) }
    }

    private fun progressNotification(): Notification {
        val running = DownloadCoordinator.downloads.value.filter { it.phase.isRunning }
        val active = running.filter { it.phase.isActive }
        val first = active.firstOrNull() ?: running.firstOrNull()
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openDownloadsIntent())
        if (first == null) return builder.setContentTitle("Preparing downloads").build()
        val queued = running.size - active.size
        val title = if (active.isEmpty()) "Waiting to download" else "Downloading ${active.size} file${if (active.size == 1) "" else "s"}"
        val percentage = (first.progress() * 100).toInt()
        val amount = if (first.hasKnownProgress()) "$percentage%" else first.downloadedBytes.asReadableBytes()
        return builder
            .setContentTitle(if (queued > 0) "$title · $queued queued" else title)
            .setContentText("${first.title} · $amount")
            .setProgress(100, percentage, !first.hasKnownProgress())
            .addAction(android.R.drawable.ic_media_pause, "Pause", commandIntent(ACTION_PAUSE, first.id, 31))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", commandIntent(ACTION_CANCEL, first.id, 32))
            .build()
    }

    private fun notifyFinished(item: DownloadItem) {
        if (item.phase != TransferPhase.COMPLETED && item.phase != TransferPhase.FAILED) return
        val done = item.phase == TransferPhase.COMPLETED
        val notification = NotificationCompat.Builder(this, CHANNEL_FINISHED)
            .setSmallIcon(if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (done) "Download complete" else "Download failed")
            .setContentText(if (done) item.title else "${item.title}: ${item.error.orEmpty()}")
            .setAutoCancel(true)
            .setContentIntent(openDownloadsIntent())
            .build()
        // Throws SecurityException when the user declined notifications; the download itself still succeeded.
        runCatching { getSystemService(NotificationManager::class.java).notify(item.id.hashCode(), notification) }
    }

    private fun commandIntent(action: String, id: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun openDownloadsIntent() = PendingIntent.getActivity(
        this, 40, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_DOWNLOADS, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Active downloads", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_FINISHED, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun interruptRunning(message: String) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        DownloadCoordinator.downloads.value.filter { it.phase.isRunning }.forEach { item ->
            DownloadCoordinator.update(item.id) { it.copy(phase = TransferPhase.PAUSED, error = message, bytesPerSecond = 0L) }
        }
    }

    /** Android 15 caps dataSync foreground services at 6 hours a day; pause cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        interruptRunning("Android paused background downloads after the daily time limit. Tap retry to continue.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        interruptRunning("Download interrupted. Tap retry to resume.")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.fetch.START"; const val ACTION_PAUSE = "app.fetch.PAUSE"; const val ACTION_CANCEL = "app.fetch.CANCEL"; const val ACTION_RETRY = "app.fetch.RETRY"; const val EXTRA_ID = "download_id"
        private const val CHANNEL_PROGRESS = "downloads"; private const val CHANNEL_FINISHED = "downloads_finished"; private const val NOTIFICATION_ID = 17
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 2_000L
        private const val TAG = "FetchDownload"
        private const val SPACE_RESERVE_BYTES = 200L * 1024 * 1024
    }
}
