package app.fetch.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleNormalizerTest {
    @Test fun `site suffixes are removed when they name the site`() {
        assertEquals("Amazing Trip", TitleNormalizer.clean("Amazing Trip - YouTube", "YouTube", "m.youtube.com"))
        assertEquals("Amazing Trip", TitleNormalizer.clean("Amazing Trip : Reddit", null, "www.reddit.com"))
        assertEquals("Amazing Trip", TitleNormalizer.clean("Amazing Trip | WebsiteName", "WebsiteName", "cdn.other.net"))
        assertEquals("Match highlights", TitleNormalizer.clean("Match highlights | BBC Sport | BBC", "BBC Sport", "www.bbc.co.uk"))
    }

    @Test fun `legitimate separators inside titles survive`() {
        assertEquals("Spider-Man - Official Trailer", TitleNormalizer.clean("Spider-Man - Official Trailer", "Marvel", "www.marvel.com"))
        assertEquals("A | B", TitleNormalizer.clean("A | B", null, null))
    }

    @Test fun `unread counters are dropped and a title is never emptied`() {
        assertEquals("Home", TitleNormalizer.clean("(3) Home", null, null))
        assertEquals("YouTube", TitleNormalizer.clean("YouTube", "YouTube", "youtube.com"))
    }

    @Test fun `brands from hosts`() {
        assertEquals("youtube", TitleNormalizer.brandOf("m.youtube.com"))
        assertEquals("bbc", TitleNormalizer.brandOf("news.bbc.co.uk"))
        assertEquals("vimeo", TitleNormalizer.brandOf("vimeo.com"))
    }
}
