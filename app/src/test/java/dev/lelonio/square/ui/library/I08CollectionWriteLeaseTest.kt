package dev.lelonio.square.ui.library

import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.SpotifyNetworkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real dispatch/publication guard, synthetic writes. Prepared, NOT EXECUTED. */
class I08CollectionWriteLeaseTest {
    @Before fun online() { SpotifyNetworkPolicy.setManual(false) }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }

    @Test fun manualBlocksNewWritesAndRevokesOldReceiptEvenAfterReturningOnline() = runTest {
        val account = AccountLocalDataFixture(); account.connect()
        val old = CollectionWriteLease.capture(account.accounts)!!
        var dispatches = 0
        var commits = 0
        old.dispatch { dispatches++ }
        SpotifyNetworkPolicy.setManual(true)
        assertNull(CollectionWriteLease.capture(account.accounts))
        SpotifyNetworkPolicy.setManual(false)
        assertTrue(runCatching { old.confirm { commits++ } }.exceptionOrNull() is SpotifyNetworkPolicy.Offline)
        assertTrue(runCatching { old.dispatch { dispatches++ } }.exceptionOrNull() is SpotifyNetworkPolicy.Offline)
        val fresh = CollectionWriteLease.capture(account.accounts)!!
        fresh.dispatch { dispatches++ }; fresh.confirm { commits++ }
        assertEquals(2, dispatches)
        assertEquals(1, commits)
    }

    @Test fun accountReplacementCannotConfirmIntoAnotherLibraryAndFailureIsNeverReplayed() = runTest {
        val account = AccountLocalDataFixture(); account.connect("a")
        val old = CollectionWriteLease.capture(account.accounts)!!
        var dispatches = 0
        var commits = 0
        assertTrue(runCatching { old.dispatch { dispatches++; error("ambiguous write") } }.isFailure)
        assertEquals(1, dispatches)
        account.connect("b")
        assertFalse(old.accountCurrent())
        assertTrue(runCatching { old.confirm { commits++ } }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { old.dispatch { dispatches++ } }.exceptionOrNull() is CancellationException)
        assertEquals(0, commits)
        assertEquals(1, dispatches)
        CollectionWriteLease.capture(account.accounts)!!.dispatch { dispatches++ }
        assertEquals(2, dispatches)
    }
}
