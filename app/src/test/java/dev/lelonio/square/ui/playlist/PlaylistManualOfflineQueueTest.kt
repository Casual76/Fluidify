package dev.lelonio.square.ui.playlist

import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.ui.MainViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistManualOfflineQueueTest {
    @Before fun online() { SpotifyNetworkPolicy.setManual(false) }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }

    @Test fun queuedWriteAndNonCooperativeReceiptKeepTheOriginalLeaseAcrossOnOff() = runTest {
        val account = AccountLocalDataFixture(); val access = account.connect()
        val row = CatalogPlaylist("spotify:playlist:synthetic", "Original")
        val library = MutableStateFlow<MainViewModel.UiState>(MainViewModel.UiState.Ready("Synthetic", listOf(row)))
        val detail = MutableStateFlow(MainViewModel.PlaylistState(uri = row.uri, name = row.name))
        val receipts = mutableListOf<CompletableDeferred<Unit>>()
        val service = object : PlaylistEditService {
            override suspend fun create(name: String): CatalogPlaylist = error("unused")
            override suspend fun rename(uri: String, name: String) {
                val receipt = CompletableDeferred<Unit>(); receipts += receipt
                withContext(NonCancellable) { receipt.await() }
            }
            override suspend fun delete(uri: String) = error("unused")
            override suspend fun remove(uri: String, trackUri: String, index: Int) = error("unused")
        }
        val edits = PlaylistEdits(backgroundScope, account.accounts, library, detail, { service },
            invalidate = { _, _ -> }, stopDetailRead = {}, reloadDetail = {})
        edits.rename(row.uri, "Old receipt"); runCurrent()
        edits.rename(row.uri, "Queued old write"); runCurrent()
        assertEquals(1, receipts.size)
        SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
        receipts.single().complete(Unit); runCurrent()
        assertEquals(1, receipts.size)
        assertEquals("Original", detail.value.name)
        assertSame(access, account.accounts.capture())
        edits.rename(row.uri, "Fresh"); runCurrent()
        assertEquals(2, receipts.size)
        receipts.last().complete(Unit); runCurrent()
        assertEquals("Fresh", detail.value.name)
    }
}
