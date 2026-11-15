package dev.lelonio.square.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RootLikedAccountCacheTest {
    private val uri = "spotify:track:shared"
    private fun gateway() = Gateway(PathfinderKeys(ApplicationProvider.getApplicationContext<Context>()))

    @Test fun aNegativeFromACannotMaskBsPositiveOrANewLoginOfA() = runTest {
        SpotifyNetworkPolicy.setManual(false)
        val fixture = AccountLocalDataFixture()
        fixture.connect("a")
        var reads = 0
        val cache = LikedTracks(gateway(), fixture.accounts) { reads++; listOf(true) }
        cache.recordConfirmed(uri, false)
        assertEquals(false, cache.isLiked(uri))
        assertEquals(0, reads)
        fixture.nativeReady = false
        fixture.accounts.capture()
        assertEquals("the same durable account keeps its cache while offline", false, cache.isLiked(uri))
        assertEquals(0, reads)
        fixture.connect("b")
        assertEquals(true, cache.isLiked(uri))
        assertEquals(1, reads)
        cache.recordConfirmed(uri, false)
        fixture.connect("b") // Same canonical ID, new login lineage.
        assertEquals(true, cache.isLiked(uri))
        assertEquals(2, reads)
    }

    @Test fun anOldPendingReadCannotPublishOrRemoveTheNewAccountsQuestion() = runTest {
        SpotifyNetworkPolicy.setManual(false)
        val fixture = AccountLocalDataFixture()
        fixture.connect("a")
        val oldAnswer = CompletableDeferred<List<Boolean>>()
        var reads = 0
        val cache = LikedTracks(gateway(), fixture.accounts) {
            reads++
            if (reads == 1) oldAnswer.await() else listOf(true)
        }
        val old = async { cache.isLiked(uri) }
        runCurrent()
        fixture.connect("b")
        assertEquals(true, cache.isLiked(uri))
        oldAnswer.complete(listOf(false))
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals(true, cache.isLiked(uri))
        assertEquals(2, reads)
    }
}
