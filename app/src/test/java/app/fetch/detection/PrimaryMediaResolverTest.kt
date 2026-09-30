package app.fetch.detection

import app.fetch.detection.PageMediaSnapshot.DomMedia
import app.fetch.download.MediaCandidate
import app.fetch.download.MediaVariant
import app.fetch.download.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryMediaResolverTest {
    private fun element(
        key: String, x: Double, y: Double, w: Double, h: Double, context: String = "", visible: Boolean = true,
        sources: List<String> = emptyList(), blob: Boolean = false, duration: Double? = null,
        muted: Boolean = false, loop: Boolean = false, autoplay: Boolean = false, poster: String? = null,
    ) = DomMedia(key, false, sources, blob, poster, x, y, w, h, visible, duration, muted, autoplay, loop, PageMediaSnapshot.words(context))

    private fun snapshot(vararg elements: DomMedia, ogVideos: List<String> = emptyList(), ld: List<PageMediaSnapshot.StructuredVideo> = emptyList()) =
        PageMediaSnapshot("https://example.com/watch/1", "Road Trip - Example", "Road Trip", "Example", "https://example.com/og.jpg",
            ogVideos, ld, elements.toList(), viewportWidth = 1300.0, viewportHeight = 800.0, pageWidth = 1300.0)

    private fun direct(url: String, key: String? = null, size: Long = 50L * 1024 * 1024) =
        MediaCandidate(url = url, title = url.substringAfterLast('/'), elementKey = key, sizeBytes = size, resolved = true)

    private fun select(candidates: List<MediaCandidate>, snapshot: PageMediaSnapshot?) =
        PrimaryMediaResolver.select(candidates, snapshot, "Road Trip", "example.com")

    @Test fun `main player wins, related sidebar previews are dropped`() {
        val page = snapshot(
            element("m0", 0.0, 100.0, 1000.0, 560.0, "div article main-player"),
            element("m1", 1050.0, 100.0, 240.0, 135.0, "div aside relatedVideos", muted = true, loop = true, autoplay = true, duration = 12.0),
            element("m2", 1050.0, 260.0, 240.0, 135.0, "section up-next", muted = true, loop = true, autoplay = true, duration = 9.0),
        )
        val result = select(listOf(direct("https://cdn/main.mp4", "m0"), direct("https://cdn/p1.mp4", "m1", 2_000_000), direct("https://cdn/p2.mp4", "m2", 2_000_000)), page)
        assertEquals(listOf("https://cdn/main.mp4"), result.map { it.url })
    }

    @Test fun `ad slots and ad servers are never offered, but a player flagged 'ad-playing' still is`() {
        val page = snapshot(
            element("content", 0.0, 100.0, 1000.0, 560.0, "div video-js vjs-ad-playing"),
            element("ad", 0.0, 100.0, 1000.0, 560.0, "div ima-ad-container"),
        )
        val result = select(listOf(direct("https://cdn/show.mp4", "content"), direct("https://cdn/creative.mp4", "ad"), direct("https://ads.doubleclick.net/v.mp4")), page)
        assertEquals(listOf("https://cdn/show.mp4"), result.map { it.url })
    }

    @Test fun `page declared og video beats network-only guesses`() {
        val page = snapshot(ogVideos = listOf("https://cdn/declared.mp4"))
        val result = select(listOf(direct("https://cdn/other.mp4"), direct("https://cdn/declared.mp4")), page)
        assertEquals(listOf("https://cdn/declared.mp4"), result.map { it.url })
    }

    @Test fun `an MSE player is tied to the stream whose length matches`() {
        val page = snapshot(element("player", 0.0, 80.0, 1100.0, 620.0, "div player", blob = true, duration = 600.0))
        val main = MediaCandidate(url = "https://cdn/master.m3u8", title = "x", streamType = StreamType.HLS, resolved = true, durationSeconds = 600.0,
            variants = listOf(MediaVariant("https://cdn/720.m3u8", "720p", "", StreamType.HLS), MediaVariant("https://cdn/360.m3u8", "360p", "", StreamType.HLS)))
        val ad = MediaCandidate(url = "https://cdn/bumper.m3u8", title = "y", streamType = StreamType.HLS, resolved = true, durationSeconds = 15.0,
            variants = listOf(MediaVariant("https://cdn/b.m3u8", "480p", "", StreamType.HLS)))
        assertEquals(listOf("https://cdn/master.m3u8"), select(listOf(ad, main), page).map { it.url })
    }

    @Test fun `hidden players are rejected even when they are the only candidate`() {
        val page = snapshot(element("h", 0.0, 0.0, 0.0, 0.0, visible = false))
        assertTrue(select(listOf(direct("https://cdn/hidden.mp4", "h")), page).isEmpty())
    }

    @Test fun `several genuine clips in an article are all offered`() {
        val page = snapshot(
            element("c1", 250.0, 150.0, 800.0, 450.0, "article figure"),
            element("c2", 250.0, 650.0, 800.0, 450.0, "article figure"),
        )
        val result = select(listOf(direct("https://cdn/clip1.mp4", "c1"), direct("https://cdn/clip2.mp4", "c2")), page)
        assertEquals(listOf("https://cdn/clip1.mp4", "https://cdn/clip2.mp4"), result.map { it.url })
    }

    @Test fun `without page evidence only the single most plausible video is shown`() {
        val result = select(listOf(direct("https://cdn/a.mp4", size = 40_000_000), direct("https://cdn/tiny.mp4", size = 120_000)), null)
        assertEquals(listOf("https://cdn/a.mp4"), result.map { it.url })
    }

    @Test fun `explicit downloads are always kept`() {
        val explicit = MediaCandidate(url = "https://cdn/file.pdf", title = "file.pdf", explicit = true)
        assertEquals(listOf("https://cdn/file.pdf"), select(listOf(explicit), null).map { it.url })
    }

    @Test fun `titles come from video metadata, never from CDN names`() {
        val ld = PageMediaSnapshot.StructuredVideo("Sunset Drive", "https://cdn/main.mp4", null, "https://cdn/thumb.jpg", 42.0)
        val page = snapshot(element("m0", 0.0, 100.0, 1000.0, 560.0, "article"), ld = listOf(ld))
        val top = select(listOf(direct("https://cdn/main.mp4", "m0")), page).single()
        assertEquals("Sunset Drive", top.title)
        assertEquals(42.0, top.durationSeconds!!, 0.001)
        val unnamed = PrimaryMediaResolver.select(listOf(direct("https://cdn/m2-res_1160p.mp4")), null, null, "example.com").single()
        assertEquals("Video from example.com", unnamed.title)
    }
}
