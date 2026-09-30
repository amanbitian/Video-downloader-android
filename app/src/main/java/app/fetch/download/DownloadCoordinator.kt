package app.fetch.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/** Process-local UI state. Files are kept as .part files, so an interrupted transfer is never exposed as media. */
object DownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableDownloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    private val mutableCandidates = MutableStateFlow<List<MediaCandidate>>(emptyList())
    private val stateLock = Any()
    private var persistJob: kotlinx.coroutines.Job? = null
    val downloads: StateFlow<List<DownloadItem>> = mutableDownloads.asStateFlow()
    val candidates: StateFlow<List<MediaCandidate>> = mutableCandidates.asStateFlow()
    @Volatile private var appContext: Context? = null

    /** Restores deterministic terminal states and makes interrupted work explicit instead of hiding it. */
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(DOWNLOADS_KEY, null) ?: return
        val restored = runCatching {
            JSONArray(saved).let { json -> List(json.length()) { decode(json.getJSONObject(it)) } }
        }.getOrDefault(emptyList()).map {
            if (it.phase in setOf(TransferPhase.CONNECTING, TransferPhase.DOWNLOADING, TransferPhase.VERIFYING, TransferPhase.QUEUED)) {
                it.copy(phase = TransferPhase.PAUSED, error = "Download interrupted. Tap retry to resume.")
            } else it
        }
        synchronized(stateLock) { mutableDownloads.value = restored }
        requestPersist(immediate = true)
    }

    fun detect(candidate: MediaCandidate) {
        if (!isLikelyMedia(candidate.url, candidate.mimeType)) return
        val isNew = synchronized(stateLock) {
            val key = candidateKey(candidate)
            if (mutableCandidates.value.any { candidateKey(it) == key }) false
            else {
                mutableCandidates.value = (mutableCandidates.value + candidate).takeLast(MAX_CANDIDATES)
                true
            }
        }
        if (isNew && candidate.streamType == StreamType.HLS) {
            scope.launch {
                val variants = runCatching { HlsResolver.resolve(candidate) }.getOrDefault(emptyList())
                if (variants.isNotEmpty()) updateCandidate(candidate.url) { it.copy(variants = variants) }
            }
        }
    }

    fun dismissCandidate(url: String) {
        synchronized(stateLock) { mutableCandidates.value = mutableCandidates.value.filterNot { it.url == url } }
    }

    fun enqueue(context: Context, candidate: MediaCandidate, variant: MediaVariant? = null) {
        val item = DownloadItem(
            title = fileSafeTitle(candidate.title), sourceUrl = variant?.url ?: candidate.url,
            mimeType = candidate.mimeType, streamType = variant?.streamType ?: candidate.streamType,
            requestHeaders = candidate.requestHeaders
        )
        synchronized(stateLock) { mutableDownloads.value = listOf(item) + mutableDownloads.value }
        requestPersist(immediate = true)
        dismissCandidate(candidate.url)
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_START)
                    .putExtra(DownloadService.EXTRA_ID, item.id)
            )
        }
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

    private fun isLikelyMedia(url: String, mime: String?): Boolean {
        val decodedUrl = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val value = "$decodedUrl ${mime.orEmpty()}".lowercase()
        return listOf(".mp4", ".webm", ".mkv", ".mov", ".mp3", ".m4a", ".m3u8", ".mpd", "video/", "audio/", "application/dash+xml").any(value::contains)
    }

    private fun fileSafeTitle(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(90)
        .ifBlank { "download" }

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

    private fun candidateKey(candidate: MediaCandidate) = "${candidate.url.substringBefore('?')}|${candidate.mimeType}|${candidate.streamType}"

    private fun encode(item: DownloadItem) = JSONObject().apply {
        put("id", item.id); put("title", item.title); put("url", item.sourceUrl); put("mime", item.mimeType)
        put("stream", item.streamType.name); put("phase", item.phase.name); put("done", item.downloadedBytes); put("total", item.totalBytes)
        put("error", item.error); put("destination", item.destination)
    }

    private fun decode(json: JSONObject) = DownloadItem(
        id = json.getString("id"), title = json.getString("title"), sourceUrl = json.getString("url"),
        mimeType = json.optString("mime").ifBlank { null }, streamType = runCatching { StreamType.valueOf(json.optString("stream")) }.getOrDefault(StreamType.DIRECT),
        phase = runCatching { TransferPhase.valueOf(json.getString("phase")) }.getOrDefault(TransferPhase.PAUSED),
        downloadedBytes = json.optLong("done"), totalBytes = json.optLong("total").takeIf { it > 0 }, error = json.optString("error").ifBlank { null },
        destination = json.optString("destination").ifBlank { null }
    )

    private const val PREFERENCES = "fetch_downloads"
    private const val DOWNLOADS_KEY = "items"
    private const val MAX_CANDIDATES = 12
    private const val PERSIST_DEBOUNCE_MS = 1_000L
}
