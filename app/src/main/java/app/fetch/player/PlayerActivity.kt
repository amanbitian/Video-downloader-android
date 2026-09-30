package app.fetch.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Offline playback of downloaded audio and video, including the .ts files produced by HLS downloads. */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: return finish()
        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        setContentView(playerView)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val item = MediaItem.Builder().setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(intent.getStringExtra(EXTRA_TITLE)).build())
            .build()
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = handOff(uri)
            })
            exo.setMediaItem(item)
            exo.seekTo(savedInstanceState?.getLong(STATE_POSITION) ?: 0L)
            exo.playWhenReady = savedInstanceState?.getBoolean(STATE_PLAYING) ?: true
            exo.prepare()
        }
    }

    /** Formats the built-in player can't decode (e.g. Ogg Theora) go to another installed app instead of a dead screen. */
    private fun handOff(uri: Uri) {
        Toast.makeText(this, "This format can't be played here. Choose another app.", Toast.LENGTH_LONG).show()
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(view, "Open with")) } catch (_: ActivityNotFoundException) { }
        finish()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            outState.putLong(STATE_POSITION, it.currentPosition)
            outState.putBoolean(STATE_PLAYING, it.playWhenReady)
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val STATE_POSITION = "position"
        private const val STATE_PLAYING = "playing"

        fun start(context: Context, uri: Uri, title: String) {
            val intent = Intent(context, PlayerActivity::class.java).setData(uri).putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
