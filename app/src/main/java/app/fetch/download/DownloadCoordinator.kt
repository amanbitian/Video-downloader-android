package app.fetch.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.fetch.browser.UrlUtils
import app.fetch.detection.AdMediaClassifier
import app.fetch.detection.PageMediaSnapshot
import app.fetch.detection.PrimaryMediaResolver
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

/**
 * Single source of truth for downloads (persisted) and for the current page's detected media (in memory).
 * Files are kept as .part files, so an interrupted transfer is never exposed as media.
 */
object DownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableDownloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    private val mutableCandidates = MutableStateFlow<List<MediaCandidate>>(emptyList())
    private val mutableSheetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val stateLock = Any()
    private var persistJob: Job? = null
    val downloads: StateFlow<List<DownloadItem>> = mutableDownloads.asStateFlow()
    private val mutableVisible = MutableStateFlow<List<MediaCandidate>>(emptyList())
    /** What the UI offers: the page's primary video(s), fully resolved. See [PrimaryMediaResolver]. */
    val candidates: StateFlow<List<MediaCandidate>> = mutableVisible.asStateFlow()
    /** Every raw detection of the current page, before primary-video selection (for tests and diagnostics). */
    internal val allCandidates: StateFlow<List<MediaCandidate>> = mutableCandidates.asStateFlow()

    /** Raw detections; every change re-runs primary-video selection. Guarded by [stateLock]. */
    private var raw: List<MediaCandidate>
        get() = mutableCandidates.value
        set(value) {
            mutableCandidates.value = value
            mutableVisible.value = PrimaryMediaResolver.select(value.filter(::isReady), snapshot, pageTitle, pageHost)
        }
    /** Emits when the user explicitly asked to download something, so the UI opens the download sheet. */
    val sheetRequests: SharedFlow<Unit> = mutableSheetRequests.asSharedFlow()
    @Volatile private var appContext: Context? = null

    /** Resolves HLS/DASH manifests into qualities. Replaceable so tests can control timing. */
    internal var streamResolver: suspend (MediaCandidate) -> ResolvedStream = { candidate ->
        if (candidate.streamType == StreamType.HLS) HlsResolver.resolve(candidate) else DashResolver.resolve(candidate)
    }

    /** Restores deterministic terminal states and makes interrupted work explicit instead of hiding it. */
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(DOWNLOADS_KEY, null)
        val restored = runCatching {
            JSONArray(saved ?: "[]").let { json -> List(json.length()) { decode(json.getJSONObject(it)) } }
        }.getOrDefault(emptyList()).map {
            if (it.phase.isRunning) it.copy(phase = TransferPhase.PAUSED, error = "Download interrupted. Tap retry to resume.", bytesPerSecond = 0L)
            else it
        }
        // Earlier builds kept partial files in cacheDir, which Android may purge under storage pressure.
        restored.forEach { item ->
            val legacy = File(context.cacheDir, "${item.id}.part")
            if (legacy.exists()) legacy.renameTo(partFile(context, item.id))
        }
        // Temp files whose download no longer exists (removed while the process was dead, crashed mid-merge…).
        val known = restored.map { it.id }.toSet()
        partsDir(context).listFiles()?.filter { it.name.substringBefore('.') !in known }?.forEach { it.delete() }
        synchronized(stateLock) { mutableDownloads.value = restored }
        requestPersist(immediate = true)
    }

    private fun partsDir(context: Context) = File(context.noBackupFilesDir, "parts").apply { mkdirs() }

    /** Partial files live in no-backup app storage: private, never purged by the system, excluded from backups. */
    fun partFile(context: Context, id: String): File = File(partsDir(context), "$id.part")

    /** Track 0 is the classic single part file, so downloads started by older builds still resume. */
    fun trackFile(context: Context, id: String, index: Int): File = if (index == 0) partFile(context, id) else File(partsDir(context), "$id.t$index.part")

    /** Removes every temporary file of a download: tracks, merge output. */
    fun deleteParts(context: Context, id: String) {
        partsDir(context).listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
    }

    const val OUTPUT_TRACK = 99

    private var sessionCounter = 0L
    @Volatile private var activeSessionId = 0L
    @Volatile private var sessionJob = SupervisorJob()
    private val probedUrls = Collections.synchronizedSet(HashSet<String>())
    /** Representation/segment URLs owned by a manifest resolved on this page. Guarded by [stateLock]. */
    private val claimedPaths = HashSet<String>()
    private val claimedPrefixes = HashSet<String>()
    /** Paths of other-quality streams/files of a video on this page, looked up or folded into its entry. Guarded by [stateLock]. */
    private val qualityPaths = HashSet<String>()
    private var pageTitle: String? = null
    private var pageThumbnail: String? = null
    private var pageHost: String? = null
    /** The latest DOM/metadata scan of the current page. */
    private var snapshot: PageMediaSnapshot? = null
    /** Latest audio-only file a streaming player fetched on this page; paired with its video qualities. */
    private var sliceAudio: Pair<String, Long?>? = null

    /**
     * A new page: forget its predecessor's media and cancel lookups still running for it. Results that finish anyway
     * are committed only if their session is still current (checked under the same lock), so they can't reappear.
     */
    fun startNewSession(): Long = synchronized(stateLock) {
        sessionJob.cancel()
        sessionJob = SupervisorJob()
        probedUrls.clear()
        claimedPaths.clear()
        claimedPrefixes.clear()
        qualityPaths.clear()
        pageTitle = null; pageThumbnail = null; pageHost = null; snapshot = null; sliceAudio = null
        raw = emptyList()
        activeSessionId = ++sessionCounter
        activeSessionId
    }

    fun detect(candidate: MediaCandidate, sessionId: Long) {
        if (sessionId != activeSessionId) return
        if (UrlUtils.isBlockedSource(candidate.url, candidate.pageUrl)) return
        // Photos are never downloads, not even from an explicit link; thumbnails are only used for display.
        if (isImage(candidate.url, candidate.mimeType)) return
        if (!candidate.explicit && AdMediaClassifier.isAdUrl(candidate.url)) return
        if (!candidate.explicit && isClaimed(candidate.url)) return
        // Another quality of a video already listed (looked up from the page, or folded in): its entry has it.
        if (!candidate.explicit && candidate.streamType != StreamType.DIRECT && synchronized(stateLock) { pathOf(candidate.url) in qualityPaths }) return
        if (!candidate.explicit && !isLikelyMedia(candidate.url, candidate.mimeType)) {
            if (candidate.probe) launchProbe(candidate, sessionId, adoptOnlyIfMedia = true)
            return
        }
        if (!candidate.explicit && candidate.streamType == StreamType.DIRECT) {
            val url = MediaGrouping.stripByteRangeParams(candidate.url)
            val stem = MediaGrouping.stem(url)
            // Alternative sources of one <video>, files named by quality, or slices fetched by a streaming player: one video, several versions.
            if (candidate.groupKey != null || stem != null || candidate.sliceFetch) {
                detectQuality(candidate.copy(url = url), candidate.groupKey ?: stem ?: url.substringBefore('?'), sessionId)
                return
            }
        }
        val isNew = commitIfCurrent(sessionId) { addLocked(withPageInfo(candidate)).also { if (it) findQualitySiblingsLocked(sessionId) } } == true
        if (candidate.explicit) mutableSheetRequests.tryEmit(Unit)
        if (!isNew) return
        when (candidate.streamType) {
            StreamType.HLS, StreamType.DASH -> resolveStream(candidate, sessionId)
            StreamType.DIRECT -> if (candidate.sizeBytes == null) launchProbe(candidate, sessionId, adoptOnlyIfMedia = false)
        }
    }

    /** Detects outside any page session, e.g. a download started from a link the user tapped. */
    fun detect(candidate: MediaCandidate) {
        detect(candidate, activeSessionId)
    }

    /** Media is often requested before the page's title and image are known; apply them to sniffed candidates once they are. */
    fun applyPageInfo(sessionId: Long, title: String?, thumbnailUrl: String?) {
        commitIfCurrent(sessionId) {
            title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
            thumbnailUrl?.takeIf { it.startsWith("http") }?.let { pageThumbnail = it }
            raw = raw.map(::withPageInfo)
        }
    }

    /** Stores the page's DOM/metadata scan; primary-video selection uses it immediately. Stale scans are ignored. */
    fun applySnapshot(sessionId: Long, pageSnapshot: PageMediaSnapshot) {
        commitIfCurrent(sessionId) {
            snapshot = pageSnapshot
            pageHost = UrlUtils.host(pageSnapshot.pageUrl).ifBlank { pageHost }
            raw = raw
            findQualitySiblingsLocked(sessionId)
        }
    }

    fun dismissCandidate(url: String) {
        synchronized(stateLock) { raw = raw.filterNot { it.url == url } }
    }

    fun isAlreadyDownloaded(url: String, videoKey: String? = null): Boolean =
        downloads.value.any { it.phase == TransferPhase.COMPLETED && it.sourceUrl == url && it.videoKey == videoKey }

    fun enqueue(context: Context, candidate: MediaCandidate, variant: MediaVariant? = null, title: String? = null) {
        val item = DownloadItem(
            title = MediaFiles.safeTitle(title?.takeIf { it.isNotBlank() } ?: candidate.title), sourceUrl = variant?.url ?: candidate.url,
            mimeType = candidate.mimeType.takeIf { variant == null }, streamType = variant?.streamType ?: candidate.streamType,
            requestHeaders = candidate.requestHeaders, totalBytes = candidate.sizeBytes.takeIf { variant == null },
            estimatedBytes = variant?.estimatedBytes, videoKey = variant?.videoKey, audioKey = variant?.audioKey,
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
        else deleteParts(context, id)
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

    /** Runs [block] under the state lock only if [sessionId] is still the visible page; returns null otherwise. */
    private inline fun <T> commitIfCurrent(sessionId: Long, block: () -> T): T? = synchronized(stateLock) {
        if (sessionId != activeSessionId) null else block()
    }

    /** Adds a detection, or folds what it adds (page signals, player element, poster) into the entry that already has it. */
    private fun addLocked(candidate: MediaCandidate): Boolean {
        val key = candidateKey(candidate)
        val path = candidate.url.substringBefore('?')
        val existing = raw.firstOrNull { candidateKey(it) == key }
            ?: raw.firstOrNull { group -> !candidate.explicit && group.variants.any { it.url.substringBefore('?') == path } }
        if (existing != null) {
            raw = raw.map { if (it === existing) it.mergedWith(candidate) else it }
            return false
        }
        raw = (raw + candidate.copy(resolved = candidate.resolved || candidate.explicit || candidate.sizeBytes != null)).takeLast(MAX_CANDIDATES)
        return true
    }

    private fun MediaCandidate.mergedWith(other: MediaCandidate) = copy(
        signals = signals + other.signals,
        elementKey = elementKey ?: other.elementKey,
        thumbnailUrl = thumbnailUrl ?: other.thumbnailUrl,
    )

    /** Only candidates whose lookups have finished are shown, so the button never opens onto a half-empty sheet. */
    private fun isReady(candidate: MediaCandidate): Boolean = when {
        candidate.explicit -> true
        candidate.isAd -> false
        candidate.streamType != StreamType.DIRECT -> candidate.resolved && (candidate.variants.isNotEmpty() || candidate.note != null)
        candidate.groupKey != null -> candidate.variants.isNotEmpty()
        else -> candidate.resolved
    }

    private fun isImage(url: String, mime: String?): Boolean =
        mime?.trim()?.lowercase()?.startsWith("image/") == true || MediaFiles.kindOf(MediaFiles.resolveMime(StreamType.DIRECT, url)) == MediaKind.IMAGE

    private fun withPageInfo(candidate: MediaCandidate): MediaCandidate = if (candidate.explicit) candidate else candidate.copy(
        title = pageTitle ?: candidate.title,
        thumbnailUrl = candidate.thumbnailUrl ?: pageThumbnail,
    )

    private fun resolveStream(candidate: MediaCandidate, sessionId: Long) = scope.launch(sessionJob) {
        val result = runCatching { streamResolver(candidate) }
        val resolved = result.getOrElse { ResolvedStream(emptyList(), "Couldn't read this stream: ${it.message ?: "network error"}") }
        commitIfCurrent(sessionId) {
            claimedPaths += resolved.claimedPaths
            claimedPrefixes += resolved.claimedPrefixes
            // One stream per quality (players load one): a stream of a video already listed adds its qualities to that entry.
            if (resolved.isAd || !absorbIntoFamilyLocked(candidate.url, resolved)) {
                raw = raw.map { existing ->
                    if (existing.url != candidate.url) return@map existing
                    // Qualities looked up from the page may have arrived first; keep them.
                    val variants = mergeQualities(existing.variants, resolved.variants)
                    existing.copy(variants = variants, resolved = true, note = resolved.note.takeIf { variants.isEmpty() },
                        durationSeconds = resolved.durationSeconds ?: existing.durationSeconds, isAd = resolved.isAd)
                }
            }
            // Sniffed copies of this manifest's own representations/segments are not separate videos.
            raw = raw.mapNotNull { existing ->
                when {
                    existing.url == candidate.url -> existing
                    existing.explicit -> existing
                    existing.groupKey != null -> existing.copy(variants = existing.variants.filterNot { isClaimedLocked(it.url) }).takeIf { it.variants.isNotEmpty() }
                    isClaimedLocked(existing.url) -> null
                    else -> existing
                }
            }
        }
    }

    /**
     * Players fetch one quality, while the page's script often lists them all as separate streams or files
     * (".../240P_400K_x.mp4/master.m3u8", ".../1080P_4000K_x.mp4/master.m3u8"). Inline URLs of the same family as a
     * detected video are looked up once each and filed under that video as more qualities.
     */
    private fun findQualitySiblingsLocked(sessionId: Long) {
        val inline = snapshot?.inlineUrls.orEmpty()
        if (inline.isEmpty()) return
        val families = inline.mapNotNull { url -> MediaGrouping.family(url)?.let { url to it } }
        if (families.isEmpty()) return
        val present = raw.flatMapTo(HashSet()) { c -> c.variants.map { pathOf(it.url) } + pathOf(c.url) }
        raw.filter { !it.explicit && !it.isAd && (it.streamType != StreamType.DIRECT || it.groupKey != null) }.forEach { target ->
            val family = MediaGrouping.family(target.url) ?: return@forEach
            families.forEach { (url, urlFamily) ->
                val path = pathOf(url)
                if (urlFamily != family || streamTypeOf(url) != target.streamType || path in present || !qualityPaths.add(path)) return@forEach
                lookUpSibling(target, url, sessionId)
            }
        }
    }

    private fun lookUpSibling(target: MediaCandidate, url: String, sessionId: Long) {
        val sibling = target.copy(url = url, variants = emptyList(), sliceFetch = false, resolved = false, note = null, sizeBytes = null)
        if (target.streamType == StreamType.DIRECT) {
            // Probed like any quality-named file and filed under the target's group.
            target.groupKey?.let { detectQuality(sibling, it, sessionId) }
            return
        }
        scope.launch(sessionJob) {
            val resolved = runCatching { streamResolver(sibling) }.getOrNull()
            commitIfCurrent(sessionId) {
                if (resolved == null || resolved.isAd || resolved.variants.isEmpty()) {
                    // Not usable; if the player fetches it later it is judged on its own.
                    qualityPaths -= pathOf(url)
                    return@commitIfCurrent
                }
                claimedPaths += resolved.claimedPaths
                claimedPrefixes += resolved.claimedPrefixes
                absorbIntoFamilyLocked(url, resolved)
            }
        }
    }

    /**
     * Files the resolved stream at [url] under another listed stream of the same video (same [MediaGrouping.family]),
     * removing its own entry if it has one. Returns false when no such entry exists.
     */
    private fun absorbIntoFamilyLocked(url: String, resolved: ResolvedStream): Boolean {
        val family = MediaGrouping.family(url) ?: return false
        val path = pathOf(url)
        val host = raw.firstOrNull {
            pathOf(it.url) != path && !it.explicit && !it.isAd && it.streamType != StreamType.DIRECT && MediaGrouping.family(it.url) == family
        } ?: return false
        val own = raw.firstOrNull { pathOf(it.url) == path && it.groupKey == null }
        val variants = mergeQualities(host.variants, resolved.variants)
        raw = raw.mapNotNull {
            when {
                it === host -> (if (own != null) it.mergedWith(own) else it).copy(
                    variants = variants, note = it.note.takeIf { variants.isEmpty() },
                    durationSeconds = it.durationSeconds ?: resolved.durationSeconds,
                )
                it === own -> null
                else -> it
            }
        }
        qualityPaths += path
        return true
    }

    /** Qualities from several sources of one video: one per resolution (the first seen wins a tie), best first. */
    private fun mergeQualities(current: List<MediaVariant>, added: List<MediaVariant>): List<MediaVariant> {
        if (current.isEmpty()) return added
        val all = (current + added).distinctBy { Triple(it.url, it.videoKey, it.audioKey) }
        val (known, unknown) = all.partition { it.height != null }
        return (known.distinctBy { it.height } + unknown)
            .sortedWith(compareByDescending<MediaVariant> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 })
    }

    private fun streamTypeOf(url: String): StreamType {
        val lowered = url.substringBefore('?').lowercase()
        return when {
            lowered.endsWith(".m3u8") -> StreamType.HLS
            lowered.endsWith(".mpd") -> StreamType.DASH
            else -> StreamType.DIRECT
        }
    }

    private fun pathOf(url: String) = url.substringBefore('#').substringBefore('?')

    /** One quality of an adaptive or multi-quality video: probe it, then file it under its group. */
    private fun detectQuality(candidate: MediaCandidate, stem: String, sessionId: Long) {
        if (!probedUrls.add("$stem|${candidate.url.substringBefore('?')}")) return
        scope.launch(sessionJob) {
            val info = runCatching { MediaProbe.probe(candidate.url, candidate.requestHeaders) }.getOrNull()
            val mime = info?.mimeType?.substringBefore(';')?.trim()?.lowercase()
            commitIfCurrent(sessionId) {
                if (isClaimedLocked(candidate.url)) return@commitIfCurrent
                if (mime != null && !mime.startsWith("video/") && !mime.startsWith("audio/") && !isLikelyMedia(candidate.url, null)) return@commitIfCurrent
                if (mime?.startsWith("audio/") == true && candidate.sliceFetch) {
                    sliceAudio = candidate.url to info?.sizeBytes
                    raw = raw.map { if (it.groupKey != null && it.sliceFetch) it.withAudio() else it }
                    return@commitIfCurrent
                }
                val height = MediaGrouping.heightOf(candidate.url)
                val format = MediaFiles.extensionOf(candidate.url).uppercase().takeIf { it.length in 2..4 }
                val quality = MediaVariant(
                    url = candidate.url, label = height?.let { "${it}p" } ?: format ?: "Video", detail = "", streamType = StreamType.DIRECT,
                    estimatedBytes = info?.sizeBytes, height = height,
                )
                val path = candidate.url.substringBefore('?')
                // Two players on a page can show the same files; a group that already has this file absorbs it.
                val existing = raw.firstOrNull { it.groupKey == stem }
                    ?: raw.firstOrNull { it.groupKey != null && it.variants.any { v -> v.url.substringBefore('?') == path } }
                var group = (existing?.mergedWith(candidate) ?: withPageInfo(candidate.copy(groupKey = stem, mimeType = mime ?: candidate.mimeType)))
                    .let { it.copy(variants = (it.variants + quality).distinctBy { v -> v.url }) }
                // Probes finish in any order, so two groups may have formed before they shared a file; fold them together now.
                val paths = group.variants.map { it.url.substringBefore('?') }.toSet()
                val overlapping = raw.filter { it !== existing && it.groupKey != null && it.variants.any { v -> v.url.substringBefore('?') in paths } }
                if (overlapping.isNotEmpty()) group = overlapping.fold(group) { merged, other -> merged.mergedWith(other) }
                    .copy(variants = (group.variants + overlapping.flatMap { it.variants }).distinctBy { v -> v.url })
                group = if (group.sliceFetch) group.withAudio() else group.describeQualities()
                // The same file may already be listed on its own (seen on the network first); the group replaces it.
                val others = raw.filterNot {
                    it in overlapping || (it.groupKey == null && !it.explicit && it.url.substringBefore('?') in paths)
                }
                raw = if (existing == null) (others + group).takeLast(MAX_CANDIDATES)
                else others.map { if (it === existing) group else it }
                findQualitySiblingsLocked(sessionId)
            }
        }
    }

    /** Pairs a sliced video's qualities with the page's separately fetched audio, so the result isn't silent. */
    private fun MediaCandidate.withAudio(): MediaCandidate {
        val audio = sliceAudio ?: return describeQualities()
        return copy(variants = variants.map { it.copy(audioKey = audio.first) }).describeQualities(audio.second)
    }

    private fun MediaCandidate.describeQualities(audioBytes: Long? = null): MediaCandidate = copy(
        variants = variants.map { variant ->
            val bytes = variant.estimatedBytes?.let { it + (audioBytes ?: 0L) }
            variant.copy(detail = listOfNotNull(
                variant.url.substringBefore('?').substringAfterLast('/'),
                bytes?.asReadableBytes(),
                "with separate audio".takeIf { variant.audioKey != null },
            ).joinToString(" · "))
        }.sortedWith(compareByDescending<MediaVariant> { it.height ?: 0 }.thenByDescending { it.estimatedBytes ?: 0 }),
    )

    /** Fills in size/type for a detected file, or (for URLs without a media hint) offers it only if the server says it is media. */
    private fun launchProbe(candidate: MediaCandidate, sessionId: Long, adoptOnlyIfMedia: Boolean) {
        if (!probedUrls.add(candidate.url.substringBefore('?'))) return
        scope.launch(sessionJob) {
            val info = runCatching { MediaProbe.probe(candidate.url, candidate.requestHeaders) }.getOrNull()
            commitIfCurrent(sessionId) {
                if (info == null) {
                    // The server wouldn't say; a file the page itself uses is still offered, just without a size.
                    if (!adoptOnlyIfMedia) raw = raw.map { if (it.url == candidate.url) it.copy(resolved = true) else it }
                    return@commitIfCurrent
                }
                if (adoptOnlyIfMedia) {
                    val mime = info.mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    val isMedia = mime.startsWith("video/") || mime.startsWith("audio/")
                    // UI sounds and previews are tiny; real downloads are not.
                    if (!isMedia || (info.sizeBytes ?: Long.MAX_VALUE) < MIN_PROBED_MEDIA_BYTES) return@commitIfCurrent
                    addLocked(withPageInfo(candidate.copy(mimeType = mime, sizeBytes = info.sizeBytes, probe = false, resolved = true)))
                } else {
                    raw = raw.map {
                        if (it.url == candidate.url) it.copy(sizeBytes = info.sizeBytes ?: it.sizeBytes, mimeType = it.mimeType ?: info.mimeType, resolved = true) else it
                    }
                }
            }
        }
    }

    private fun isClaimed(url: String) = synchronized(stateLock) { isClaimedLocked(url) }
    private fun isClaimedLocked(url: String): Boolean {
        val path = MediaGrouping.stripByteRangeParams(url).substringBefore('?')
        return path in claimedPaths || claimedPrefixes.any(path::startsWith)
    }

    private fun isLikelyMedia(url: String, mime: String?): Boolean {
        val decodedUrl = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val value = "$decodedUrl ${mime.orEmpty()}".lowercase()
        return listOf(".mp4", ".webm", ".mkv", ".mov", ".ogv", ".mp3", ".m4a", ".ogg", ".opus", ".flac", ".wav", ".m3u8", ".mpd", "video/", "audio/", "application/dash+xml")
            .any(value::contains)
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

    private fun candidateKey(candidate: MediaCandidate) = candidate.groupKey ?: "${candidate.url.substringBefore('?')}|${candidate.streamType}"

    private fun encode(item: DownloadItem) = JSONObject().apply {
        put("id", item.id); put("title", item.title); put("url", item.sourceUrl); put("mime", item.mimeType)
        put("stream", item.streamType.name); put("phase", item.phase.name); put("done", item.downloadedBytes); put("total", item.totalBytes)
        put("estimated", item.estimatedBytes); put("error", item.error); put("destination", item.destination); put("validator", item.validator)
        put("videoKey", item.videoKey); put("audioKey", item.audioKey); put("track", item.trackIndex); put("segBefore", item.segmentsBefore)
        put("segIndex", item.segmentIndex); put("segOffset", item.segmentOffset); put("segCount", item.segmentCount); put("created", item.createdAt)
        // Cookies/Referer/User-Agent are what authorise the request; without them a resume after process death gets 403.
        put("headers", JSONObject(item.requestHeaders))
    }

    private fun decode(json: JSONObject) = DownloadItem(
        id = json.getString("id"), title = json.getString("title"), sourceUrl = json.getString("url"),
        mimeType = json.optString("mime").ifBlank { null }, streamType = runCatching { StreamType.valueOf(json.optString("stream")) }.getOrDefault(StreamType.DIRECT),
        phase = runCatching { TransferPhase.valueOf(json.getString("phase")) }.getOrDefault(TransferPhase.PAUSED),
        downloadedBytes = json.optLong("done"), totalBytes = json.optLong("total").takeIf { it > 0 }, estimatedBytes = json.optLong("estimated").takeIf { it > 0 },
        error = json.optString("error").ifBlank { null }, destination = json.optString("destination").ifBlank { null }, validator = json.optString("validator").ifBlank { null },
        videoKey = json.optString("videoKey").ifBlank { null }, audioKey = json.optString("audioKey").ifBlank { null },
        trackIndex = json.optInt("track"), segmentsBefore = json.optInt("segBefore"),
        segmentIndex = json.optInt("segIndex"), segmentOffset = json.optLong("segOffset"), segmentCount = json.optInt("segCount"),
        createdAt = json.optLong("created").takeIf { it > 0 } ?: System.currentTimeMillis(),
        requestHeaders = json.optJSONObject("headers")?.let { headers -> headers.keys().asSequence().associateWith { headers.getString(it) } }.orEmpty(),
    )

    private const val PREFERENCES = "fetch_downloads"
    private const val DOWNLOADS_KEY = "items"
    /** Raw detections per page; selection decides what is shown, so preview grids must not evict the main video. */
    private const val MAX_CANDIDATES = 80
    private const val PERSIST_DEBOUNCE_MS = 1_000L
    private const val MIN_PROBED_MEDIA_BYTES = 100L * 1024
}
