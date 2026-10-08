package app.fetch.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: results of a page the user already left must never reappear, even if they finish late. */
class DownloadCoordinatorSessionTest {
    private val originalResolver = DownloadCoordinator.streamResolver
    private val stream = MediaCandidate(url = "https://cdn.example.com/a/master.m3u8", title = "Video A", streamType = StreamType.HLS)
    private val quality = MediaVariant("https://cdn.example.com/a/720.m3u8", "720p", "", StreamType.HLS)

    @After fun restore() {
        DownloadCoordinator.streamResolver = originalResolver
        DownloadCoordinator.startNewSession()
    }

    @Test fun `late resolver result for a previous page is discarded`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        // A resolver that ignores cancellation, like a blocking network read that completes anyway.
        DownloadCoordinator.streamResolver = {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            finished.complete(Unit)
            ResolvedStream(listOf(quality))
        }
        val pageA = DownloadCoordinator.startNewSession()
        DownloadCoordinator.detect(stream, pageA)
        assertEquals(1, DownloadCoordinator.allCandidates.value.size)
        // Still resolving: nothing is offered yet, so the button can't open onto a half-empty sheet.
        assertTrue(DownloadCoordinator.candidates.value.isEmpty())

        // The lookup must be in flight when the user leaves; one cancelled before it starts can't leak anyway.
        withTimeout(2_000) { started.await() }
        DownloadCoordinator.startNewSession() // user navigates to page B
        assertTrue(DownloadCoordinator.allCandidates.value.isEmpty())

        release.complete(Unit)
        withTimeout(2_000) { finished.await() }
        delay(100)
        assertTrue("page A's qualities leaked into page B", DownloadCoordinator.allCandidates.value.isEmpty())
        assertTrue(DownloadCoordinator.candidates.value.isEmpty())
    }

    @Test fun `resolver result for the current page is applied`() = runBlocking {
        DownloadCoordinator.streamResolver = { ResolvedStream(listOf(quality)) }
        val page = DownloadCoordinator.startNewSession()
        DownloadCoordinator.detect(stream, page)
        withTimeout(2_000) { while (DownloadCoordinator.candidates.value.firstOrNull()?.resolved != true) delay(10) }
        assertEquals(listOf("720p"), DownloadCoordinator.candidates.value.single().variants.map { it.label })
    }

    /** One single-quality HLS master per quality, as players with a per-quality config serve them. */
    private fun perQuality(height: Int, kbps: Int, host: String = "cdn1") =
        "https://$host.example.com/hls/videos/2024/01/123/${height}P_${kbps}K_123.mp4/master.m3u8?validto=9&hash=abc"

    private val perQualityResolver: suspend (MediaCandidate) -> ResolvedStream = { candidate ->
        val height = Regex("""(\d+)P_""").find(candidate.url)!!.groupValues[1].toInt()
        ResolvedStream(listOf(MediaVariant(candidate.url.replace("master.m3u8", "index.m3u8"), "${height}p", "", StreamType.HLS, height = height)))
    }

    private fun snapshotWithInline(vararg urls: String) = app.fetch.detection.PageMediaSnapshot(
        "https://site.example.com/watch?v=1", "Video", null, null, null, emptyList(), emptyList(), emptyList(),
        inlineUrls = urls.toList(), viewportWidth = 400.0, viewportHeight = 800.0, pageWidth = 400.0,
    )

    @Test fun `qualities listed in the page script join the quality the player fetched`() = runBlocking {
        DownloadCoordinator.streamResolver = perQualityResolver
        val page = DownloadCoordinator.startNewSession()
        DownloadCoordinator.detect(MediaCandidate(url = perQuality(240, 400), title = "Video", streamType = StreamType.HLS), page)
        DownloadCoordinator.applySnapshot(page, snapshotWithInline(
            perQuality(1080, 4000, host = "cdn2"), perQuality(720, 4000), perQuality(240, 400),
            "https://cdn1.example.com/hls/videos/2024/01/999/720P_4000K_999.mp4/master.m3u8", // another video
        ))
        withTimeout(2_000) { while (DownloadCoordinator.candidates.value.singleOrNull()?.variants?.size != 3) delay(10) }
        assertEquals(listOf("1080p", "720p", "240p"), DownloadCoordinator.candidates.value.single().variants.map { it.label })
        assertEquals(1, DownloadCoordinator.allCandidates.value.size)
    }

    @Test fun `two qualities fetched as separate streams become one video`() = runBlocking {
        DownloadCoordinator.streamResolver = perQualityResolver
        val page = DownloadCoordinator.startNewSession()
        DownloadCoordinator.detect(MediaCandidate(url = perQuality(240, 400), title = "Video", streamType = StreamType.HLS), page)
        DownloadCoordinator.detect(MediaCandidate(url = perQuality(720, 2000), title = "Video", streamType = StreamType.HLS), page)
        withTimeout(2_000) {
            while (DownloadCoordinator.allCandidates.value.size != 1 || DownloadCoordinator.candidates.value.singleOrNull()?.variants?.size != 2) delay(10)
        }
        assertEquals(listOf("720p", "240p"), DownloadCoordinator.candidates.value.single().variants.map { it.label })
    }

    @Test fun `detections tagged with an old session are ignored`() {
        val old = DownloadCoordinator.startNewSession()
        DownloadCoordinator.startNewSession()
        DownloadCoordinator.detect(stream, old)
        assertTrue(DownloadCoordinator.candidates.value.isEmpty())
    }
}
