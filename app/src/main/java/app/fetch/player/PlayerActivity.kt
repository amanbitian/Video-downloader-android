package app.fetch.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.fetch.ui.FetchTheme

/**
 * Full-screen offline player (MX Player-style): gestures, lock, aspect modes, speed, tracks, external subtitles,
 * resume, playlist of downloads and picture-in-picture. Also opens video/audio files shared by other apps.
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var positions: PlaybackPositions
    private lateinit var audio: AudioManager
    private val inPip = mutableStateOf(false)
    private val orientation = mutableStateOf(OrientationMode.AUTO)
    /** Resumed position shown once per item as "Resumed at 12:34 · Start over". */
    private val resumed = mutableStateOf<Long?>(null)

    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::attachSubtitle) }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(EXTRA_PIP_ACTION) == PIP_TOGGLE) { if (player.isPlaying) player.pause() else player.play() }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val queue = PlayerQueue.from(intent, contentResolver)
        if (queue == null) { finish(); return }
        positions = PlaybackPositions(this)
        audio = getSystemService(AudioManager::class.java)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        hideSystemBars()

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(PlayerMath.DOUBLE_TAP_SEEK_MS)
            .setSeekForwardIncrementMs(PlayerMath.DOUBLE_TAP_SEEK_MS)
            // Ducks or pauses for calls and other apps, and pauses when headphones are unplugged.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(listener)
        load(queue, savedInstanceState?.getLong(STATE_POSITION))

        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            FetchTheme(dark = true) {
                PlayerScreen(
                    player = player,
                    inPip = inPip.value,
                    orientation = orientation.value,
                    resumedAt = resumed.value,
                    onStartOver = { player.seekTo(0); resumed.value = null },
                    onResumeNoticeShown = { resumed.value = null },
                    onBack = ::finish,
                    onRotate = ::cycleOrientation,
                    onPip = ::enterPip,
                    onLoadSubtitle = { subtitlePicker.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "*/*")) },
                    brightness = ::currentBrightness,
                    onBrightness = ::setBrightness,
                    volume = { audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) },
                    onVolume = ::setVolume,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A new video while one is playing (e.g. from picture-in-picture): save the old one and switch.
        PlayerQueue.from(intent, contentResolver)?.let { savePosition(); load(it, null) }
    }

    private fun load(queue: PlayerQueue, restoredPosition: Long?) {
        player.setMediaItems(queue.items, queue.startIndex, restoredPosition ?: C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            resumed.value = null
        }

        override fun onPlaybackStateChanged(state: Int) {
            // Resume once the duration is known, so a position near the end can be recognised as "finished".
            if (state == Player.STATE_READY) {
                val item = player.currentMediaItem ?: return
                if (item.mediaId != lastResumeCheck) {
                    lastResumeCheck = item.mediaId
                    val from = positions.resumePosition(item.mediaId, player.duration)
                    if (from > 0 && player.currentPosition < PlayerMath.RESUME_MARGIN_MS) { player.seekTo(from); resumed.value = from }
                }
            }
            if (state == Player.STATE_ENDED) player.currentMediaItem?.let { positions.clear(it.mediaId) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            applyOrientation()
            updatePipParams()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePipParams()
            if (!isPlaying) savePosition()
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@PlayerActivity, "This format can't be played here. Choose another app.", Toast.LENGTH_LONG).show()
            val uri = player.currentMediaItem?.localConfiguration?.uri ?: return finish()
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { startActivity(Intent.createChooser(view, "Open with")) } catch (_: ActivityNotFoundException) { }
            finish()
        }
    }
    private var lastResumeCheck: String? = null

    /** MX-style: follow the video's shape until the user picks an orientation with the rotate button. */
    private fun applyOrientation() {
        val size = player.videoSize
        requestedOrientation = when (orientation.value) {
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            OrientationMode.AUTO -> when {
                size.width == 0 || size.height == 0 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                size.width * size.pixelWidthHeightRatio >= size.height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    private fun cycleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        orientation.value = if (landscape) OrientationMode.PORTRAIT else OrientationMode.LANDSCAPE
        applyOrientation()
    }

    private fun attachSubtitle(uri: Uri) {
        val item = player.currentMediaItem ?: return
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
        val mime = when {
            name.endsWith(".vtt", true) -> "text/vtt"
            name.endsWith(".ssa", true) || name.endsWith(".ass", true) -> "text/x-ssa"
            name.endsWith(".ttml", true) || name.endsWith(".xml", true) -> "application/ttml+xml"
            else -> "application/x-subrip"
        }
        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setLabel(name.ifBlank { "External" }).build()
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        player.replaceMediaItem(index, item.buildUpon().setSubtitleConfigurations(listOf(subtitle)).build())
        player.seekTo(index, position)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        Toast.makeText(this, "Subtitles: ${name.ifBlank { "loaded" }}", Toast.LENGTH_SHORT).show()
    }

    private fun currentBrightness(): Float {
        val window = window.attributes.screenBrightness
        if (window >= 0) return window
        val system = runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return system / 255f
    }

    /** Only this window's brightness changes; the system setting is untouched and returns when the player closes. */
    private fun setBrightness(level: Float) {
        window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) }
    }

    private fun setVolume(level: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt().coerceIn(0, max), 0)
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val size = player.videoSize
        val builder = PictureInPictureParams.Builder()
        if (size.width > 0 && size.height > 0) {
            // Android rejects ratios beyond 2.39:1; clamp so very wide or tall videos still get a window.
            val ratio = (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(1 / 2.39f, 2.39f)
            builder.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        val playing = player.isPlaying
        val action = RemoteAction(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "Pause" else "Play", if (playing) "Pause" else "Play",
            PendingIntent.getBroadcast(this, 1, Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP_ACTION, PIP_TOGGLE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        return builder.setActions(listOf(action)).build()
    }

    private fun updatePipParams() {
        if (inPip.value) runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** Leaving the app mid-video shrinks it into a floating window instead of stopping it. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying && player.videoSize.width > 0) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode) hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        // Closing the picture-in-picture window stops the activity; a video must not keep playing unseen.
        player.pause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_POSITION, player.currentPosition)
    }

    override fun onDestroy() {
        if (::player.isInitialized) {
            savePosition()
            player.removeListener(listener)
            player.release()
            runCatching { unregisterReceiver(pipReceiver) }
        }
        super.onDestroy()
    }

    private fun savePosition() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        if (player.duration > 0) positions.save(item.mediaId, player.currentPosition, player.duration)
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    enum class OrientationMode { AUTO, LANDSCAPE, PORTRAIT }

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_INDEX = "index"
        private const val STATE_POSITION = "position"
        private const val ACTION_PIP = "app.fetch.player.PIP"
        private const val EXTRA_PIP_ACTION = "pip_action"
        private const val PIP_TOGGLE = "toggle"

        /** Plays [items] (uri to title) starting at [index]; next/previous and auto-advance move through the list. */
        fun start(context: Context, items: List<Pair<Uri, String>>, index: Int) {
            val intent = Intent(context, PlayerActivity::class.java)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.first }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(items.map { it.second }))
                .putExtra(EXTRA_INDEX, index)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun start(context: Context, uri: Uri, title: String) = start(context, listOf(uri to title), 0)
    }

    /** What to play: a list of downloads from inside the app, or a single file opened from another app. */
    private class PlayerQueue(val items: List<MediaItem>, val startIndex: Int) {
        companion object {
            fun from(intent: Intent, resolver: android.content.ContentResolver): PlayerQueue? {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)
                val titles = intent.getStringArrayListExtra(EXTRA_TITLES)
                if (!uris.isNullOrEmpty()) {
                    val items = uris.mapIndexed { i, uri -> item(uri, titles?.getOrNull(i) ?: uri.lastPathSegment.orEmpty()) }
                    return PlayerQueue(items, intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(items.indices))
                }
                val uri = intent.data ?: return null
                val name = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull() ?: uri.lastPathSegment.orEmpty()
                return PlayerQueue(listOf(item(uri, name.substringBeforeLast('.'))), 0)
            }

            private fun item(uri: Uri, title: String) = MediaItem.Builder().setUri(uri).setMediaId(uri.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
        }
    }
}
