package dev.lelonio.square.ui.requests

import android.app.Application
import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.data.SpotifyNetworkPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PREPARED ONLY. Controlled late readers; real account capability and manual network barrier. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class I07DiscoveryRequestsTest {
    private val account = AccountLocalDataFixture()
    private val track = CatalogTrack("spotify:track:a", "A", "Artist")
    private val release = SearchItem("spotify:album:a", "Album", "Artist", null)
    private fun proof(selection: () -> Boolean = { true }) = I07RequestProof.online(
        account.accounts, checkNotNull(account.accounts.captureOnline()), SpotifyNetworkPolicy.capture(), selection,
    )
    @After fun restorePolicy() { SpotifyNetworkPolicy.setManual(false); account.logout() }

    @Test fun partialFeedWithReleasesRetriesAndSuccessfulEmptyReplacesPreviousData() = runTest {
        account.connect()
        var state = I07FeedSnapshot()
        var fail = true
        var calls = 0
        var empty = false
        val pipe = I07FeedRequests(this, { state = it }, { testScheduler.currentTime }, freshnessMs = 100)
        val readers = I07FeedRequests.Readers(
            releases = { calls++; if (empty) emptyList() else listOf(release) },
            artists = { if (fail) error("temporary artists failure") else emptyList() },
            topTracks = { listOf(track) }, allTime = { emptyList() },
            fromArtists = { emptyList() }, recentContexts = { emptyList() },
        )
        pipe.load(proof(), readers); advanceUntilIdle()
        assertEquals(listOf(release), state.newReleases)
        assertEquals(I07FeedStatus.FAILED, state.sections[I07FeedSection.ARTISTS])
        assertEquals(I07FeedStatus.FAILED, state.sections[I07FeedSection.FROM_ARTISTS])
        assertEquals(I07FeedStatus.READY, state.sections[I07FeedSection.RECENT_CONTEXTS])
        fail = false
        pipe.load(proof(), readers); advanceUntilIdle()
        assertEquals(2, calls) // newReleases nonempty cannot suppress repair of failed siblings
        assertTrue(I07FeedSection.entries.all { state.sections[it] == I07FeedStatus.READY })
        pipe.load(proof(), readers); advanceUntilIdle()
        assertEquals(2, calls) // a complete empty section is cacheable
        advanceTimeBy(101)
        empty = true
        pipe.load(proof(), readers); advanceUntilIdle()
        assertEquals(3, calls)
        assertTrue(state.newReleases.isEmpty())
        assertFalse(state.loading)
    }

    @Test fun feedCancellationAndAccountSwitchRejectNonCancellableOldCompletion() = runTest {
        account.connect("A")
        var state = I07FeedSnapshot()
        val gate = CompletableDeferred<Unit>()
        var laterReaders = 0
        val pipe = I07FeedRequests(this, { state = it }, { testScheduler.currentTime })
        val old = I07FeedRequests.Readers(
            releases = { withContext(NonCancellable) { gate.await() }; listOf(release) },
            artists = { laterReaders++; emptyList() }, topTracks = { emptyList() }, allTime = { emptyList() },
            fromArtists = { emptyList() }, recentContexts = { emptyList() },
        )
        pipe.load(proof(), old); runCurrent()
        account.logout(); pipe.invalidate(); account.connect("B")
        val current = I07FeedRequests.Readers({ emptyList() }, { emptyList() }, { emptyList() },
            { emptyList() }, { emptyList() }, { emptyList() })
        pipe.load(proof(), current); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(state.newReleases.isEmpty())
        assertEquals(0, laterReaders)
        assertTrue(state.sections.values.all { it == I07FeedStatus.READY })
    }

    @Test fun shelvesRejectOldAccountWithoutRelyingOnCooperativeCancellationAndClearOnEmpty() = runTest {
        account.connect("A")
        val gate = CompletableDeferred<Unit>()
        var shelves = listOf<String>()
        val pipe = I07LatestRequest<List<String>>(this)
        pipe.start("home-A", proof(), read = { withContext(NonCancellable) { gate.await() }; listOf("private-A") },
            accept = { shelves = it })
        runCurrent()
        account.logout(); account.connect("B")
        // Deliberately do not cancel the old job: account proof alone must reject it.
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(shelves.isEmpty())
        pipe.start("home-B", proof(), read = { listOf("private-B") }, accept = { shelves = it })
        advanceUntilIdle(); assertEquals(listOf("private-B"), shelves)
        pipe.start("home-B-empty", proof(), read = { emptyList() }, accept = { shelves = it })
        advanceUntilIdle(); assertTrue(shelves.isEmpty())
    }

    @Test fun radioACompletingAfterBChoiceCannotPlayOrReplaceNewRequestFeedback() = runTest {
        account.connect()
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        var state = I07LatestRequest.State()
        val played = mutableListOf<String>()
        val pipe = I07LatestRequest<List<String>>(this, { state = it })
        pipe.start("A", proof(), read = { withContext(NonCancellable) { gateA.await() }; listOf("radio-A") },
            accept = { played += it })
        runCurrent(); assertEquals(I07LatestRequest.Phase.LOADING, state.phase)
        pipe.invalidate(); played += "chosen-B"
        pipe.start("B", proof(), read = { gateB.await(); emptyList() }, useful = { it.isNotEmpty() },
            accept = { played += it })
        runCurrent(); gateA.complete(Unit); runCurrent()
        assertEquals(listOf("chosen-B"), played)
        assertEquals("B", state.key)
        assertEquals(I07LatestRequest.Phase.LOADING, state.phase)
        gateB.complete(Unit); advanceUntilIdle()
        assertEquals(I07LatestRequest.Phase.FAILED, state.phase)
        assertEquals(listOf("chosen-B"), played)
    }

    @Test fun radioSelectionProofRejectsLateResultEvenWithoutCancelCallbackAndTimeoutIsVisible() = runTest {
        account.connect()
        val gate = CompletableDeferred<Unit>()
        var selected = true
        var plays = 0
        var state = I07LatestRequest.State()
        val pipe = I07LatestRequest<List<String>>(this, { state = it }, timeoutMs = 10)
        pipe.start("A", proof { selected }, read = { withContext(NonCancellable) { gate.await() }; listOf("A") },
            accept = { plays++ })
        runCurrent(); selected = false; gate.complete(Unit); advanceUntilIdle()
        assertEquals(0, plays)
        pipe.start("B", proof(), read = { CompletableDeferred<Unit>().await(); listOf("B") }, accept = { plays++ })
        advanceUntilIdle()
        assertEquals(I07LatestRequest.Phase.FAILED, state.phase)
        assertEquals(0, plays)
    }

    @Test fun manualRevokesPendingResponseAndNoQueuedReaderStartsOnItsOldLeaseAfterManualEnds() = runTest {
        account.connect()
        var calls = 0
        var accepted = 0
        val pipe = I07LatestRequest<List<String>>(this)
        val oldProof = proof()
        pipe.start("A", oldProof, read = { calls++; listOf("A") }, accept = { accepted++ })
        SpotifyNetworkPolicy.setManual(true)
        runCurrent()
        assertEquals(0, calls)
        SpotifyNetworkPolicy.setManual(false)
        pipe.start("old-lease", oldProof, read = { calls++; emptyList() }, accept = { accepted++ })
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(0, accepted)
    }

    @Test fun feedManualPauseRejectsLateReleaseAndDoesNotRunItsRemainingReaders() = runTest {
        account.connect()
        var state = I07FeedSnapshot()
        var laterCalls = 0
        val gate = CompletableDeferred<Unit>()
        val pipe = I07FeedRequests(this, { state = it }, { testScheduler.currentTime })
        val old = I07FeedRequests.Readers(
            { withContext(NonCancellable) { gate.await() }; listOf(release) },
            { laterCalls++; emptyList() }, { emptyList() }, { emptyList() }, { emptyList() }, { emptyList() },
        )
        pipe.load(proof(), old); runCurrent()
        SpotifyNetworkPolicy.setManual(true)
        pipe.unavailable(I07FeedAvailability.OFFLINE)
        assertFalse(state.loading)
        assertEquals(I07FeedAvailability.OFFLINE, state.availability)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(0, laterCalls)
        assertTrue(state.newReleases.isEmpty())
        SpotifyNetworkPolicy.setManual(false)
        val current = I07FeedRequests.Readers({ emptyList() }, { emptyList() }, { emptyList() },
            { emptyList() }, { emptyList() }, { emptyList() })
        pipe.load(proof(), current); advanceUntilIdle()
        assertTrue(state.sections.values.all { it == I07FeedStatus.READY })
    }

    @Test fun failedAccountVerificationCannotStartAnyPersonalFeedReader() = runTest {
        account.connect()
        var state = I07FeedSnapshot()
        var readers = 0
        val pipe = I07FeedRequests(this, { state = it }, { testScheduler.currentTime })
        pipe.load(proof(), I07FeedRequests.Readers(
            { readers++; listOf(release) }, { readers++; emptyList() }, { emptyList() },
            { emptyList() }, { emptyList() }, { emptyList() },
            verify = { error("profile belongs to a different Web API account") },
        ))
        advanceUntilIdle()
        assertEquals(0, readers)
        assertTrue(state.newReleases.isEmpty())
        assertTrue(state.sections.values.all { it == I07FeedStatus.FAILED })
        assertFalse(state.loading)
    }
}
