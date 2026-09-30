package app.fetch.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGroupingTest {
    @Test fun `qualities of one video share a stem`() {
        val low = MediaGrouping.stem("https://cdn.example.com/v/m2-res_480p.mp4?token=A")
        val high = MediaGrouping.stem("https://cdn.example.com/v/m2-res_1160p.mp4?token=B")
        assertEquals("https://cdn.example.com/v/m2-res_{q}.mp4", low)
        assertEquals(low, high)
        assertEquals(MediaGrouping.stem("https://v.example.com/abc/DASH_720.mp4"), MediaGrouping.stem("https://v.example.com/abc/DASH_1080.mp4"))
        assertEquals(MediaGrouping.stem("https://x.example.com/vid/1280x720/clip.mp4"), MediaGrouping.stem("https://x.example.com/vid/640x360/clip.mp4"))
    }

    @Test fun `files without a quality marker are not grouped`() {
        assertNull(MediaGrouping.stem("https://cdn.example.com/v/clip.mp4"))
        assertNull(MediaGrouping.stem("https://cdn.example.com/2024/clip_2024.mp4"))
    }

    @Test fun `heights are read from the name`() {
        assertEquals(1160, MediaGrouping.heightOf("https://cdn.example.com/v/m2-res_1160p.mp4"))
        assertEquals(720, MediaGrouping.heightOf("https://x.example.com/vid/1280x720/clip.mp4"))
        assertEquals(1080, MediaGrouping.heightOf("https://v.example.com/abc/DASH_1080.mp4?x=1"))
        assertNull(MediaGrouping.heightOf("https://cdn.example.com/v/clip.mp4"))
    }

    @Test fun `slice requests are recognised by closed ranges or range query parameters`() {
        assertTrue(MediaGrouping.isSliceRequest("https://cdn/v.mp4", "bytes=0-1023"))
        assertFalse(MediaGrouping.isSliceRequest("https://cdn/v.mp4", "bytes=0-"))
        assertTrue(MediaGrouping.isSliceRequest("https://cdn/v.mp4?bytestart=0&byteend=999", null))
    }

    @Test fun `byte range query parameters are stripped, others kept`() {
        assertEquals("https://cdn/v.mp4?efg=abc", MediaGrouping.stripByteRangeParams("https://cdn/v.mp4?bytestart=0&efg=abc&byteend=999"))
        assertEquals("https://cdn/v.mp4", MediaGrouping.stripByteRangeParams("https://cdn/v.mp4?range=0-999"))
        assertEquals("https://cdn/v.mp4?range=all", MediaGrouping.stripByteRangeParams("https://cdn/v.mp4?range=all"))
    }
}
