package dev.lelonio.square.ui.library

import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.ui.MainViewModel.AddToPlaylistState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Test

class RootCollectionPickerRecoveryTest {
    private val busy = AddToPlaylistState(open = true, trackUri = "spotify:track:a", trackTitle = "A",
        playlists = listOf(CatalogPlaylist("spotify:playlist:p", "P")), busy = "spotify:playlist:p",
        revision = 4, accountSession = "account-a")

    @Test fun anUnavailableCapabilityEndsTheSameSessionsBusyStateWithoutReplayingOrConfirming() {
        val state = MutableStateFlow(busy)
        state.update { revokeCollectionPicker(it, "account-a", "unconfirmed") }
        assertNull(state.value.busy)
        assertNull(state.value.done)
        assertNull(state.value.removed)
        assertEquals("unconfirmed", state.value.error)
        assertEquals(busy.trackUri, state.value.trackUri)
        assertTrue(state.value.revision > busy.revision)
        assertFalse("old completion cannot publish", state.compareAndSet(busy, busy.copy(done = "P", busy = null)))
        assertSame(state.value, finishCollectionPickerAttempt(state.value, busy.revision, busy.busy!!, "old"))
    }

    @Test fun aNewAccountClearsTheCapturedTrackDestinationsAndReceiptSynchronously() {
        val reset = revokeCollectionPicker(busy, "account-b", "unconfirmed")
        assertFalse(reset.open)
        assertNull(reset.trackUri)
        assertTrue(reset.playlists.isEmpty())
        assertNull(reset.busy)
        assertNull(reset.accountSession)
        assertTrue(reset.revision > busy.revision)
    }

    @Test fun terminalCleanupCannotTouchANewPanelOrNewAttempt() {
        val newAttempt = busy.copy(revision = 5, busy = "spotify:playlist:new")
        assertSame(newAttempt, finishCollectionPickerAttempt(newAttempt, 4, busy.busy!!, "old"))
        val cancelled = finishCollectionPickerAttempt(busy, 4, busy.busy!!, "unconfirmed")
        assertNull(cancelled.busy)
        assertEquals("unconfirmed", cancelled.error)
    }

    @Test fun anOrdinaryConfirmedOrFailedOutcomeIsNotRewrittenByFinally() {
        val confirmed = busy.copy(busy = null, done = "P")
        assertSame(confirmed, finishCollectionPickerAttempt(confirmed, 4, busy.busy!!, "unconfirmed"))
        val failed = busy.copy(busy = null, error = "specific error")
        assertSame(failed, finishCollectionPickerAttempt(failed, 4, busy.busy!!, "unconfirmed"))
    }
}
