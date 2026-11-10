package dev.lelonio.square.ui.playlist

import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.ui.MainViewModel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistRevocationFeedbackTest {
    @Before fun online() { SpotifyNetworkPolicy.setManual(false) }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }

    private class Service : PlaylistEditService {
        val reply = CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun rename(uri: String, name: String) {
            calls++
            withContext(NonCancellable) { reply.await() }
        }
        override suspend fun create(name: String): CatalogPlaylist = error("unused")
        override suspend fun delete(uri: String) = error("unused")
        override suspend fun remove(uri: String, trackUri: String, index: Int) = error("unused")
    }
    private class Fixture(val account: AccountLocalDataFixture, val service: Service,
        val edits: PlaylistEdits, val detail: MutableStateFlow<MainViewModel.PlaylistState>)

    private suspend fun TestScope.fixture(): Fixture {
        val account = AccountLocalDataFixture().also { it.connect() }
        val row = CatalogPlaylist("spotify:playlist:synthetic", "Before")
        val library = MutableStateFlow<MainViewModel.UiState>(MainViewModel.UiState.Ready("Synthetic", listOf(row)))
        val detail = MutableStateFlow(MainViewModel.PlaylistState(uri = row.uri, name = row.name))
        val service = Service()
        val edits = PlaylistEdits(backgroundScope, account.accounts, library, detail, { service },
            invalidate = { _, _ -> }, stopDetailRead = {}, reloadDetail = {},
            onlineAllowed = { !SpotifyNetworkPolicy.manual.value })
        return Fixture(account, service, edits, detail)
    }
    private fun TestScope.dispatch(f: Fixture) {
        f.edits.rename(checkNotNull(f.detail.value.uri), "Server accepted")
        runCurrent()
        assertEquals(1, f.service.calls)
    }
    private fun assertUncertain(f: Fixture) {
        assertEquals("Before", f.detail.value.name)
        assertEquals(1, f.service.calls)
        assertEquals(PlaylistEditFeedback.Reason.UNCONFIRMED, f.edits.feedback.value.single().reason)
    }

    @Test fun revokedReplyWhileOfflineHasUncertainFeedback() = runTest {
        val f = fixture(); dispatch(f)
        SpotifyNetworkPolicy.setManual(true)
        f.service.reply.complete(Unit); runCurrent()
        assertUncertain(f)
    }
    @Test fun revokedReplyAfterRoundTripHasUncertainFeedback() = runTest {
        val f = fixture(); dispatch(f)
        SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
        f.service.reply.complete(Unit); runCurrent()
        assertUncertain(f)
    }
    @Test fun failedTransportWhileOfflineStillReportsUncertainty() = runTest {
        val f = fixture(); dispatch(f)
        SpotifyNetworkPolicy.setManual(true)
        f.service.reply.completeExceptionally(IOException("synthetic ambiguous transport")); runCurrent()
        assertUncertain(f)
    }
    @Test fun replacementAccountDoesNotReceiveTheOldUncertainOutcome() = runTest {
        val f = fixture(); dispatch(f)
        SpotifyNetworkPolicy.setManual(true)
        f.account.connect("account-b"); runCurrent()
        f.service.reply.complete(Unit); runCurrent()
        assertEquals("Before", f.detail.value.name)
        assertTrue(f.edits.feedback.value.isEmpty())
        assertEquals(1, f.service.calls)
    }
    @Test fun changedDestinationDoesNotReceiveTheOldUncertainOutcome() = runTest {
        val f = fixture(); dispatch(f)
        SpotifyNetworkPolicy.setManual(true)
        f.edits.destinationChanged()
        f.service.reply.complete(Unit); runCurrent()
        assertTrue(f.edits.feedback.value.isEmpty())
        assertEquals(1, f.service.calls)
    }
    @Test fun offlineBeforeDispatchNeverCallsTheService() = runTest {
        val f = fixture()
        SpotifyNetworkPolicy.setManual(true)
        f.edits.rename(checkNotNull(f.detail.value.uri), "Denied"); runCurrent()
        assertEquals(0, f.service.calls)
        assertEquals(PlaylistEditFeedback.Reason.UNAVAILABLE, f.edits.feedback.value.single().reason)
    }
    @Test fun ordinarySuccessStillCommitsWithoutAnError() = runTest {
        val f = fixture(); dispatch(f)
        f.service.reply.complete(Unit); runCurrent()
        assertEquals("Server accepted", f.detail.value.name)
        assertTrue(f.edits.feedback.value.isEmpty())
        assertEquals(1, f.service.calls)
    }
}
