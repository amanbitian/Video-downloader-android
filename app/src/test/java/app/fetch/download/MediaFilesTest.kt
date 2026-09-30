package app.fetch.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFilesTest {
    @Test fun `manifest extensions are replaced by the saved container`() {
        assertEquals("index.ts", MediaFiles.displayName("index.m3u8", "video/mp2t"))
        assertEquals("master.mp4", MediaFiles.displayName("master.mpd", "video/mp4"))
    }

    @Test fun `titles containing dots still get an extension`() {
        assertEquals("Mr. Bean.mp4", MediaFiles.displayName("Mr. Bean", "video/mp4"))
        assertEquals("v1.2 release notes.pdf", MediaFiles.displayName("v1.2 release notes", "application/pdf"))
    }

    @Test fun `matching extension is normalised, not doubled`() {
        assertEquals("song.mp3", MediaFiles.displayName("song.mp3", "audio/mpeg"))
        assertEquals("clip.mp4", MediaFiles.displayName("clip.MP4", "video/mp4"))
    }

    @Test fun `unknown mime keeps a plausible existing extension`() {
        assertEquals("archive.xyz", MediaFiles.displayName("archive.xyz", "application/octet-stream"))
        assertEquals("download", MediaFiles.displayName("", "application/octet-stream"))
    }

    @Test fun `unfamiliar media types are not forced to mp4`() {
        assertEquals("HTML Video.ogv", MediaFiles.displayName("HTML Video", "video/ogg"))
        assertEquals("clip", MediaFiles.displayName("clip", "video/x-unknown"))
    }

    @Test fun `audio types are not all labelled mp3`() {
        assertEquals("audio/mp4", MediaFiles.resolveMime(StreamType.DIRECT, "https://cdn.example.com/a.m4a?sig=1"))
        assertEquals("m4a", MediaFiles.extensionFor("audio/mp4"))
        assertEquals("track.m4a", MediaFiles.displayName("track", "audio/mp4"))
    }

    @Test fun `specific declared type beats url, generic and list values are ignored`() {
        assertEquals("video/webm", MediaFiles.resolveMime(StreamType.DIRECT, "https://x/v.mp4", "video/webm; codecs=vp9"))
        assertEquals("application/pdf", MediaFiles.resolveMime(StreamType.DIRECT, "https://x/doc.pdf", "application/octet-stream"))
        assertEquals("video/mp4", MediaFiles.resolveMime(StreamType.DIRECT, "https://x/v.mp4", "video/webm,video/ogg,video/*;q=0.9"))
        assertEquals("video/quicktime", MediaFiles.resolveMime(StreamType.DIRECT, "https://x/v", null, "video/quicktime"))
        assertEquals("video/mp2t", MediaFiles.resolveMime(StreamType.HLS, "https://x/v.m3u8", "application/vnd.apple.mpegurl"))
    }

    @Test fun `kinds route files to the right collection`() {
        assertEquals(MediaKind.VIDEO, MediaFiles.kindOf("video/mp2t"))
        assertEquals(MediaKind.IMAGE, MediaFiles.kindOf("image/webp"))
        assertEquals(MediaKind.OTHER, MediaFiles.kindOf("application/pdf"))
    }

    @Test fun `content range total`() {
        assertEquals(12345L, MediaFiles.contentRangeTotal("bytes 0-0/12345"))
        assertEquals(500L, MediaFiles.contentRangeTotal("bytes */500"))
        assertNull(MediaFiles.contentRangeTotal("bytes 0-99/*"))
        assertNull(MediaFiles.contentRangeTotal(null))
    }

    @Test fun `safe titles strip path and control characters`() {
        assertEquals("a_b_c", MediaFiles.safeTitle("a/b:c"))
        assertEquals("download", MediaFiles.safeTitle("   "))
    }

    @Test fun `segment progress is used when size is unknown`() {
        val item = DownloadItem(title = "x", sourceUrl = "https://x/a.m3u8", segmentIndex = 25, segmentCount = 100)
        assertEquals(0.25f, item.progress(), 0.0001f)
    }
}
