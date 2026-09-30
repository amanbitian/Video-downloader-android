package app.fetch.detection

import app.fetch.adblock.AdRules
import app.fetch.adblock.RequestBlocker
import app.fetch.adblock.RequestDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdAndFilterTest {
    @Test fun `ad media urls`() {
        assertTrue(AdMediaClassifier.isAdUrl("https://pubads.g.doubleclick.net/gampad/ads?iu=x"))
        assertTrue(AdMediaClassifier.isAdUrl("https://cdn.example.com/vast/creative-123.mp4"))
        assertTrue(AdMediaClassifier.isAdUrl("https://cdn.example.com/ads/preroll.mp4"))
        assertFalse(AdMediaClassifier.isAdUrl("https://cdn.example.com/uploads/2024/video.mp4"))
        assertFalse(AdMediaClassifier.isAdUrl("https://cdn.example.com/shadow/headline.mp4"))
    }

    @Test fun `vast and vmap documents`() {
        assertTrue(AdMediaClassifier.isVastDocument("<?xml version=\"1.0\"?>\n<VAST version=\"4.0\"><Ad>"))
        assertTrue(AdMediaClassifier.isVastDocument("<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\">"))
        assertFalse(AdMediaClassifier.isVastDocument("#EXTM3U\n#EXT-X-VERSION:3"))
    }

    @Test fun `page context words`() {
        assertTrue(AdMediaClassifier.isAdContext(PageMediaSnapshot.words("div ima-ad-container")))
        assertFalse(AdMediaClassifier.isAdContext(PageMediaSnapshot.words("div video-js vjs-ad-playing")))
        assertFalse(AdMediaClassifier.isAdContext(PageMediaSnapshot.words("header download-button shadow")))
        assertTrue(AdMediaClassifier.isRelatedContext(PageMediaSnapshot.words("div relatedVideos")))
        assertTrue(AdMediaClassifier.isRelatedContext(PageMediaSnapshot.words("section up-next")))
        assertFalse(AdMediaClassifier.isRelatedContext(PageMediaSnapshot.words("div feed-item article")))
    }

    @Test fun `request blocker`() {
        val page = "https://news.example.com/story"
        fun decide(url: String, enabled: Boolean = true, allowed: Set<String> = emptySet(), mainFrame: Boolean = false) =
            RequestBlocker.classify(page, url, mainFrame, enabled, allowed)
        assertEquals(RequestDecision.BLOCK_AD, decide("https://securepubads.g.doubleclick.net/tag/js/gpt.js"))
        assertEquals(RequestDecision.BLOCK_TRACKER, decide("https://www.google-analytics.com/analytics.js"))
        assertEquals(RequestDecision.BLOCK_AD, decide("https://thirdparty-cdn.net/ads/banner.js"))
        assertEquals(RequestDecision.ALLOW, decide("https://static.example.com/ads/house-banner.png"), )
        assertEquals(RequestDecision.ALLOW, decide("https://cdn.example.net/video/main.mp4"))
        assertEquals(RequestDecision.ALLOW, decide("https://securepubads.g.doubleclick.net/x", enabled = false))
        assertEquals(RequestDecision.ALLOW, decide("https://securepubads.g.doubleclick.net/x", allowed = setOf("example.com")))
        assertEquals(RequestDecision.ALLOW, decide("https://ads.doubleclick.net/landing", mainFrame = true))
    }

    @Test fun `host rules match the domain and its subdomains, not look-alikes`() {
        assertEquals(RequestDecision.BLOCK_AD, AdRules.classifyHost("doubleclick.net"))
        assertEquals(RequestDecision.BLOCK_AD, AdRules.classifyHost("a.b.securepubads.g.doubleclick.net"))
        assertEquals(RequestDecision.ALLOW, AdRules.classifyHost("notdoubleclick.net"))
        assertEquals(RequestDecision.ALLOW, AdRules.classifyHost("doubleclick.net.example.com"))
        assertEquals(RequestDecision.BLOCK_TRACKER, AdRules.classifyHost("mc.yandex.ru"))
        assertEquals(RequestDecision.ALLOW, AdRules.classifyHost("yandex.ru"))
    }

    @Test fun `blocker stays fast on a busy page`() {
        AdRules.clearCache()
        val hosts = listOf("cdn.example.com", "img.example.com", "securepubads.g.doubleclick.net", "www.google-analytics.com", "fonts.gstatic.com", "static.thirdparty.net")
        val urls = hosts.map { "https://$it/assets/script-${it.length}.js?v=1" }
        // A heavy news page makes a few hundred requests; 200 000 is several hundred page loads.
        val start = System.nanoTime()
        var blocked = 0
        repeat(200_000) { i ->
            val index = i % hosts.size
            if (RequestBlocker.classify("news.example.com", hosts[index], urls[index], isMainFrame = false, enabled = true, allowedSites = emptySet()) != RequestDecision.ALLOW) blocked++
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        // Hosts 2 (doubleclick) and 3 (google-analytics) are blocked; the rest, including the third-party CDN, are not.
        assertEquals((0 until 200_000).count { it % hosts.size == 2 || it % hosts.size == 3 }, blocked)
        assertTrue("200k decisions took $millis ms", millis < 1_500)
        assertTrue(AdRules.cachedHostCount() <= hosts.size)
    }

    @Test fun `titles never come from machine names`() {
        assertEquals("Sunset Drive", FilenameResolver.title("Sunset Drive", "Page", null, "example.com"))
        assertEquals("Road Trip", FilenameResolver.title(null, "Road Trip", "m2-res_1160p.mp4", "example.com"))
        assertEquals("Video from example.com", FilenameResolver.title(null, null, "193039199_mp4_h264", "www.example.com"))
        assertTrue(FilenameResolver.looksMachineMade("m2-res_480p"))
        assertTrue(FilenameResolver.looksMachineMade("index.m3u8"))
        assertFalse(FilenameResolver.looksMachineMade("Episode 12"))
        assertFalse(FilenameResolver.looksMachineMade("2024"))
        assertEquals("Road Trip_ Part 2.mp4", FilenameResolver.fileName("Road Trip: Part 2", "mp4"))
    }

    @Test fun `snapshot parsing`() {
        val json = """
            {"url":"https://example.com/v","title":"T","ogTitle":"OG","site":"Ex","image":"https://example.com/i.jpg",
             "ogVideos":["https://cdn/v.mp4","not-a-url"],
             "ld":[{"name":"Clip","contentUrl":"https://cdn/v.mp4","embedUrl":"","thumbnailUrl":"","duration":"PT1M5S"}],
             "videos":[{"key":"m0","tag":"video","sources":["blob:https://example.com/abc","https://cdn/v.mp4"],"poster":"",
               "x":0,"y":10,"w":640,"h":360,"visible":true,"duration":65.0,"muted":false,"autoplay":false,"loop":false,
               "context":["VIDEO  video-player","DIV relatedVideos"]}],
             "vw":400,"vh":800,"pw":400}
        """.trimIndent()
        val snapshot = PageMediaSnapshot.parse(json)!!
        assertEquals(listOf("https://cdn/v.mp4"), snapshot.ogVideos)
        assertEquals(65.0, snapshot.structuredVideos.single().durationSeconds!!, 0.001)
        val element = snapshot.elements.single()
        assertTrue(element.isBlob)
        assertEquals(listOf("https://cdn/v.mp4"), element.sources)
        assertTrue("related" in element.contextWords && "videos" in element.contextWords)
        assertEquals(640.0 * 360.0, element.area, 0.1)
    }
}
