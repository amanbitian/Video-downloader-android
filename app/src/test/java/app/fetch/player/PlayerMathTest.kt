package app.fetch.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerMathTest {
    @Test fun `times format like a player clock`() {
        assertEquals("0:00", PlayerMath.formatTime(0))
        assertEquals("4:05", PlayerMath.formatTime(245_000))
        assertEquals("1:02:03", PlayerMath.formatTime(3_723_000))
        assertEquals("+0:15", PlayerMath.formatTime(15_000, withSign = true))
        assertEquals("-0:10", PlayerMath.formatTime(-10_000, withSign = true))
    }

    @Test fun `horizontal swipe seeks proportionally and stays inside the video`() {
        // Half the screen on a long video = 50 s.
        assertEquals(110_000L, PlayerMath.seekTarget(60_000, 540f, 1080f, 600_000))
        // On a 30 s clip a full swipe covers the clip, not 100 s.
        assertEquals(30_000L, PlayerMath.seekTarget(10_000, 1080f, 1080f, 30_000))
        assertEquals(0L, PlayerMath.seekTarget(5_000, -1080f, 1080f, 600_000))
        assertEquals(5_000L, PlayerMath.seekTarget(5_000, 300f, 0f, 600_000))
    }

    @Test fun `vertical swipe adjusts levels within 0 and 1`() {
        assertEquals(0.75f, PlayerMath.adjustLevel(0.5f, 500f, 2000f), 0.0001f)
        assertEquals(1f, PlayerMath.adjustLevel(0.9f, 1000f, 2000f), 0.0001f)
        assertEquals(0f, PlayerMath.adjustLevel(0.1f, -1000f, 2000f), 0.0001f)
    }

    @Test fun `resume skips the very start and the very end`() {
        assertEquals(0L, PlayerMath.resumeFrom(3_000, 600_000))
        assertEquals(120_000L, PlayerMath.resumeFrom(120_000, 600_000))
        assertEquals(0L, PlayerMath.resumeFrom(597_000, 600_000))
        assertEquals(120_000L, PlayerMath.resumeFrom(120_000, 0))
    }

    @Test fun `speed labels`() {
        assertEquals("2×", PlayerMath.speedLabel(2f))
        assertEquals("1.25×", PlayerMath.speedLabel(1.25f))
    }
}
