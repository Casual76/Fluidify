package dev.lelonio.square.ui.playlist

import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.requests.RequestOwnership
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Prepared, NOT EXECUTED. Real receipt consumer and UI states, synthetic authority/service only. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistEditsTest {
    private val a = CatalogPlaylist("spotify:playlist:a", "Original A")
    private val b = CatalogPlaylist("spotify:playlist:b", "Original B")
    private val c = CatalogPlaylist("spotify:playlist:c", "Created C")
    private val first = CatalogTrack("spotify:track:t", "Track", "Artist", addedAt = "2024-01-01T00:00:00Z")
    private val second = first.copy(addedAt = "2025-01-01T00:00:00Z")

    private data class Call(val operation: String, val uri: String, val name: String = "", val index: Int = -1) {
        val result = CompletableDeferred<CatalogPlaylist?>()
        fun success(playlist: CatalogPlaylist? = null) { result.complete(playlist) }
        fun fail() { result.completeExceptionally(IOException("Synthetic unconfirmed write")) }
    }
    private class Service : PlaylistEditService {
        val calls = mutableListOf<Call>()
        // Deliberately non-cooperative, so cancelling/losing authority must still fence the receipt.
        private suspend fun call(call: Call): CatalogPlaylist? {
            calls += call
            return withContext(NonCancellable) { call.result.await() }
        }
        override suspend fun create(name: String) = checkNotNull(call(Call("create", "", name)))
        override suspend fun rename(uri: String, name: String) { call(Call("rename", uri, name)) }
        override suspend fun delete(uri: String) { call(Call("delete", uri)) }
        override suspend fun remove(uri: String, trackUri: String, index: Int) { call(Call("remove", uri, trackUri, index)) }
    }

    private inner class Harness(val account: AccountLocalDataFixture, scope: CoroutineScope) {
        val library = MutableStateFlow<MainViewModel.UiState>(MainViewModel.UiState.Ready("Account", listOf(a, b)))
        val detail = MutableStateFlow(MainViewModel.PlaylistState(
            uri = a.uri, name = a.name, tracks = listOf(first, second), saved = true, mine = true,
        ))
        val service = Service()
        val invalidated = mutableListOf<String>()
        val readers = RequestOwnership()
        var reloaded = 0
        val edits = PlaylistEdits(scope, account.accounts, library, detail, { service },
            invalidate = { uri, _ -> invalidated += uri },
            stopDetailRead = { readers.invalidate() }, reloadDetail = { reloaded++ },
        )
        fun rows() = (library.value as MainViewModel.UiState.Ready).playlists
    }
    private suspend fun TestScope.harness(): Harness =
        Harness(AccountLocalDataFixture().also { it.connect() }, backgroundScope).also { runCurrent() }

    @Test fun failedRenameWithOpenDetailKeepsBothTitlesAndReportsOnce() = runTest {
        val h = harness()
        h.edits.rename(a.uri, "New title")
        runCurrent()
        assertEquals(a.name, h.detail.value.name)
        assertEquals(listOf(a, b), h.rows())
        h.service.calls.single().fail()
        runCurrent()
        assertEquals(a.name, h.detail.value.name)
        assertEquals(listOf(a, b), h.rows())
        assertEquals(PlaylistEditFeedback.Operation.RENAME, h.edits.feedback.value.single().operation)
        assertEquals(PlaylistEditFeedback.Reason.UNCONFIRMED, h.edits.feedback.value.single().reason)
        h.edits.dismiss(h.edits.feedback.value.single().id)
        runCurrent()
        assertTrue(h.edits.feedback.value.isEmpty())
        assertEquals(1, h.service.calls.size)
        assertEquals(listOf(a.uri, a.uri), h.invalidated)
    }

    @Test fun reversedFailuresAndSuccessesOnDifferentPlaylistsPreserveConfirmedEdits() = runTest {
        val h = harness()
        h.edits.rename(a.uri, "Rejected A")
        h.edits.rename(b.uri, "Confirmed B")
        runCurrent()
        h.service.calls[1].success()
        runCurrent()
        h.service.calls[0].fail()
        runCurrent()
        assertEquals(listOf(a, b.copy(name = "Confirmed B")), h.rows())
        assertEquals(a.name, h.detail.value.name)
        assertEquals(a.name, h.edits.feedback.value.single().name)
    }

    @Test fun samePlaylistWritesAreFifoAndSupersededFailureDoesNotCoverNewRequest() = runTest {
        val h = harness()
        h.edits.rename(a.uri, "First")
        runCurrent()
        h.edits.rename(a.uri, "Second")
        runCurrent()
        assertEquals(1, h.service.calls.size)
        h.service.calls[0].fail()
        runCurrent()
        assertEquals(2, h.service.calls.size)
        assertTrue(h.edits.feedback.value.isEmpty())
        h.service.calls[1].success()
        runCurrent()
        assertEquals("Second", h.rows().first().name)
        assertEquals("Second", h.detail.value.name)
    }

    @Test fun secondRenameFailureRetainsFirstConfirmedName() = runTest {
        val h = harness()
        h.edits.rename(a.uri, "Confirmed")
        runCurrent()
        h.edits.rename(a.uri, "Rejected")
        h.service.calls.single().success()
        runCurrent()
        h.service.calls[1].fail()
        runCurrent()
        assertEquals("Confirmed", h.detail.value.name)
        assertEquals("Confirmed", h.rows().first().name)
        assertEquals(1, h.edits.feedback.value.size)
    }

    @Test fun failedDeleteCannotUndoConcurrentCreateOrRename() = runTest {
        val h = harness()
        h.edits.delete(a.uri)
        h.edits.create(c.name)
        h.edits.rename(b.uri, "Changed B")
        runCurrent()
        h.service.calls[1].success(c)
        h.service.calls[2].success()
        runCurrent()
        h.service.calls[0].fail()
        runCurrent()
        assertEquals(listOf(c, a, b.copy(name = "Changed B")), h.rows())
        assertEquals(true, h.detail.value.saved)
        assertEquals(PlaylistEditFeedback.Operation.DELETE, h.edits.feedback.value.single().operation)
    }

    @Test fun failedCreateAndRemovalKeepRowsAndGiveSeparateFeedback() = runTest {
        val h = harness()
        val tracks = h.detail.value.tracks
        h.edits.create("  New  ")
        h.edits.remove(second, 1)
        runCurrent()
        assertEquals("New", h.service.calls[0].name)
        assertSame(tracks, h.detail.value.tracks)
        assertEquals(1, h.service.calls[1].index)
        h.service.calls.forEach { it.fail() }
        runCurrent()
        assertEquals(listOf(a, b), h.rows())
        assertSame(tracks, h.detail.value.tracks)
        assertEquals(setOf(PlaylistEditFeedback.Operation.CREATE, PlaylistEditFeedback.Operation.REMOVE),
            h.edits.feedback.value.map { it.operation }.toSet())
        assertEquals(2, h.service.calls.size)
    }

    @Test fun successfulRemovalDeletesOnlySelectedDuplicateAndFencesEarlierReader() = runTest {
        val h = harness()
        val reader = h.readers.begin()
        h.edits.remove(second, 1)
        runCurrent()
        assertFalse(reader.isCurrent())
        h.service.calls.single().success()
        runCurrent()
        assertEquals(listOf(first), h.detail.value.tracks)
        assertTrue(h.edits.feedback.value.isEmpty())
    }

    @Test fun duplicateWithoutPositionAndWrongPositionNeverFallBackToFirstUri() = runTest {
        val h = harness()
        h.edits.remove(second, -1)
        runCurrent()
        assertTrue(h.service.calls.isEmpty())
        assertEquals(PlaylistEditFeedback.Reason.SELECTION_CHANGED, h.edits.feedback.value.single().reason)
        h.edits.remove(second, 8)
        runCurrent()
        assertTrue(h.service.calls.isEmpty())
        assertEquals(listOf(first, second), h.detail.value.tracks)
    }

    @Test fun queuedRemovalWithShiftedPositionsRequiresNewSelectionWithoutDispatch() = runTest {
        val h = harness()
        h.edits.remove(first, 0)
        h.edits.remove(second, 1)
        runCurrent()
        assertEquals(1, h.service.calls.size)
        h.service.calls.single().success()
        runCurrent()
        assertEquals(listOf(second), h.detail.value.tracks)
        assertEquals(1, h.service.calls.size)
        assertEquals(PlaylistEditFeedback.Reason.SELECTION_CHANGED, h.edits.feedback.value.single().reason)
    }

    @Test fun lateRemovalFailureAfterNavigationDoesNotTouchNewPageOrShowAlert() = runTest {
        val h = harness()
        h.edits.remove(second, 1)
        runCurrent()
        h.edits.detailOpened()
        val newPage = MainViewModel.PlaylistState(uri = b.uri, name = b.name, tracks = listOf(first))
        h.detail.value = newPage
        h.service.calls.single().fail()
        runCurrent()
        assertEquals(newPage, h.detail.value)
        assertTrue(h.edits.feedback.value.isEmpty())
    }

    @Test fun lateRenameFailureAfterLeavingDetailDoesNotShowAlert() = runTest {
        val h = harness()
        h.edits.rename(a.uri, "New")
        runCurrent()
        h.edits.destinationChanged()
        h.service.calls.single().fail()
        runCurrent()
        assertEquals(listOf(a, b), h.rows())
        assertEquals(a.name, h.detail.value.name)
        assertTrue(h.edits.feedback.value.isEmpty())
    }

    @Test fun sameUriReopenedCannotInheritOldRemovalMenu() = runTest {
        val h = harness()
        val oldAction = h.edits.removalAction(h.detail.value, second, 1)
        h.edits.detailOpened() // same URI and the very same cached track objects
        oldAction()
        runCurrent()
        assertTrue(h.service.calls.isEmpty())
        assertEquals(listOf(first, second), h.detail.value.tracks)
    }

    @Test fun oldAccountSuccessFailureAndQueuedWritesCannotPublishIntoNewAccount() = runTest {
        val h = harness()
        h.edits.create(c.name)
        h.edits.rename(a.uri, "Old rename")
        runCurrent()
        h.edits.delete(a.uri) // waits for the old rename's mutex
        runCurrent()
        h.account.connect("account-b")
        val newRows = listOf(b.copy(name = "Account B"))
        val newPage = MainViewModel.PlaylistState(uri = a.uri, name = "Account B page")
        h.library.value = MainViewModel.UiState.Ready("B", newRows)
        h.detail.value = newPage
        runCurrent()
        h.service.calls[0].success(c)
        h.service.calls[1].fail()
        runCurrent()
        assertEquals(newRows, h.rows())
        assertEquals(newPage, h.detail.value)
        assertEquals(2, h.service.calls.size)
        assertTrue(h.edits.feedback.value.isEmpty())
    }

    @Test fun returningToSameAccountStillRejectsOldAuthorityReceipt() = runTest {
        val h = harness()
        h.edits.rename(a.uri, "Stale")
        runCurrent()
        h.account.connect("account-b")
        h.account.connect("account-a")
        runCurrent()
        h.service.calls.single().success()
        runCurrent()
        assertEquals(a.name, h.detail.value.name)
        assertEquals(listOf(a, b), h.rows())
        assertTrue(h.edits.feedback.value.isEmpty())
    }

    @Test fun localOnlyAuthorityCannotDispatchAndAlreadyQueuedAlertClearsOnRevocation() = runTest {
        val h = harness()
        h.edits.create(c.name)
        runCurrent()
        h.service.calls.single().fail()
        runCurrent()
        assertEquals(1, h.edits.feedback.value.size)
        h.account.history = h.account.history!!.copy(generation = 99)
        h.account.accounts.historyChanged()
        runCurrent()
        assertTrue(h.edits.feedback.value.isEmpty())
        h.edits.rename(a.uri, "Denied")
        runCurrent()
        assertEquals(1, h.service.calls.size)
        assertEquals(PlaylistEditFeedback.Reason.UNAVAILABLE, h.edits.feedback.value.single().reason)
    }

    @Test fun cancellationOfEveryWriteIsNotAServiceFailureOrAutomaticRetry() = runTest {
        for (operation in PlaylistEditFeedback.Operation.entries) {
            val h = harness()
            val job = when (operation) {
                PlaylistEditFeedback.Operation.CREATE -> h.edits.create(c.name)
                PlaylistEditFeedback.Operation.RENAME -> h.edits.rename(a.uri, "Cancelled")
                PlaylistEditFeedback.Operation.DELETE -> h.edits.delete(a.uri)
                PlaylistEditFeedback.Operation.REMOVE -> h.edits.remove(second, 1)
            }
            runCurrent()
            job.cancel()
            h.service.calls.single().success(c)
            runCurrent()
            assertTrue(job.isCancelled)
            assertTrue(h.edits.feedback.value.isEmpty())
            assertEquals(1, h.service.calls.size)
            assertEquals(listOf(a, b), h.rows())
            assertEquals(listOf(first, second), h.detail.value.tracks)
            assertEquals(a.name, h.detail.value.name)
        }
    }

    @Test fun serviceCancellationExceptionIsPropagatedWithoutFeedback() = runTest {
        val h = harness()
        val job = h.edits.delete(a.uri)
        runCurrent()
        h.service.calls.single().result.completeExceptionally(CancellationException("Synthetic cancellation"))
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(h.edits.feedback.value.isEmpty())
        assertEquals(listOf(a, b), h.rows())
    }

    @Test fun cancelledNonCooperativeWriterReturningIoFailureStillHasNoServiceAlert() = runTest {
        val h = harness()
        val job = h.edits.rename(a.uri, "Cancelled")
        runCurrent()
        job.cancel()
        h.service.calls.single().fail()
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(h.edits.feedback.value.isEmpty())
        assertEquals(a.name, h.detail.value.name)
        assertEquals(listOf(a, b), h.rows())
    }

    @Test fun earlierLibraryAndDetailReadsCannotEraseNewerConfirmedReceipts() = runTest {
        val h = harness()
        val readVersion = h.edits.readVersion()
        val oldPage = h.detail.value
        h.edits.rename(a.uri, "Confirmed A")
        h.edits.delete(b.uri)
        h.edits.create(c.name)
        runCurrent()
        h.service.calls[2].success(c)
        h.service.calls[1].success()
        h.service.calls[0].success()
        runCurrent()
        assertEquals(listOf(c, a.copy(name = "Confirmed A")), h.edits.mergeLibraryRead(listOf(a, b), readVersion))
        assertEquals("Confirmed A", h.edits.mergeDetailRead(oldPage, readVersion).name)
        assertEquals(listOf(c, a.copy(name = "Confirmed A")), h.rows())
        // A new read can observe later changes made outside the app.
        assertEquals(listOf(a), h.edits.mergeLibraryRead(listOf(a), h.edits.readVersion()))
    }

    @Test fun confirmedDeleteSynchronizesOpenDetailAndOldReadsWithoutClearingTracks() = runTest {
        val h = harness()
        val version = h.edits.readVersion()
        val old = h.detail.value
        h.edits.delete(a.uri)
        runCurrent()
        h.service.calls.single().success()
        runCurrent()
        assertEquals(listOf(b), h.rows())
        assertEquals(false, h.detail.value.saved)
        assertEquals(false, h.edits.mergeDetailRead(old, version).saved)
        assertEquals(old.tracks, h.detail.value.tracks)
    }

    @Test fun createReceiptWhileLibraryIsLoadingIsMergedIntoPendingReadOnlyOnce() = runTest {
        val h = harness()
        val version = h.edits.readVersion()
        h.library.value = MainViewModel.UiState.Loading
        h.edits.create(c.name)
        runCurrent()
        h.service.calls.single().success(c)
        runCurrent()
        assertEquals(listOf(c, a), h.edits.mergeLibraryRead(listOf(a), version))
        assertEquals(listOf(c, a), h.edits.mergeLibraryRead(listOf(c, a), version))
    }

    @Test fun laterLibraryReadKeepsBothTitlesAlignedAndBeatsOldDetailMetadata() = runTest {
        val h = harness()
        val oldVersion = h.edits.readVersion()
        val oldDetail = h.detail.value
        h.edits.rename(a.uri, "Confirmed A")
        runCurrent()
        h.service.calls.single().success()
        runCurrent()
        val readVersion = h.edits.readVersion()
        h.edits.publishLibraryRead(MainViewModel.UiState.Ready("Account", listOf(a.copy(name = "Later remote name"), b)), readVersion)
        assertEquals("Later remote name", h.rows().first().name)
        assertEquals("Later remote name", h.detail.value.name)
        assertEquals("Later remote name", h.edits.mergeDetailRead(oldDetail, oldVersion).name)
    }

    @Test fun explicitReadAfterAmbiguousDeleteUpdatesDetailMembershipWithoutRepeatingWrite() = runTest {
        val h = harness()
        val oldDetail = h.detail.value
        h.edits.delete(a.uri)
        runCurrent()
        h.service.calls.single().fail()
        runCurrent()
        assertEquals(true, h.detail.value.saved)
        val version = h.edits.readVersion()
        h.edits.publishLibraryRead(MainViewModel.UiState.Ready("Account", listOf(b)), version)
        assertEquals(false, h.detail.value.saved)
        assertEquals(false, h.edits.mergeDetailRead(oldDetail, version).saved)
        assertEquals(listOf(b), h.rows())
        assertEquals(1, h.service.calls.size)
    }
}
