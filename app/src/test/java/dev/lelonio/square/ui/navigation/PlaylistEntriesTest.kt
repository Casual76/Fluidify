package dev.lelonio.square.ui.navigation

import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.MainViewModel.PlaylistState
import dev.lelonio.square.ui.playlist.PlaylistEditFeedback
import dev.lelonio.square.ui.playlist.PlaylistEditService
import dev.lelonio.square.ui.playlist.PlaylistEdits
import dev.lelonio.square.ui.requests.RequestOwnership
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** PREPARED ONLY. Production selection/read/receipt consumers; synthetic authority and replies. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistEntriesTest {
    private val artist = CatalogPlaylist("spotify:artist:artist", "Artist")
    private val a = CatalogPlaylist("spotify:album:a", "Album A")
    private val b = CatalogPlaylist("spotify:album:b", "Album B")
    private val playlist = CatalogPlaylist("spotify:playlist:p", "Playlist")
    private val track = CatalogTrack("spotify:track:t", "Synthetic track", "Artist")

    private inner class Consumer(val account: AccountLocalDataFixture, val scope: CoroutineScope) {
        val entries = PlaylistEntries()
        val ownership = RequestOwnership()
        val library = MutableStateFlow<MainViewModel.UiState>(MainViewModel.UiState.Ready("Fixture", listOf(playlist)))
        var job: Job? = null
        var loads = 0
        var removals = 0
        lateinit var ticket: PlaylistReadTicket
        lateinit var reading: PlaylistEntries.Entry
        val edits by lazy {
            PlaylistEdits(scope, account.accounts, library, entries.activeState,
                service = { object : PlaylistEditService {
                    override suspend fun create(name: String) = CatalogPlaylist("spotify:playlist:new", name)
                    override suspend fun rename(uri: String, name: String) = Unit
                    override suspend fun delete(uri: String) = Unit
                    override suspend fun remove(uri: String, trackUri: String, index: Int) { removals++ }
                } }, invalidate = { uri, _ -> entries.invalidate(uri) },
                stopDetailRead = { ownership.invalidate() }, reloadDetail = {},
                currentDetail = { entries.activeState },
            )
        }
        val selection = PlaylistEntrySelection(entries,
            authority = { account.accounts.capture()?.binding?.lineage },
            stopRead = { ownership.invalidate(); job?.cancel() },
            changed = { edits.detailOpened() }, reconcile = { edits.mergeDetailRead(it, 0L) },
            open = { descriptor ->
                loads++
                reading = checkNotNull(entries.pending())
                ticket = PlaylistReadTicket(ownership.begin(), account.accounts,
                    account.accounts.captureOnline(), edits.readVersion(), entries.activeState,
                    onReady = { entries.completed(reading) })
                entries.activeState.value = PlaylistState(uri = descriptor.uri, name = descriptor.name, loading = true)
            },
        )
        fun open(id: String, descriptor: CatalogPlaylist) {
            selection.prepare(descriptor)
            assertTrue(selection.select(id, descriptor.uri, account.accounts.capture()?.binding?.lineage))
        }
        fun finish(page: PlaylistState = entries.activeState.value.copy(tracks = listOf(track), loading = false)) {
            ticket.update { page }
            entries.completed(reading)
        }
        fun lateReply(): CompletableDeferred<PlaylistState> {
            val reply = CompletableDeferred<PlaylistState>()
            val captured = ticket
            job = scope.launch {
                captured.bind(currentCoroutineContext()[Job])
                val page = withContext(NonCancellable) { reply.await() }
                try { captured.update { page } } catch (_: CancellationException) { /* denied receipt */ }
            }
            return reply
        }
    }

    @Test fun artistAlbumABackAlbumBRetainsExactArtistStateAndText() = runTest {
        val c = Consumer(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        c.open("artist-entry", artist); c.finish(c.entries.activeState.value.copy(loading = false, following = true, followers = 42))
        val artistPage = c.entries.activeState.value
        c.entries.memory("artist-entry").query = "remember this filter"
        c.open("album-a-entry", a); c.finish()
        c.open("artist-entry", artist)
        assertSame(artistPage, c.entries.activeState.value)
        assertEquals("remember this filter", c.entries.memory("artist-entry").query)
        assertEquals(2, c.loads) // Back must not ask the artist's service again.
        c.open("album-b-entry", b); c.finish()
        assertEquals(b.uri, c.entries.activeState.value.uri)
        assertSame(artistPage, c.entries.state("artist-entry").value)
    }

    @Test fun sameUriInTwoEntriesHasIndependentStateAndInvalidatesOldMenuGeneration() = runTest {
        val c = Consumer(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        c.open("one", playlist); c.finish()
        val menu = c.edits.removalAction(c.entries.activeState.value, track, 0)
        c.open("two", playlist); c.finish(c.entries.activeState.value.copy(loading = false, error = "New entry error"))
        menu(); runCurrent()
        assertEquals(0, c.removals)
        assertEquals(PlaylistEditFeedback.Reason.SELECTION_CHANGED, c.edits.feedback.value.single().reason)
        assertNull(c.entries.state("one").value.error)
        assertEquals("New entry error", c.entries.state("two").value.error)
    }

    @Test fun inProgressArtistRestartsOnReturnWhileCompletedErrorIsRetained() = runTest {
        val c = Consumer(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        c.open("artist", artist)
        c.open("a", a); c.finish(c.entries.activeState.value.copy(loading = false, error = "Synthetic failure"))
        c.open("artist", artist)
        assertEquals(3, c.loads)
        assertTrue(c.entries.activeState.value.loading)
        c.finish()
        c.open("a", a)
        assertEquals(3, c.loads)
        assertEquals("Synthetic failure", c.entries.activeState.value.error)
    }

    @Test fun confirmedEditUsesTheCurrentEntryInsteadOfTheOriginallyCapturedFlow() = runTest {
        val c = Consumer(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        c.open("one", playlist); c.finish()
        val previous = c.entries.activeState
        c.open("two", playlist); c.finish()
        c.edits.rename(playlist.uri, "Confirmed new title"); runCurrent()
        assertEquals("Confirmed new title", c.entries.activeState.value.name)
        assertEquals("Playlist", previous.value.name)
        assertEquals("Confirmed new title", (c.library.value as MainViewModel.UiState.Ready).playlists.single().name)
    }

    @Test fun completeContentIsRetainedWhenOptionalMetadataWasStillPendingOnNavigation() = runTest {
        val c = Consumer(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        c.open("artist", artist)
        // Use the production read ticket's ready callback; leave its bound metadata job suspended.
        c.ticket.update { it.copy(loading = false, tracks = listOf(track), following = true) }
        val pendingMetadata = c.lateReply(); runCurrent()
        c.open("a", a); c.finish()
        pendingMetadata.complete(PlaylistState(uri = artist.uri, name = "Late metadata")); runCurrent()
        c.open("artist", artist)
        assertEquals(2, c.loads)
        assertEquals("Artist", c.entries.activeState.value.name)
        assertEquals(listOf(track), c.entries.activeState.value.tracks)
        assertTrue(c.entries.activeState.value.following == true)
    }

    @Test fun nonCooperativeOldResponseCannotReplaceCurrentEntryOrRetainedEntry() = runTest {
        val c = Consumer(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        c.open("artist", artist)
        val old = c.lateReply(); runCurrent()
        c.open("a", a); c.finish()
        old.complete(PlaylistState(uri = artist.uri, name = "LATE", error = "LATE ERROR")); runCurrent()
        assertEquals(a.uri, c.entries.activeState.value.uri)
        assertEquals("Artist", c.entries.state("artist").value.name)
        assertTrue(c.entries.state("artist").value.loading)
    }

    @Test fun oldReadCannotPublishMetadataOrErrorAfterReopeningTheSameUri() = runTest {
        val c = Consumer(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        c.open("one", a); val old = c.ticket
        c.open("two", a); c.finish()
        try { old.update { it.copy(name = "Stale metadata", error = "Stale error") }; fail("accepted stale read") }
        catch (_: CancellationException) { }
        assertEquals("Album A", c.entries.activeState.value.name)
        assertNull(c.entries.activeState.value.error)
    }

    @Test fun revocationImmediatelyBlanksEveryHeldFlowAndRejectsAlreadyCapturedWriters() = runTest {
        val c = Consumer(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        c.open("artist", artist); c.finish()
        val held = c.entries.activeState
        c.entries.memory("artist").query = "private filter"
        c.open("a", a); c.finish()
        val observer = c.account.accounts.observe { c.selection.revoke() }
        // observe also revokes its initial snapshot, as MainViewModel does at construction.
        assertNull(held.value.uri)
        held.value = PlaylistState(uri = artist.uri, name = "Forbidden")
        assertNull(held.value.uri)
        assertEquals("", c.entries.memory("artist").query)
        c.account.logout()
        assertNull(c.entries.state("a").value.uri)
        observer.close()
    }

    @Test fun changedAccountRejectsOldAuthorityAndLateReceiptBeforeItsMainCollectorRuns() = runTest {
        val account = AccountLocalDataFixture().also { it.connect() }
        val c = Consumer(account, backgroundScope)
        c.open("old", a)
        val oldTicket = c.ticket
        val lineage = account.accounts.capture()!!.binding.lineage
        val observer = account.accounts.observe { c.selection.revoke() }
        account.connect("account-b")
        assertFalse(c.selection.select("restored-old", a.uri, lineage))
        c.open("new", b); c.finish()
        try { oldTicket.update { it.copy(name = "Account A") }; fail("accepted foreign receipt") }
        catch (_: CancellationException) { }
        assertEquals(b.uri, c.entries.activeState.value.uri)
        assertNull(c.entries.state("old").value.uri)
        observer.close()
    }

    @Test fun revocationTombstoneCannotBeReboundEvenWithTheSameLineage() = runTest {
        val c = Consumer(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        c.open("old", artist); c.finish()
        c.selection.revoke()
        assertFalse(c.selection.select("old", artist.uri, c.account.accounts.capture()!!.binding.lineage))
        c.open("new", artist)
        assertEquals(2, c.loads)
    }

    @Test fun poppingOneLeaseReleasesOnlyThatEntryAndBlanksItsHeldFlow() = runTest {
        val c = Consumer(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        c.open("artist", artist); c.finish()
        val retained = c.entries.state("artist").value
        c.open("a", a); c.finish(); val leaving = c.entries.activeState
        c.open("artist", artist)
        c.entries.release("a")
        assertNull(leaving.value.uri)
        assertSame(retained, c.entries.activeState.value)
    }

    @Test fun recreatedConsumerReloadsOnlyIdentifiersUnderTheSameAuthority() = runTest {
        val account = AccountLocalDataFixture().also { it.connect() }
        val old = Consumer(account, backgroundScope)
        old.open("stable-nav-id", artist); old.finish()
        old.entries.memory("stable-nav-id").query = "private filter"
        val recreated = Consumer(account, backgroundScope)
        assertTrue(recreated.selection.select("stable-nav-id", artist.uri, account.accounts.capture()!!.binding.lineage))
        assertEquals(1, recreated.loads)
        assertEquals(artist.uri, recreated.entries.activeState.value.uri)
        assertTrue(recreated.entries.activeState.value.loading)
        assertTrue(recreated.entries.activeState.value.tracks.isEmpty())
        assertEquals("", recreated.entries.memory("stable-nav-id").query)
    }
}
