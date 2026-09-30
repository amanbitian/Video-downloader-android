package app.fetch.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSelectionTest {
    @Test fun `containers follow what MediaMuxer can combine`() {
        assertEquals(Codecs.Container.MP4, Codecs.container("H.264", "AAC"))
        assertEquals(Codecs.Container.WEBM, Codecs.container("VP9", "Opus"))
        assertNull(Codecs.container("H.264", "Opus"))
        assertNull(Codecs.container("VP9", "AAC"))
        assertEquals(Codecs.Container.MP4, Codecs.container(null, "AAC"))
    }

    @Test fun `codec strings map to families`() {
        assertEquals("H.264", Codecs.video("avc1.640028,mp4a.40.2"))
        assertEquals("AAC", Codecs.audio("avc1.640028,mp4a.40.2"))
        assertEquals("H.265", Codecs.video("hev1.1.6.L93.B0"))
        assertNull(Codecs.audio("avc1.640028"))
    }

    @Test fun `audio policy skips incompatible and commentary tracks, then prefers default and bitrate`() {
        val options = listOf(
            AudioOption("opus", "Opus", "en", 160_000, isDefault = true, label = null),
            AudioOption("commentary", "AAC", "en", 256_000, isDefault = false, label = null, isAccessory = true),
            AudioOption("aac-64", "AAC", "en", 64_000, isDefault = false, label = null),
            AudioOption("aac-128", "AAC", "en", 128_000, isDefault = false, label = null),
        )
        assertEquals("aac-128", AudioPolicy.choose(options, "H.264", deviceLanguage = "en")?.key)
        assertEquals("opus", AudioPolicy.choose(options, "VP9", deviceLanguage = "en")?.key)
    }

    @Test fun `device language wins when no track is marked default`() {
        val options = listOf(
            AudioOption("en", "AAC", "en", 192_000, isDefault = false, label = null),
            AudioOption("hi", "AAC", "hi-IN", 128_000, isDefault = false, label = null),
        )
        assertEquals("hi", AudioPolicy.choose(options, "H.264", deviceLanguage = "hi")?.key)
        assertEquals("en", AudioPolicy.choose(options, "H.264", deviceLanguage = "fr")?.key)
    }
}
