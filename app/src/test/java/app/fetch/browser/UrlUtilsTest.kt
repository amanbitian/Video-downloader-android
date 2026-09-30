package app.fetch.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {
    @Test fun `address bar input is normalised`() {
        assertEquals("https://example.com", UrlUtils.normalize(" example.com "))
        assertEquals("http://example.com/a", UrlUtils.normalize("http://example.com/a"))
        assertEquals("https://duckduckgo.com/?q=cat+videos", UrlUtils.normalize("cat videos"))
        assertEquals("", UrlUtils.normalize("  "))
    }

    @Test fun `urls are extracted from shared text`() {
        assertEquals("https://vimeo.com/123", UrlUtils.extractUrl("Watch this! https://vimeo.com/123."))
        assertEquals("https://x.com/a/status/1?s=20", UrlUtils.extractUrl("(https://x.com/a/status/1?s=20)"))
        assertNull(UrlUtils.extractUrl("no link here"))
    }

    @Test fun `hosts are parsed without www or credentials`() {
        assertEquals("m.example.com", UrlUtils.host("https://user:pw@m.example.com:8080/path"))
        assertEquals("example.com", UrlUtils.host("https://www.example.com"))
    }

    @Test fun `youtube is blocked including subdomains and video cdn`() {
        assertTrue(UrlUtils.isBlockedSource("https://m.youtube.com/watch?v=1"))
        assertTrue(UrlUtils.isBlockedSource(null, "https://youtu.be/abc"))
        assertTrue(UrlUtils.isBlockedSource("https://rr3---sn-abc.googlevideo.com/videoplayback?x=1"))
        assertFalse(UrlUtils.isBlockedSource("https://notyoutube.com/v.mp4"))
    }

    @Test fun `candidate filter keeps media and drops static assets and segments`() {
        assertTrue(UrlUtils.isCandidateUrl("https://cdn.example.com/v/clip.mp4?token=1"))
        assertTrue(UrlUtils.isCandidateUrl("https://cdn.example.com/master.m3u8"))
        assertFalse(UrlUtils.isCandidateUrl("https://cdn.example.com/seg-001.ts?x=mp4"))
        assertFalse(UrlUtils.isCandidateUrl("https://cdn.example.com/thumb.mp4.jpg"))
        assertFalse(UrlUtils.isCandidateUrl("https://cdn.example.com/app.js"))
    }
}
