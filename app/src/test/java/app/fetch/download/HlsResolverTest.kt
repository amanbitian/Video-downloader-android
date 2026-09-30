package app.fetch.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsResolverTest {
    @Test fun `master playlist produces sorted quality choices with absolute URLs`() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=6221600,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080
            high/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=836280,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=848x480
            mid/index.m3u8
        """.trimIndent()
        val variants = HlsResolver.parse("https://cdn.example.com/path/master.m3u8", manifest)
        assertEquals(listOf("1080p", "480p"), variants.map { it.label })
        assertEquals("https://cdn.example.com/path/high/index.m3u8", variants.first().url)
        assertTrue(variants.first().detail.contains("1920x1080"))
    }

    @Test fun `encrypted manifest exposes no downloadable variant`() {
        assertTrue(HlsResolver.parse("https://cdn.example.com/a.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128").isEmpty())
    }

    @Test fun `quoted attribute commas do not break parsing and separate audio is flagged`() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:CODECS="avc1.640028,mp4a.40.2",BANDWIDTH=5000000,RESOLUTION=1280x720,AUDIO="aud"
            v720.m3u8
        """.trimIndent()
        val variant = HlsResolver.parse("https://cdn.example.com/m.m3u8", manifest).single()
        assertEquals("720p", variant.label)
        assertTrue(variant.detail, variant.detail.contains("H.264"))
        assertTrue(variant.detail, variant.detail.contains("no audio"))
    }

    @Test fun `media playlist segments resolve against the playlist url`() {
        val playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\nseg0.ts\n#EXTINF:4,\n/abs/seg1.ts\n#EXT-X-ENDLIST"
        assertEquals(
            listOf("https://cdn.example.com/v/seg0.ts", "https://cdn.example.com/abs/seg1.ts"),
            HlsResolver.mediaSegments("https://cdn.example.com/v/index.m3u8", playlist)
        )
    }

    @Test(expected = UnsupportedStreamException::class)
    fun `encrypted media playlist is rejected with a readable error`() {
        HlsResolver.mediaSegments("https://cdn.example.com/a.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\ns.ts")
    }

    @Test(expected = UnsupportedStreamException::class)
    fun `fmp4 media playlist is rejected`() {
        HlsResolver.mediaSegments("https://cdn.example.com/a.m3u8", "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\ns.m4s")
    }
}
