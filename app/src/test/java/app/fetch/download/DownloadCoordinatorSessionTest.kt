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
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        // A resolver that ignores cancellation, like a blocking network read that completes anyway.
        DownloadCoordinator.streamResolver = {
            withContext(NonCancellable) { release.await() }
            finished.complete(Unit)
            ResolvedStream(listOf(quality))
        }
        val pageA = DownloadCoordinator.startNewSession()
        DownloadCoordinator.detect(stream, pageA)
        assertEquals(1, DownloadCoordinator.candidates.value.size)

        DownloadCoordinator.startNewSession() // user navigates to page B
        assertTrue(DownloadCoordinator.candidates.value.isEmpty())

        release.complete(Unit)
        withTimeout(2_000) { finished.await() }
        delay(100)
        assertTrue("page A's qualities leaked into page B", DownloadCoordinator.candidates.value.isEmpty())
    }

    @Test fun `resolver result for the current page is applied`() = runBlocking {
        DownloadCoordinator.streamResolver = { ResolvedStream(listOf(quality)) }
        val page = DownloadCoordinator.startNewSession()
        DownloadCoordinator.detect(stream, page)
        withTimeout(2_000) { while (DownloadCoordinator.candidates.value.firstOrNull()?.resolved != true) delay(10) }
        assertEquals(listOf("720p"), DownloadCoordinator.candidates.value.single().variants.map { it.label })
    }

    @Test fun `detections tagged with an old session are ignored`() {
        val old = DownloadCoordinator.startNewSession()
        DownloadCoordinator.startNewSession()
        DownloadCoordinator.detect(stream, old)
        assertTrue(DownloadCoordinator.candidates.value.isEmpty())
    }
}
