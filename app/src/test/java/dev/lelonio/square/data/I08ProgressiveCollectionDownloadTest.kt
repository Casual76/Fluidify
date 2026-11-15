package dev.lelonio.square.data

import dev.lelonio.square.ui.library.CollectionDownloadReads
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real source/keep producer and persisted index. Synthetic transport; prepared, NOT EXECUTED. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class I08ProgressiveCollectionDownloadTest {
    private val uri = "spotify:playlist:long"
    private val t = CatalogTrack("spotify:track:t", "T", "Artist", addedAt = "2020-01-01T00:00:00Z")
    private val u = CatalogTrack("spotify:track:u", "U", "Artist", addedAt = "2026-01-01T00:00:00Z")
    private val label = DownloadStore.OwnerLabel("Long playlist")
    private val record = DownloadStore.FileRecord("aabb", "OGG_320", 42, 320, 123)
    @Before fun online() { SpotifyNetworkPolicy.setManual(false) }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }

    private inner class Harness(val authority: AccountLocalDataFixture, scope: TestScope) {
        val context = accountDataTestContext()
        val store = DownloadStore(context, StandardTestDispatcher(scope.testScheduler),
            { file, text -> writeDownloadIndexAtomically(file, text) }, authority.accounts)
        val reads = CollectionDownloadReads()
        val access get() = authority.accounts.capture()!!
        var queued = 0
        suspend fun keep(source: CollectionDownloadReads.Read) = reads.keep(store, source.access, uri,
            listOf(t), label, sourceComplete = false, source = source, queued = { queued++ })
    }
    private suspend fun TestScope.harness() = Harness(AccountLocalDataFixture().also { it.connect("a") }, this)

    @Test fun ownerCreatedAfterReadStartedCannotBecomeCompleteOnFirstSubset() = runTest {
        val h = harness()
        val source = h.reads.begin(uri, h.access)
        assertNull(h.store.beginOwnerRefresh(h.access, uri)) // original read had no owner/token
        val keep = async { h.keep(source) }
        runCurrent()
        assertEquals(listOf(t.uri), h.store.owners.value[uri])
        h.store.onCompleted(h.access, t.uri, record)
        assertEquals(OwnerState.Partial(1, 1), h.store.ownerState(uri))
        assertEquals(setOf(uri), h.store.incompleteOwners.value)
        // Full source preserves repeated occurrences, their dates and the remaining track.
        val full = listOf(t, t.copy(addedAt = u.addedAt), u)
        source.complete(full)
        assertEquals(DownloadStore.OwnerRefreshResult.MEMBERSHIP, keep.await())
        assertEquals(full, h.store.tracksForOwner(uri))
        assertTrue(h.store.incompleteOwners.value.isEmpty())
        assertEquals(OwnerState.Running(2f / 3f, 2, 3), h.store.ownerState(uri))
        assertEquals(listOf(u.uri), h.store.pending())
        h.store.onCompleted(h.access, u.uri, record.copy(trackId = "ccdd"))
        assertEquals(OwnerState.Complete, h.store.ownerState(uri))
        assertEquals(1, h.queued) // caller wakes queue again only for MEMBERSHIP
    }

    @Test fun cachedCompletePageStillJoinsAnAlreadyRegisteredBackgroundRead() = runTest {
        val h = harness()
        val source = h.reads.begin(uri, h.access)
        val keep = async { h.reads.keep(h.store, h.access, uri, listOf(t), label,
            sourceComplete = true, source = source, queued = { h.queued++ }) }
        runCurrent()
        h.store.onCompleted(h.access, t.uri, record)
        assertEquals(OwnerState.Partial(1, 1), h.store.ownerState(uri))
        source.complete(listOf(t, u))
        assertEquals(DownloadStore.OwnerRefreshResult.MEMBERSHIP, keep.await())
        assertEquals(listOf(t, u), h.store.tracksForOwner(uri))
        assertEquals(listOf(u.uri), h.store.pending())
    }

    @Test fun sourceFailureSurvivesReopenAndNormalSyncRepairsWithoutResettingSharedFailuresOrFiles() = runTest {
        val h = harness()
        h.store.setOwner(h.access, "other", listOf(u), DownloadStore.OwnerLabel("Other"))
        repeat(DownloadStore.MAX_ATTEMPTS) { h.store.onFailed(h.access, u.uri, "not available") }
        val failures = h.store.failures.value
        val source = h.reads.begin(uri, h.access)
        val keep = async { runCatching { h.keep(source) } }
        runCurrent()
        h.store.onCompleted(h.access, t.uri, record)
        source.fail(IOException("page interrupted"))
        assertTrue(keep.await().exceptionOrNull() is IOException)
        val reopened = DownloadStore(h.context, StandardTestDispatcher(testScheduler),
            { file, text -> writeDownloadIndexAtomically(file, text) }, h.authority.accounts)
        reopened.load()
        assertEquals(setOf(uri), reopened.incompleteOwners.value)
        assertEquals(OwnerState.Partial(1, 1), reopened.ownerState(uri))
        val refresh = reopened.beginOwnerRefresh(h.access, uri)!!
        assertEquals(DownloadStore.OwnerRefreshResult.MEMBERSHIP,
            reopened.refreshOwner(refresh, listOf(t, u), label))
        assertEquals(failures, reopened.failures.value)
        assertEquals(record, reopened.files.value[t.uri])
        assertEquals(listOf(u), reopened.tracksForOwner("other"))
        assertTrue(reopened.incompleteOwners.value.isEmpty())
        assertEquals(OwnerState.Partial(1, 2), reopened.ownerState(uri))
    }

    @Test fun successfulSameSubsetClearsIncompleteMarkerWithoutRequeueOrAudioReset() = runTest {
        val h = harness()
        val source = h.reads.begin(uri, h.access)
        val keep = async { h.keep(source) }; runCurrent()
        h.store.onCompleted(h.access, t.uri, record)
        source.complete(listOf(t))
        assertEquals(DownloadStore.OwnerRefreshResult.METADATA, keep.await())
        assertEquals(record, h.store.files.value[t.uri])
        assertEquals(OwnerState.Complete, h.store.ownerState(uri))
        assertTrue(h.store.pending().isEmpty())
    }

    @Test fun removalAndExplicitReplacementWinOverTheLateFullRead() = runTest {
        val h = harness()
        val first = h.reads.begin(uri, h.access)
        val keep = async { h.keep(first) }; runCurrent()
        h.store.removeOwner(h.access, uri)
        first.complete(listOf(t, u))
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, keep.await())
        assertFalse(h.store.owners.value.containsKey(uri))
        assertTrue(h.store.incompleteOwners.value.isEmpty())
        val second = h.reads.begin(uri, h.access)
        val replacement = async { h.keep(second) }; runCurrent()
        h.store.setOwner(h.access, uri, listOf(u), label)
        second.complete(listOf(t, u))
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, replacement.await())
        assertEquals(listOf(u), h.store.tracksForOwner(uri))
    }

    @Test fun accountSwitchCannotPublishIntoNewAccountAndRetainsOriginalIncompleteOwner() = runTest {
        val h = harness()
        val source = h.reads.begin(uri, h.access)
        val keep = async { h.keep(source) }; runCurrent()
        h.authority.connect("b")
        source.complete(listOf(t, u))
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, keep.await())
        assertTrue(h.store.owners.value.isEmpty())
        assertTrue(h.store.incompleteOwners.value.isEmpty())
        h.authority.connect("a")
        assertEquals(listOf(t.uri), h.store.owners.value[uri])
        assertEquals(setOf(uri), h.store.incompleteOwners.value)
    }

    @Test fun manualAndCancellationLeaveKnownCopyIncompleteWithoutReplayingAnySource() = runTest {
        val h = harness()
        val source = h.reads.begin(uri, h.access)
        val keep = async { runCatching { h.keep(source) } }; runCurrent()
        h.store.onCompleted(h.access, t.uri, record)
        SpotifyNetworkPolicy.setManual(true)
        val denied = runCatching { source.complete(listOf(t, u)) }.exceptionOrNull()!!
        source.fail(denied) // same catch/finally contract used by loadContextInto
        assertTrue(keep.await().exceptionOrNull() is SpotifyNetworkPolicy.Offline)
        assertEquals(OwnerState.Partial(1, 1), h.store.ownerState(uri))
        assertEquals(1, h.queued)
        SpotifyNetworkPolicy.setManual(false)
        h.store.removeOwner(h.access, uri)
        val fresh = h.reads.begin(uri, h.access)
        val cancelled = async { h.keep(fresh) }; runCurrent()
        cancelled.cancelAndJoin()
        fresh.complete(listOf(t, u)); runCurrent()
        assertEquals(listOf(t.uri), h.store.owners.value[uri])
        assertEquals(setOf(uri), h.store.incompleteOwners.value)
        assertEquals(2, h.queued)
        // A later normal refresh can finish the retained request; no cancelled job revived it.
        val refresh = h.store.beginOwnerRefresh(h.access, uri)!!
        assertEquals(DownloadStore.OwnerRefreshResult.MEMBERSHIP,
            h.store.refreshOwner(refresh, listOf(t, u), label))
        assertTrue(h.store.incompleteOwners.value.isEmpty())
    }
}
