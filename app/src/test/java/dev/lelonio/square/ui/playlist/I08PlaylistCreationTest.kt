package dev.lelonio.square.ui.playlist

import dev.lelonio.square.backend.createdSpotifyPlaylist
import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.PlaylistDto
import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.library.editableCollectionDestinations
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

/** Confirmed creation -> actual account UI receipt -> actual picker projection; NOT EXECUTED. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class I08PlaylistCreationTest {
    @Before fun online() { SpotifyNetworkPolicy.setManual(false) }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }
    @Test fun confirmedCreationIsImmediatelyEditableSelectableAndSurvivesAnOlderLibraryRead() = runTest {
        val account = AccountLocalDataFixture(); account.connect("a")
        val library = MutableStateFlow<MainViewModel.UiState>(MainViewModel.UiState.Ready("a", emptyList()))
        val page = MutableStateFlow(MainViewModel.PlaylistState())
        var calls = 0
        var fail = true
        val receipt = CompletableDeferred<Unit>()
        val service = object : PlaylistEditService {
            override suspend fun create(name: String): CatalogPlaylist {
                calls++
                if (fail) throw IOException("not confirmed")
                receipt.await()
                return createdSpotifyPlaylist(PlaylistDto("new", "spotify:playlist:new", name), "a")
            }
            override suspend fun rename(uri: String, name: String) = error("unused")
            override suspend fun delete(uri: String) = error("unused")
            override suspend fun remove(uri: String, trackUri: String, index: Int) = error("unused")
        }
        val edits = PlaylistEdits(backgroundScope, account.accounts, library, page, { service },
            invalidate = { _, _ -> }, stopDetailRead = {}, reloadDetail = {})
        val oldRead = edits.readVersion()
        edits.create("First").join()
        assertTrue((library.value as MainViewModel.UiState.Ready).playlists.isEmpty())
        assertEquals(1, calls)
        fail = false
        edits.create("New")
        runCurrent()
        assertTrue((library.value as MainViewModel.UiState.Ready).playlists.isEmpty())
        receipt.complete(Unit); runCurrent()
        val rows = (library.value as MainViewModel.UiState.Ready).playlists
        assertEquals("a", rows.single().owner)
        assertEquals(true, rows.single().editable)
        assertEquals(rows, editableCollectionDestinations(rows, "a"))
        edits.publishLibraryRead(MainViewModel.UiState.Ready("a", emptyList()), oldRead)
        assertEquals(rows, (library.value as MainViewModel.UiState.Ready).playlists)
        assertEquals(2, calls) // only explicit retry, no rootlist restart or write replay
    }
}
