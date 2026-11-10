package dev.lelonio.square.ui.navigation

import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.playlist.PlaylistEditService
import dev.lelonio.square.ui.playlist.PlaylistEdits
import dev.lelonio.square.ui.requests.RequestOwnership
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Production title publisher, ticket and receipt overlay; synthetic authority/service only. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistNameReceiptTest {
    private val uri = "spotify:playlist:p"
    @Before fun online() { SpotifyNetworkPolicy.setManual(false) }
    @After fun restore() { SpotifyNetworkPolicy.setManual(false) }

    private inner class Fixture(account: AccountLocalDataFixture, scope: CoroutineScope) {
        val library = MutableStateFlow<MainViewModel.UiState>(MainViewModel.UiState.Loading)
        val page = MutableStateFlow(MainViewModel.PlaylistState(uri = uri, name = "", mine = true))
        val ownership = RequestOwnership()
        var writes = 0
        val edits = PlaylistEdits(scope, account.accounts, library, page,
            service = { object : PlaylistEditService {
                override suspend fun create(name: String) = CatalogPlaylist("spotify:playlist:new", name)
                override suspend fun rename(uri: String, name: String) { writes++ }
                override suspend fun delete(uri: String) = Unit
                override suspend fun remove(uri: String, trackUri: String, index: Int) = Unit
            } }, invalidate = { _, _ -> }, stopDetailRead = { ownership.invalidate() }, reloadDetail = {})
        // This is the production callback called by MainViewModel when nameOf completes.
        val ticket = PlaylistReadTicket(ownership.begin(), account.accounts,
            account.accounts.captureOnline(), edits.readVersion(), page)
        fun lateName(name: String) = ticket.publishName(name, edits::mergeDetailRead)
    }

    @Test fun lateNameCannotOverwriteAnAlreadyConfirmedRename() = runTest {
        val f = Fixture(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        f.edits.rename(uri, "Confirmed title"); runCurrent()
        assertEquals("Confirmed title", f.page.value.name)
        assertEquals(1, f.writes)
        f.lateName("Old title from the earlier request")
        assertEquals("Confirmed title", f.page.value.name)
        assertEquals(1, f.writes)
        f.ticket.ensureCurrent() // Protection cannot depend on cancelling this valid read.
    }

    @Test fun lateNameCannotOverwriteANewerLibraryTitle() = runTest {
        val f = Fixture(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        f.edits.publishLibraryRead(MainViewModel.UiState.Ready("Fixture",
            listOf(CatalogPlaylist(uri, "New library title"))), f.ticket.version)
        assertEquals("New library title", f.page.value.name)
        f.lateName("Old detail title")
        assertEquals("New library title", f.page.value.name)
        assertEquals("New library title", (f.library.value as MainViewModel.UiState.Ready).playlists.single().name)
        assertEquals(0, f.writes)
    }

    @Test fun unmodifiedReadCanPublishItsNewTitle() = runTest {
        val f = Fixture(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        f.lateName("Fresh title")
        assertEquals("Fresh title", f.page.value.name)
        assertEquals(0, f.writes)
    }

    @Test fun revokedTicketCannotPublishATitle() = runTest {
        val f = Fixture(AccountLocalDataFixture().also { it.connect() }, backgroundScope)
        f.ownership.invalidate()
        assertThrows(CancellationException::class.java) { f.lateName("Stale title") }
        assertEquals("", f.page.value.name)
        assertEquals(0, f.writes)
    }
}
