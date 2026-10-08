package app.fetch.player

import kotlin.math.abs
import kotlin.math.min

/** Pure maths behind the player's gestures and labels, kept free of Android so it is unit-testable. */
object PlayerMath {
    /** A full-width horizontal swipe seeks this far (or the whole video, if shorter). */
    const val SEEK_PER_SCREEN_MS = 100_000L
    const val DOUBLE_TAP_SEEK_MS = 10_000L
    /** Positions this close to either end are not worth resuming from. */
    const val RESUME_MARGIN_MS = 5_000L

    val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

    /** "4:05", "1:02:03"; negative values get a sign ("-0:10") for relative seeks. */
    fun formatTime(ms: Long, withSign: Boolean = false): String {
        val sign = if (ms < 0) "-" else if (withSign) "+" else ""
        val total = abs(ms) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return sign + if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Horizontal drag of [dragPx] across a [widthPx]-wide screen → milliseconds to move, never past either end. */
    fun seekTarget(startMs: Long, dragPx: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0f || durationMs <= 0L) return startMs
        val span = min(durationMs, SEEK_PER_SCREEN_MS)
        val target = startMs + (dragPx / widthPx * span).toLong()
        return target.coerceIn(0L, durationMs)
    }

    /** Vertical drag changes a 0..1 level; dragging up by the full height adds 1. */
    fun adjustLevel(level: Float, dragUpPx: Float, heightPx: Float): Float =
        if (heightPx <= 0f) level else (level + dragUpPx / heightPx).coerceIn(0f, 1f)

    /** Where a saved position should resume from, or 0 when it's too close to the start or the end. */
    fun resumeFrom(savedMs: Long, durationMs: Long): Long = when {
        savedMs < RESUME_MARGIN_MS -> 0L
        durationMs > 0 && savedMs > durationMs - RESUME_MARGIN_MS -> 0L
        else -> savedMs
    }

    fun speedLabel(speed: Float): String = if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"
}
