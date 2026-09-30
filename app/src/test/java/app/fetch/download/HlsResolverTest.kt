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
}
