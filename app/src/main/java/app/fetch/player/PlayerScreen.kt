package app.fetch.player

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Gesture { NONE, SEEK, BRIGHTNESS, VOLUME, ZOOM }

/** How the picture fills the screen, cycled by the aspect button (MX's fit / crop / stretch). */
@OptIn(UnstableApi::class)
private enum class FitMode(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    CROP("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

/** Player state mirrored into Compose; positions are polled, everything else follows [Player.Listener]. */
private class PlayerUiState(player: Player) {
    var isPlaying by mutableStateOf(player.isPlaying)
    var title by mutableStateOf(player.mediaMetadata.title?.toString().orEmpty())
    var duration by mutableLongStateOf(player.duration.coerceAtLeast(0))
    var position by mutableLongStateOf(player.currentPosition)
    var hasPrevious by mutableStateOf(player.hasPreviousMediaItem())
    var hasNext by mutableStateOf(player.hasNextMediaItem())
    var repeatMode by mutableIntStateOf(player.repeatMode)
    var speed by mutableFloatStateOf(player.playbackParameters.speed)
    var tracks by mutableStateOf(player.currentTracks)
    var ended by mutableStateOf(false)

    fun sync(player: Player) {
        isPlaying = player.isPlaying
        title = player.mediaMetadata.title?.toString().orEmpty()
        duration = player.duration.coerceAtLeast(0)
        position = player.currentPosition
        hasPrevious = player.hasPreviousMediaItem()
        hasNext = player.hasNextMediaItem()
        repeatMode = player.repeatMode
        speed = player.playbackParameters.speed
        tracks = player.currentTracks
        ended = player.playbackState == Player.STATE_ENDED
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    player: Player,
    inPip: Boolean,
    orientation: PlayerActivity.OrientationMode,
    resumedAt: Long?,
    onStartOver: () -> Unit,
    onResumeNoticeShown: () -> Unit,
    onBack: () -> Unit,
    onRotate: () -> Unit,
    onPip: () -> Unit,
    onLoadSubtitle: () -> Unit,
    brightness: () -> Float,
    onBrightness: (Float) -> Unit,
    volume: () -> Float,
    onVolume: (Float) -> Unit,
) {
    val state = remember(player) { PlayerUiState(player) }
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var fit by remember { mutableStateOf(FitMode.FIT) }
    var zoom by remember { mutableFloatStateOf(1f) }
    /** Transient centre message: "Brightness 60%", "+0:15 / 1:23", "2× speed"… with an optional 0..1 level bar. */
    var feedback by remember { mutableStateOf<Feedback?>(null) }
    var feedbackJob by remember { mutableStateOf<Job?>(null) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var doubleTapSide by remember { mutableIntStateOf(0) }
    var boosted by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var showSpeed by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    var sliderDrag by remember { mutableStateOf<Float?>(null) }

    fun flash(message: Feedback, holdMs: Long = 700) {
        feedback = message
        feedbackJob?.cancel()
        feedbackJob = scope.launch { delay(holdMs); feedback = null }
    }
    fun poke() { interaction++ }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = state.sync(player)
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            state.position = player.currentPosition
            if (player.duration > 0) state.duration = player.duration
            delay(if (controlsVisible) 250 else 1_000)
        }
    }
    // Controls fade out while playing; any touch on them restarts the timer.
    LaunchedEffect(controlsVisible, state.isPlaying, interaction, sliderDrag) {
        if (controlsVisible && state.isPlaying && sliderDrag == null) { delay(3_500); controlsVisible = false }
    }
    LaunchedEffect(showUnlock) { if (showUnlock) { delay(3_000); showUnlock = false } }
    LaunchedEffect(doubleTapSide) { if (doubleTapSide != 0) { delay(600); doubleTapSide = 0 } }
    LaunchedEffect(resumedAt) { if (resumedAt != null) { delay(6_000); onResumeNoticeShown() } }

    val edgeGuard = with(LocalDensity.current) { 48.dp.toPx() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setBackgroundColor(AndroidColor.BLACK)
                    keepScreenOn = true
                    this.player = player
                }
            },
            update = { view ->
                view.resizeMode = fit.resizeMode
                view.scaleX = zoom
                view.scaleY = zoom
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (inPip) return@Box

        // Gesture layer: taps (show/hide, double-tap seek, hold for 2×) and drags (seek, brightness, volume, pinch zoom).
        Box(
            Modifier.fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { if (locked) showUnlock = true else controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            if (locked) return@detectTapGestures
                            val third = size.width / 3f
                            when {
                                offset.x < third -> { player.seekBack(); doubleTapSide = -1 }
                                offset.x > third * 2 -> { player.seekForward(); doubleTapSide = 1 }
                                else -> if (player.isPlaying) player.pause() else player.play()
                            }
                        },
                        onPress = {
                            if (locked) return@detectTapGestures
                            // Holding plays at 2× until the finger lifts (MX / YouTube style), unless the press turns into a drag.
                            val hold = scope.launch {
                                delay(450)
                                if (!dragging && player.isPlaying) {
                                    boosted = true
                                    player.setPlaybackSpeed(state.speed * 2)
                                    feedback = Feedback(Icons.Default.FastForward, "${PlayerMath.speedLabel(state.speed * 2)} speed")
                                }
                            }
                            tryAwaitRelease()
                            hold.cancel()
                            if (boosted) { boosted = false; player.setPlaybackSpeed(state.speed); feedback = null }
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Leave the screen edges to the system's own swipe gestures.
                        if (down.position.y < edgeGuard || down.position.y > size.height - edgeGuard) return@awaitEachGesture
                        var mode = Gesture.NONE
                        var total = Offset.Zero
                        val startPosition = player.currentPosition
                        val startBrightness = brightness()
                        val startVolume = volume()
                        var target = startPosition
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2 && (mode == Gesture.NONE || mode == Gesture.ZOOM)) {
                                mode = Gesture.ZOOM
                                dragging = true
                                zoom = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                                feedback = Feedback(Icons.Default.ZoomIn, "Zoom ${(zoom * 100).roundToInt()}%")
                                event.changes.forEach { it.consume() }
                            } else if (mode != Gesture.ZOOM) {
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                total += change.positionChange()
                                if (mode == Gesture.NONE && total.getDistance() > viewConfiguration.touchSlop) {
                                    mode = when {
                                        abs(total.x) > abs(total.y) -> Gesture.SEEK
                                        down.position.x < size.width / 2 -> Gesture.BRIGHTNESS
                                        else -> Gesture.VOLUME
                                    }
                                    dragging = true
                                }
                                when (mode) {
                                    Gesture.SEEK -> {
                                        target = PlayerMath.seekTarget(startPosition, total.x, size.width.toFloat(), state.duration)
                                        seekPreview = target
                                        feedback = Feedback(null, "${PlayerMath.formatTime(target - startPosition, withSign = true)}   ${PlayerMath.formatTime(target)} / ${PlayerMath.formatTime(state.duration)}")
                                    }
                                    Gesture.BRIGHTNESS -> {
                                        val level = PlayerMath.adjustLevel(startBrightness, -total.y, size.height * 0.8f)
                                        onBrightness(level)
                                        feedback = Feedback(Icons.Default.BrightnessMedium, "Brightness ${(level * 100).roundToInt()}%", level)
                                    }
                                    Gesture.VOLUME -> {
                                        val level = PlayerMath.adjustLevel(startVolume, -total.y, size.height * 0.8f)
                                        onVolume(level)
                                        feedback = Feedback(Icons.Default.VolumeUp, "Volume ${(level * 100).roundToInt()}%", level)
                                    }
                                    else -> Unit
                                }
                                if (mode != Gesture.NONE) change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        if (mode == Gesture.SEEK) { player.seekTo(target); seekPreview = null }
                        if (mode != Gesture.NONE) flash(feedback ?: Feedback(null, ""), 500)
                        dragging = false
                    }
                }
        )

        // Double-tap seek hint on the tapped side.
        if (doubleTapSide != 0) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.33f).align(if (doubleTapSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Text(if (doubleTapSide < 0) "«  10s" else "10s  »", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Centred over the video; moved above the centre controls while they're showing so it doesn't cover play/pause.
        feedback?.let {
            val overControls = controlsVisible && !locked
            FeedbackPill(it, Modifier.align(if (overControls) Alignment.TopCenter else Alignment.Center).padding(top = if (overControls) 72.dp else 0.dp))
        }

        // Controls.
        AnimatedVisibility(visible = controlsVisible && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.25f to Color.Black.copy(alpha = 0.25f), 0.7f to Color.Black.copy(alpha = 0.25f), 1f to Color.Black.copy(alpha = 0.7f))))
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    // Top: back, title, speed, tracks, more.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                        Text(state.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { poke(); showSpeed = true }) { Text(PlayerMath.speedLabel(state.speed), color = Color.White, fontWeight = FontWeight.SemiBold) }
                        IconButton(onClick = { poke(); showTracks = true }) { Icon(Icons.Default.ClosedCaption, "Audio and subtitles", tint = Color.White) }
                        MoreMenu(state, player, onLoadSubtitle, onPip, onInteract = ::poke)
                    }
                    Spacer(Modifier.weight(1f))
                    // Centre: previous, −10 s, play/pause, +10 s, next.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        CenterButton(Icons.Default.SkipPrevious, "Previous", enabled = state.hasPrevious || state.position > 3_000) { poke(); player.seekToPrevious() }
                        CenterButton(Icons.Default.Replay10, "Back 10 seconds") { poke(); player.seekBack() }
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.45f), modifier = Modifier.size(68.dp).clickable {
                            poke()
                            when {
                                state.ended -> { player.seekTo(0); player.play() }
                                player.isPlaying -> player.pause()
                                else -> player.play()
                            }
                        }) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(40.dp))
                            }
                        }
                        CenterButton(Icons.Default.Forward10, "Forward 10 seconds") { poke(); player.seekForward() }
                        CenterButton(Icons.Default.SkipNext, "Next", enabled = state.hasNext) { poke(); player.seekToNext() }
                    }
                    Spacer(Modifier.weight(1f))
                    // Bottom: time, seek bar, then lock / aspect / rotate / PiP.
                    val shown = sliderDrag?.let { (it * state.duration).toLong() } ?: seekPreview ?: state.position
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(PlayerMath.formatTime(shown), color = Color.White, fontSize = 13.sp)
                        SeekBar(
                            fraction = if (state.duration > 0) (shown.toFloat() / state.duration).coerceIn(0f, 1f) else 0f,
                            onDrag = { sliderDrag = it; poke() },
                            onDragEnd = { sliderDrag?.let { player.seekTo((it * state.duration).toLong()) }; sliderDrag = null },
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text(PlayerMath.formatTime(state.duration), color = Color.White, fontSize = 13.sp)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { locked = true; controlsVisible = false; showUnlock = true }) { Icon(Icons.Default.Lock, "Lock controls", tint = Color.White) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            poke()
                            fit = FitMode.entries[(fit.ordinal + 1) % FitMode.entries.size]
                            zoom = 1f
                            flash(Feedback(Icons.Default.AspectRatio, fit.label))
                        }) { Icon(Icons.Default.AspectRatio, "Aspect: ${fit.label}", tint = Color.White) }
                        IconButton(onClick = { poke(); onRotate() }) {
                            Icon(Icons.Default.ScreenRotation, if (orientation == PlayerActivity.OrientationMode.AUTO) "Rotate" else "Rotate (locked)", tint = Color.White)
                        }
                        IconButton(onClick = onPip) { Icon(Icons.Default.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                    }
                }
            }
        }

        // Locked: only an unlock button, shown briefly on tap.
        if (locked && showUnlock) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.safeDrawingPadding().padding(16.dp).align(Alignment.CenterStart).size(52.dp)
                .clickable { locked = false; showUnlock = false; controlsVisible = true }) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.LockOpen, "Unlock", tint = Color.White) }
            }
        }

        // "Resumed at 12:34 · Start over"
        if (resumedAt != null && !locked) {
            Surface(shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier.safeDrawingPadding().padding(start = 16.dp, bottom = 120.dp).align(Alignment.BottomStart)) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Resumed at ${PlayerMath.formatTime(resumedAt)}", color = Color.White, fontSize = 13.sp)
                    TextButton(onClick = onStartOver) { Text("Start over") }
                }
            }
        }
    }

    if (showSpeed) SpeedDialog(state.speed, onPick = { player.setPlaybackSpeed(it); showSpeed = false }, onDismiss = { showSpeed = false })
    if (showTracks) TracksDialog(state.tracks, player, onLoadSubtitle = { showTracks = false; onLoadSubtitle() }, onDismiss = { showTracks = false })
}

private data class Feedback(val icon: ImageVector?, val text: String, val level: Float? = null)

@Composable
private fun FeedbackPill(feedback: Feedback, modifier: Modifier) {
    if (feedback.text.isEmpty()) return
    Surface(shape = RoundedCornerShape(14.dp), color = Color.Black.copy(alpha = 0.65f), modifier = modifier) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp).widthIn(min = 140.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                feedback.icon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
                Text(feedback.text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            feedback.level?.let {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { it }, color = Color.White, trackColor = Color.White.copy(alpha = 0.25f), gapSize = 0.dp, drawStopIndicator = {}, modifier = Modifier.width(140.dp))
            }
        }
    }
}

/** Slim MX-style seek bar: a thin track with a dot thumb that grows while dragging, tap or drag anywhere to seek. */
@Composable
private fun SeekBar(fraction: Float, onDrag: (Float) -> Unit, onDragEnd: () -> Unit, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    var dragging by remember { mutableStateOf(false) }
    // The gesture detectors outlive recompositions; always call the latest callbacks (they capture the current duration).
    val onDrag by rememberUpdatedState(onDrag)
    val onDragEnd by rememberUpdatedState(onDragEnd)
    Canvas(
        modifier
            .height(32.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f); contentDescription = "Seek bar" }
            .pointerInput(Unit) {
                detectTapGestures { onDrag((it.x / size.width).coerceIn(0f, 1f)); onDragEnd() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; onDrag((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging = false; onDragEnd() },
                    onDragCancel = { dragging = false; onDragEnd() },
                ) { change, _ -> change.consume(); onDrag((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val y = size.height / 2
        val track = 3.dp.toPx()
        val x = size.width * fraction
        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), track, StrokeCap.Round)
        drawLine(accent, Offset(0f, y), Offset(x, y), track, StrokeCap.Round)
        drawCircle(accent, radius = (if (dragging) 9 else 6).dp.toPx(), center = Offset(x, y))
    }
}

@Composable
private fun CenterButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp)) {
        Icon(icon, description, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun MoreMenu(state: PlayerUiState, player: Player, onLoadSubtitle: () -> Unit, onPip: () -> Unit, onInteract: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { onInteract(); open = true }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val (repeatLabel, repeatIcon) = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> "Repeat: this video" to Icons.Default.RepeatOne
                Player.REPEAT_MODE_ALL -> "Repeat: all" to Icons.Default.Repeat
                else -> "Repeat: off" to Icons.Default.Repeat
            }
            DropdownMenuItem(text = { Text(repeatLabel) }, leadingIcon = { Icon(repeatIcon, null) }, onClick = {
                player.repeatMode = when (state.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                    Player.REPEAT_MODE_ONE -> if (player.mediaItemCount > 1) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                    else -> Player.REPEAT_MODE_OFF
                }
            })
            DropdownMenuItem(text = { Text("Load subtitle file…") }, leadingIcon = { Icon(Icons.Default.ClosedCaption, null) }, onClick = { open = false; onLoadSubtitle() })
            DropdownMenuItem(text = { Text("Picture in picture") }, leadingIcon = { Icon(Icons.Default.PictureInPictureAlt, null) }, onClick = { open = false; onPip() })
        }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedDialog(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback speed") },
        text = {
            // Wraps instead of scrolling so every speed is visible on any width.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerMath.SPEEDS.forEach { speed ->
                    FilterChip(selected = speed == current, onClick = { onPick(speed) }, label = { Text(PlayerMath.speedLabel(speed)) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Audio and subtitle tracks of the current video, plus loading an external subtitle file. */
@Composable
private fun TracksDialog(tracks: Tracks, player: Player, onLoadSubtitle: () -> Unit, onDismiss: () -> Unit) {
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group -> (0 until group.length).map { group to it } }
    val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group -> (0 until group.length).map { group to it } }
    val textDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes

    fun select(group: Tracks.Group, index: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .setTrackTypeDisabled(group.type, false)
            .build()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio & subtitles") },
        text = {
            LazyColumn {
                item { SectionTitle("Audio") }
                if (audio.isEmpty()) item { Text("No audio track", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
                items(audio) { (group, index) ->
                    TrackRow(trackLabel(group, index, "Track"), group.isTrackSelected(index)) { select(group, index); onDismiss() }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); SectionTitle("Subtitles") }
                item {
                    TrackRow("Off", textDisabled || text.none { (g, i) -> g.isTrackSelected(i) }) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        onDismiss()
                    }
                }
                items(text) { (group, index) ->
                    TrackRow(trackLabel(group, index, "Subtitles"), !textDisabled && group.isTrackSelected(index)) { select(group, index); onDismiss() }
                }
                item { TextButton(onClick = onLoadSubtitle) { Text("Load subtitle file…") } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp)
    }
}

private fun trackLabel(group: Tracks.Group, index: Int, fallback: String): String {
    val format = group.getTrackFormat(index)
    val language = format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
    val channels = if (format.channelCount > 0) when (format.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${format.channelCount} ch" } else null
    return listOfNotNull(format.label ?: language ?: "$fallback ${index + 1}", channels).joinToString(" · ")
}
