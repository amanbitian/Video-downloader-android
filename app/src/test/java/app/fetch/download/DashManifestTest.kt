package app.fetch.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashManifestTest {
    private val url = "https://cdn.example.com/video/manifest.mpd"

    private val templated = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10S">
          <Period>
            <AdaptationSet contentType="video" mimeType="video/mp4">
              <SegmentTemplate initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/seg-${'$'}Number%03d${'$'}.m4s" startNumber="1" timescale="1000" duration="4000"/>
              <Representation id="v1080" codecs="avc1.640028" bandwidth="4000000" width="1920" height="1080"/>
              <Representation id="v1080-vp9" codecs="vp09.00.40.08" bandwidth="2500000" width="1920" height="1080"/>
              <Representation id="v480" codecs="avc1.4d401e" bandwidth="1000000" width="854" height="480"/>
              <Representation id="v4k-av1" codecs="av01.0.12M.08" bandwidth="9000000" width="3840" height="2160"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
              <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>
              <SegmentTemplate initialization="a/init.mp4" media="a/${'$'}Time${'$'}.m4s" timescale="48000">
                <SegmentTimeline><S t="0" d="192000" r="1"/><S d="96000"/></SegmentTimeline>
              </SegmentTemplate>
              <Representation id="a128" codecs="mp4a.40.2" bandwidth="128000"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
              <Role schemeIdUri="urn:mpeg:dash:role:2011" value="commentary"/>
              <Representation id="a-commentary" codecs="mp4a.40.2" bandwidth="256000"><BaseURL>commentary.mp4</BaseURL></Representation>
            </AdaptationSet>
            <AdaptationSet contentType="text" mimeType="application/mp4">
              <Representation id="subs" codecs="stpp" bandwidth="1000"><BaseURL>subs.mp4</BaseURL></Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test fun `number templates expand with padding and duration-derived count`() {
        val rep = DashManifest.parse(url, templated).representation("v480")!!
        assertEquals("https://cdn.example.com/video/v480/init.mp4", rep.initUrl)
        assertEquals(3, rep.segmentUrls.size) // ceil(10s / 4s)
        assertEquals("https://cdn.example.com/video/v480/seg-001.m4s", rep.segmentUrls.first())
        assertEquals("https://cdn.example.com/video/v480/seg-003.m4s", rep.segmentUrls.last())
    }

    @Test fun `segment timelines expand repeats and carry time forward`() {
        val rep = DashManifest.parse(url, templated).representation("a128")!!
        assertEquals(
            listOf("a/0.m4s", "a/192000.m4s", "a/384000.m4s").map { "https://cdn.example.com/video/$it" },
            rep.segmentUrls
        )
    }

    @Test fun `base url representations are single files and text tracks are classified`() {
        val manifest = DashManifest.parse(url, templated)
        val commentary = manifest.representation("a-commentary")!!
        assertNull(commentary.initUrl)
        assertEquals(listOf("https://cdn.example.com/video/commentary.mp4"), commentary.segmentUrls)
        assertEquals(DashManifest.Kind.TEXT, manifest.representation("subs")!!.kind)
    }

    @Test fun `qualities pair video with the main audio, one per resolution, compatible codecs first`() {
        val resolved = DashResolver.build(url, DashManifest.parse(url, templated))
        assertEquals(listOf("1080p", "480p"), resolved.variants.map { it.label })
        val best = resolved.variants.first()
        assertEquals("v1080", best.videoKey)
        assertEquals("a128", best.audioKey)
        assertTrue(best.detail, best.detail.contains("H.264 + AAC"))
        // (4 Mbps + 128 kbps) × 10 s / 8
        assertEquals(5_160_000L, best.estimatedBytes)
        assertTrue(resolved.claimedPaths.contains("https://cdn.example.com/video/v1080/seg-002.m4s"))
        assertNull(resolved.note)
    }

    @Test fun `drm protected streams are reported up front`() {
        val xml = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT1M">
              <Period><AdaptationSet contentType="video">
                <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
                <Representation id="v" codecs="avc1.640028" bandwidth="1" height="720"><BaseURL>v.mp4</BaseURL></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val resolved = DashResolver.build(url, DashManifest.parse(url, xml))
        assertTrue(resolved.variants.isEmpty())
        assertTrue(resolved.note!!, resolved.note!!.contains("DRM"))
    }

    @Test fun `live manifests are refused`() {
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic"><Period><AdaptationSet contentType="video"><Representation id="v" bandwidth="1"/></AdaptationSet></Period></MPD>"""
        val manifest = DashManifest.parse(url, xml)
        assertTrue(manifest.isLive)
        assertTrue(DashResolver.build(url, manifest).note!!.contains("Live"))
    }

    @Test fun `iso durations`() {
        assertEquals(3723.5, DashManifest.parseDuration("PT1H2M3.5S")!!, 0.001)
        assertEquals(90.0, DashManifest.parseDuration("PT1M30S")!!, 0.001)
        assertNull(DashManifest.parseDuration("garbage"))
        assertFalse(DashManifest.parseDuration("PT0S")!! > 0)
    }
}
