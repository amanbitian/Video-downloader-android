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


    @Test fun `separate audio renditions are paired, preferring the default track`() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Commentary",LANGUAGE="en",URI="audio/commentary.m3u8",CHARACTERISTICS="public.accessibility.describes-video"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Hindi",LANGUAGE="hi",URI="audio/hi.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="subs/en.m3u8"
            #EXT-X-STREAM-INF:CODECS="avc1.640028,mp4a.40.2",BANDWIDTH=5000000,RESOLUTION=1280x720,AUDIO="aud",SUBTITLES="subs"
            v720.m3u8
        """.trimIndent()
        val variant = HlsResolver.parse("https://cdn.example.com/m.m3u8", manifest).single()
        assertEquals("720p", variant.label)
        assertEquals("https://cdn.example.com/audio/en.m3u8", variant.audioKey)
        assertTrue(variant.detail, variant.detail.contains("H.264 + AAC"))
        assertTrue(variant.detail, variant.detail.contains("English audio"))
    }

    @Test fun `entries whose audio group can't be merged are skipped`() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="ac3",NAME="English",DEFAULT=YES,URI="a2/index.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",DEFAULT=YES,URI="a1/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,CODECS="avc1.640020,ac-3",RESOLUTION=960x540,AUDIO="ac3"
            v5/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2200000,CODECS="avc1.640020,mp4a.40.2",RESOLUTION=960x540,AUDIO="aac"
            v5/index.m3u8
        """.trimIndent()
        val variant = HlsResolver.parse("https://cdn.example.com/m.m3u8", manifest).single()
        assertEquals("https://cdn.example.com/a1/index.m3u8", variant.audioKey)
    }

    @Test fun `one entry per resolution, most compatible codec first`() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:CODECS="hvc1.1.6.L120,mp4a.40.2",BANDWIDTH=3000000,RESOLUTION=1920x1080
            hevc1080.m3u8
            #EXT-X-STREAM-INF:CODECS="avc1.640028,mp4a.40.2",BANDWIDTH=6000000,RESOLUTION=1920x1080
            avc1080.m3u8
            #EXT-X-STREAM-INF:CODECS="avc1.640028,mp4a.40.2",BANDWIDTH=4000000,RESOLUTION=1920x1080
            avc1080low.m3u8
        """.trimIndent()
        val variants = HlsResolver.parse("https://cdn.example.com/m.m3u8", manifest)
        assertEquals(listOf("https://cdn.example.com/avc1080.m3u8"), variants.map { it.url })
    }

    @Test fun `media playlist segments resolve against the playlist url and sum their duration`() {
        val playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4.5,\nseg0.ts\n#EXTINF:4,\n/abs/seg1.ts\n#EXT-X-ENDLIST"
        val parsed = HlsResolver.mediaPlaylist("https://cdn.example.com/v/index.m3u8", playlist)
        assertEquals(listOf("https://cdn.example.com/v/seg0.ts", "https://cdn.example.com/abs/seg1.ts"), parsed.segments)
        assertEquals(null, parsed.initUrl)
        assertEquals(8.5, parsed.durationSeconds, 0.001)
    }

    @Test fun `fmp4 media playlist exposes its init segment`() {
        val parsed = HlsResolver.mediaPlaylist("https://cdn.example.com/v/a.m3u8", "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\ns1.m4s\n#EXTINF:4,\ns2.m4s")
        assertEquals("https://cdn.example.com/v/init.mp4", parsed.initUrl)
        assertEquals(2, parsed.segments.size)
    }

    @Test fun `byte ranges of one file become a single whole-file download`() {
        val playlist = """
            #EXTM3U
            #EXT-X-MAP:URI="main.mp4",BYTERANGE="721@0"
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:5874288@721
            main.mp4
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:5863101@5875009
            main.mp4
        """.trimIndent()
        val parsed = HlsResolver.mediaPlaylist("https://cdn.example.com/v9/prog_index.m3u8", playlist)
        assertEquals(null, parsed.initUrl)
        assertEquals(listOf("https://cdn.example.com/v9/main.mp4"), parsed.segments)
        assertTrue(parsed.isFragmentedMp4)
        assertEquals(12.0, parsed.durationSeconds, 0.001)
    }

    @Test(expected = UnsupportedStreamException::class)
    fun `encrypted media playlist is rejected with a readable error`() {
        HlsResolver.mediaPlaylist("https://cdn.example.com/a.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\ns.ts")
    }

    @Test(expected = UnsupportedStreamException::class)
    fun `master playlist is not mistaken for a media playlist`() {
        HlsResolver.mediaPlaylist("https://cdn.example.com/a.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8")
    }
}
