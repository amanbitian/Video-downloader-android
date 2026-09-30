package app.fetch.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.fetch.browser.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Process-local UI state. Files are kept as .part files, so an interrupted transfer is never exposed as media. */
object DownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableDownloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    private val mutableCandidates = MutableStateFlow<List<MediaCandidate>>(emptyList())
    private val mutableSheetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val stateLock = Any()
    private var persistJob: Job? = null
    val downloads: StateFlow<List<DownloadItem>> = mutableDownloads.asStateFlow()
    val candidates: StateFlow<List<MediaCandidate>> = mutableCandidates.asStateFlow()
    /** Emits when the user explicitly asked to download something, so the UI opens the download sheet. */
    val sheetRequests: SharedFlow<Unit> = mutableSheetRequests.asSharedFlow()
    @Volatile private var appContext: Context? = null

    /** Restores deterministic terminal states and makes interrupted work explicit instead of hiding it. */
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(DOWNLOADS_KEY, null) ?: return
        val restored = runCatching {
            JSONArray(saved).let { json -> List(json.length()) { decode(json.getJSONObject(it)) } }
        }.getOrDefault(emptyList()).map {
            if (it.phase.isRunning) it.copy(phase = TransferPhase.PAUSED, error = "Download interrupted. Tap retry to resume.", bytesPerSecond = 0L)
            else it
        }
        // Earlier builds kept partial files in cacheDir, which Android may purge under storage pressure.
        restored.forEach { item ->
            val legacy = File(context.cacheDir, "${item.id}.part")
            if (legacy.exists()) legacy.renameTo(partFile(context, item.id))
        }
        synchronized(stateLock) { mutableDownloads.value = restored }
        requestPersist(immediate = true)
    }

    /** Partial files live in no-backup app storage: private, never purged by the system, excluded from backups. */
    fun partFile(context: Context, id: String): File =
        File(File(context.noBackupFilesDir, "parts").apply { mkdirs() }, "$id.part")

    private val sessionCounter = AtomicLong(0)
    private val activeSessionId = AtomicLong(0)
    @Volatile private var sessionJob = SupervisorJob()
    private val probedUrls = Collections.synchronizedSet(HashSet<String>())

    /** A new page: forget its predecessor's media and cancel any lookups still running for it. */
    fun startNewSession(): Long {
        sessionJob.cancel()
        sessionJob = SupervisorJob()
        probedUrls.clear()
        val id = sessionCounter.incrementAndGet()
        activeSessionId.set(id)
        synchronized(stateLock) { mutableCandidates.value = emptyList() }
        return id
    }

    fun detect(candidate: MediaCandidate, sessionId: Long) {
        if (sessionId != activeSessionId.get()) return
        if (UrlUtils.isBlockedSource(candidate.url, candidate.pageUrl)) return
        if (!candidate.explicit && !isLikelyMedia(candidate.url, candidate.mimeType)) {
            if (candidate.probe) launchProbe(candidate, sessionId, adoptOnlyIfMedia = true)
            return
        }
        val isNew = add(candidate)
        if (candidate.explicit) mutableSheetRequests.tryEmit(Unit)
        if (!isNew) return
        when (candidate.streamType) {
            StreamType.HLS -> scope.launch(sessionJob) {
                val result = runCatching { HlsResolver.resolve(candidate) }
                if (sessionId != activeSessionId.get()) return@launch
                val variants = result.getOrDefault(emptyList())
                val note = when {
                    variants.isNotEmpty() -> null
                    result.isFailure -> "Couldn't read this stream: ${result.exceptionOrNull()?.message ?: "network error"}"
                    else -> "This stream is encrypted or protected and can't be downloaded."
                }
                updateCandidate(candidate.url) { it.copy(variants = variants, resolved = true, note = note) }
            }
            StreamType.DIRECT -> if (candidate.sizeBytes == null) launchProbe(candidate, sessionId, adoptOnlyIfMedia = false)
            StreamType.DASH -> Unit
        }
    }

    /** Detects outside any page session, e.g. a download started from a link the user tapped. */
    fun detect(candidate: MediaCandidate) {
        detect(candidate, activeSessionId.get())
    }

    /** Media is often requested before the page title is known; name sniffed candidates after the page once it is. */
    fun applyPageTitle(sessionId: Long, title: String) {
        if (sessionId != activeSessionId.get() || title.isBlank()) return
        synchronized(stateLock) { mutableCandidates.value = mutableCandidates.value.map { if (it.explicit) it else it.copy(title = title) } }
    }

    fun dismissCandidate(url: String) {
        synchronized(stateLock) { mutableCandidates.value = mutableCandidates.value.filterNot { it.url == url } }
    }

    fun isAlreadyDownloaded(url: String): Boolean =
        downloads.value.any { it.sourceUrl == url && it.phase == TransferPhase.COMPLETED }

    fun enqueue(context: Context, candidate: MediaCandidate, variant: MediaVariant? = null) {
        val item = DownloadItem(
            title = MediaFiles.safeTitle(candidate.title), sourceUrl = variant?.url ?: candidate.url,
            mimeType = candidate.mimeType, streamType = variant?.streamType ?: candidate.streamType,
            requestHeaders = candidate.requestHeaders, totalBytes = candidate.sizeBytes.takeIf { variant == null }
        )
        synchronized(stateLock) { mutableDownloads.value = listOf(item) + mutableDownloads.value }
        requestPersist(immediate = true)
        dismissCandidate(candidate.url)
        command(context, DownloadService.ACTION_START, item.id)
    }

    fun command(context: Context, action: String, id: String) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).setAction(action)
                    .putExtra(DownloadService.EXTRA_ID, id)
            )
        }
    }

    /** Removes a row; running transfers are cancelled first. With [deleteFile] the saved file is deleted too. */
    fun remove(context: Context, id: String, deleteFile: Boolean) {
        val item = find(id) ?: return
        if (item.phase.isRunning) command(context, DownloadService.ACTION_CANCEL, id)
        else partFile(context, id).delete()
        if (deleteFile) MediaActions.deleteFile(context, item)
        synchronized(stateLock) { mutableDownloads.value = mutableDownloads.value.filterNot { it.id == id } }
        requestPersist(immediate = true)
    }

    fun clearFinished() {
        synchronized(stateLock) {
            mutableDownloads.value = mutableDownloads.value.filterNot { it.phase == TransferPhase.COMPLETED || it.phase == TransferPhase.CANCELLED }
        }
        requestPersist(immediate = true)
    }

    fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        val stateChanged = synchronized(stateLock) {
            val before = mutableDownloads.value
            val after = before.map { if (it.id == id) transform(it) else it }
            mutableDownloads.value = after
            before.firstOrNull { it.id == id }?.phase != after.firstOrNull { it.id == id }?.phase
        }
        requestPersist(immediate = stateChanged)
    }

    fun find(id: String): DownloadItem? = synchronized(stateLock) { mutableDownloads.value.firstOrNull { it.id == id } }

    private fun add(candidate: MediaCandidate): Boolean = synchronized(stateLock) {
        val key = candidateKey(candidate)
        if (mutableCandidates.value.any { candidateKey(it) == key }) false
        else {
            mutableCandidates.value = (mutableCandidates.value + candidate).takeLast(MAX_CANDIDATES)
            true
        }
    }

    /** Fills in size/type for a detected file, or (for URLs without a media hint) offers it only if the server says it is media. */
    private fun launchProbe(candidate: MediaCandidate, sessionId: Long, adoptOnlyIfMedia: Boolean) {
        if (!probedUrls.add(candidate.url.substringBefore('?'))) return
        scope.launch(sessionJob) {
            val info = runCatching { MediaProbe.probe(candidate.url, candidate.requestHeaders) }.getOrNull() ?: return@launch
            if (sessionId != activeSessionId.get()) return@launch
            if (adoptOnlyIfMedia) {
                val mime = info.mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                val isMedia = mime.startsWith("video/") || mime.startsWith("audio/")
                // UI sounds and previews are tiny; real downloads are not.
                if (!isMedia || (info.sizeBytes ?: Long.MAX_VALUE) < MIN_PROBED_MEDIA_BYTES) return@launch
                add(candidate.copy(mimeType = mime, sizeBytes = info.sizeBytes, probe = false))
            } else {
                updateCandidate(candidate.url) { it.copy(sizeBytes = info.sizeBytes ?: it.sizeBytes, mimeType = it.mimeType ?: info.mimeType) }
            }
        }
    }

    private fun isLikelyMedia(url: String, mime: String?): Boolean {
        val decodedUrl = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val value = "$decodedUrl ${mime.orEmpty()}".lowercase()
        return listOf(".mp4", ".webm", ".mkv", ".mov", ".ogv", ".mp3", ".m4a", ".ogg", ".opus", ".flac", ".wav", ".m3u8", ".mpd", "video/", "audio/", "application/dash+xml")
            .any(value::contains)
    }

    private fun updateCandidate(url: String, transform: (MediaCandidate) -> MediaCandidate) {
        synchronized(stateLock) { mutableCandidates.value = mutableCandidates.value.map { if (it.url == url) transform(it) else it } }
    }

    private fun requestPersist(immediate: Boolean) = synchronized(stateLock) {
        if (immediate) persistJob?.cancel()
        if (immediate || persistJob?.isActive != true) {
            persistJob = scope.launch {
                if (!immediate) delay(PERSIST_DEBOUNCE_MS)
                persistSnapshot()
            }
        }
    }

    private fun persistSnapshot() {
        val context = appContext ?: return
        val snapshot = synchronized(stateLock) { mutableDownloads.value.toList() }
        val json = JSONArray().apply { snapshot.forEach { put(encode(it)) } }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(DOWNLOADS_KEY, json.toString()).apply()
    }

    private fun candidateKey(candidate: MediaCandidate) = "${candidate.url.substringBefore('?')}|${candidate.streamType}"

    private fun encode(item: DownloadItem) = JSONObject().apply {
        put("id", item.id); put("title", item.title); put("url", item.sourceUrl); put("mime", item.mimeType)
        put("stream", item.streamType.name); put("phase", item.phase.name); put("done", item.downloadedBytes); put("total", item.totalBytes)
        put("error", item.error); put("destination", item.destination); put("validator", item.validator)
        put("segIndex", item.segmentIndex); put("segOffset", item.segmentOffset); put("segCount", item.segmentCount); put("created", item.createdAt)
        // Cookies/Referer/User-Agent are what authorise the request; without them a resume after process death gets 403.
        put("headers", JSONObject(item.requestHeaders))
    }

    private fun decode(json: JSONObject) = DownloadItem(
        id = json.getString("id"), title = json.getString("title"), sourceUrl = json.getString("url"),
        mimeType = json.optString("mime").ifBlank { null }, streamType = runCatching { StreamType.valueOf(json.optString("stream")) }.getOrDefault(StreamType.DIRECT),
        phase = runCatching { TransferPhase.valueOf(json.getString("phase")) }.getOrDefault(TransferPhase.PAUSED),
        downloadedBytes = json.optLong("done"), totalBytes = json.optLong("total").takeIf { it > 0 }, error = json.optString("error").ifBlank { null },
        destination = json.optString("destination").ifBlank { null }, validator = json.optString("validator").ifBlank { null },
        segmentIndex = json.optInt("segIndex"), segmentOffset = json.optLong("segOffset"), segmentCount = json.optInt("segCount"),
        createdAt = json.optLong("created").takeIf { it > 0 } ?: System.currentTimeMillis(),
        requestHeaders = json.optJSONObject("headers")?.let { headers -> headers.keys().asSequence().associateWith { headers.getString(it) } }.orEmpty(),
    )

    private const val PREFERENCES = "fetch_downloads"
    private const val DOWNLOADS_KEY = "items"
    private const val MAX_CANDIDATES = 12
    private const val PERSIST_DEBOUNCE_MS = 1_000L
    private const val MIN_PROBED_MEDIA_BYTES = 100L * 1024
}
