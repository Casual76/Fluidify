package dev.lelonio.square.wear

import dev.lelonio.square.auth.LocalSpotifyAuthority
import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.lelonio.square.data.HistoryAccess
import dev.lelonio.square.data.HistoryAccount
import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.pampa.fluidify.wear.protocol.AckErrors
import dev.pampa.fluidify.wear.protocol.AuthRequest
import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.CommandEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic durable credentials only. No Android service, real login or JNI is started. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class I11WearRequestSessionTest {
    private class ColdPhone {
        var receipt: String? = null
        var kept: LocalSpotifyAuthority.KeptIdentity? = LocalSpotifyAuthority.KeptIdentity("a", "synthetic-a")
        var history: HistoryAccess? = null
        var epoch: Long? = null
        var nativeId = "a"
        var ready = false
        val authority = LocalSpotifyAuthority({ receipt }, { receipt = it }, { kept })
        val accounts = AccountLocalDataCoordinator({ history }, { epoch }, { nativeId }, authority, { ready })
        val local = checkNotNull(accounts.capture())
        val session = I11WearRequestSession(accounts, local, requireNativeScope = false) // synthetic JNI-free producer
        suspend fun verify(id: String = "a", generation: Long = 1) {
            nativeId = id
            history = HistoryAccess(HistoryAccount(id), generation)
            epoch = generation
            ready = true
            check(accounts.verifyNativeAccount())
        }
        suspend fun replace(id: String = "b") {
            accounts.revoke()
            kept = LocalSpotifyAuthority.KeptIdentity(id, "synthetic-new-$id")
            verify(id, 2)
        }
    }

    @Before fun onlineAllowed() { SpotifyNetworkPolicy.setManual(false) }
    @After fun restorePolicy() { SpotifyNetworkPolicy.setManual(false) }

    @Test fun localLikeWaitsForTheSameAccountThenWritesOnce() = runTest {
        val phone = ColdPhone()
        val request = CommandEnvelope(3, Command.SetLiked("spotify:track:a", true), phone.local.requestSession)
        assertSame(phone.local, WearCommandAccess.capture(phone.accounts, request))
        var writes = 0
        val result = i11WearCommandResult(request.command, 8_000) {
            val online = phone.session.onlineAccess(7_000) { phone.verify(); true }
            assertEquals("a", online!!.account.id)
            phone.session.onlineIO { writes++ }
            null
        }
        assertNull("successful null result is distinct from timeout", result)
        assertEquals(1, writes)
        assertTrue(phone.session.isCurrent())
    }

    @Test fun connectedEngineWithoutCanonicalVerificationCannotWrite() = runTest {
        val phone = ColdPhone()
        var writes = 0
        val online = phone.session.onlineAccess(40) { true }
        if (online != null) phone.session.onlineIO { writes++ }
        assertNull(online)
        assertEquals(0, writes)
        assertFalse(phone.session.isOnlineCurrent())
    }

    @Test fun accountVerificationCanFinishAfterEngineReportsConnected() = runTest {
        val phone = ColdPhone()
        val verification = launch { delay(30); phone.verify() }
        assertNotNull(phone.session.onlineAccess(100) { true })
        verification.join()
        assertTrue(phone.session.isOnlineCurrent())
    }

    @Test fun idleEngineRevokesOnlineProofButKeepsTheSameAccountsWireCorrelation() = runTest {
        val phone = ColdPhone()
        phone.verify()
        val online = phone.accounts.captureOnline()!!
        phone.ready = false // Idle service teardown does not change the account lifecycle epoch.
        val idle = phone.accounts.captureRequest(online.requestSession)!!
        assertEquals(AccountLocalDataCoordinator.Origin.LOCAL, idle.origin)
        assertEquals(online.requestSession, idle.requestSession)
        assertNotSame(online, idle)
        assertFalse(phone.accounts.isCurrent(online))
        assertNull(phone.accounts.captureOnline())
        try { phone.accounts.requireOnline(online); fail("old proof must be revoked") }
        catch (_: CancellationException) { }
    }

    @Test fun capturingLocalDataDoesNotProbeOrLoadTheNativeEngine() {
        var probes = 0
        val authority = LocalSpotifyAuthority({ null }, {}, {
            LocalSpotifyAuthority.KeptIdentity("a", "synthetic-a")
        })
        val accounts = AccountLocalDataCoordinator({ null }, { null }, { error("not admitted") },
            authority, { probes++; false })
        val local = accounts.capture()!!
        assertSame(local, accounts.captureRequest(local.requestSession))
        assertTrue(accounts.isCurrent(local))
        assertNull(accounts.captureOnline())
        assertEquals("pure local reads must not load JNI or wake playback", 0, probes)
    }

    @Test fun firstCommandAfterIdleVerifiesTheReadyEngineAndWritesOnlyOnce() = runTest {
        val phone = ColdPhone()
        phone.verify()
        val wire = phone.accounts.captureOnline()!!.requestSession
        phone.ready = false
        val idle = phone.accounts.captureRequest(wire)!!
        val request = I11WearRequestSession(phone.accounts, idle, requireNativeScope = false)
        var wakes = 0
        var writes = 0
        val online = request.onlineAccess(100) {
            wakes++
            phone.ready = true // No external verify() callback: the request must verify the live identity.
            true
        }!!
        assertEquals(AccountLocalDataCoordinator.Origin.ONLINE, online.origin)
        assertEquals(wire, online.requestSession)
        request.onlineIO { writes++ }
        assertTrue(request.isOnlineCurrent())
        assertEquals(1, wakes)
        assertEquals(1, writes)
    }

    @Test fun localTransportSurvivesTheSameProofPromotionWhileBinding() = runTest {
        val phone = ColdPhone()
        val request = phone.session
        assertTrue(request.bootstrapCurrent())
        phone.verify()
        assertTrue(request.isCurrent())
        assertSame(phone.accounts.captureOnline(), request.access())
        var plays = 0
        request.use { plays++ }
        assertEquals(1, plays)
        phone.replace("a")
        assertFalse(request.isCurrent())
        try { request.use { plays++ }; fail("same name with new login is a different authority") }
        catch (_: I11WearRequestSession.AccountChanged) { }
        assertEquals(1, plays)
    }

    @Test fun confirmingTheSameBindingNeverPublishesATransientLogoutToObservers() = runTest {
        val phone = ColdPhone()
        val seen = mutableListOf<AccountLocalDataCoordinator.Access?>()
        val observer = phone.accounts.observe { seen += it }
        try {
            phone.verify()
            assertEquals(listOf(AccountLocalDataCoordinator.Origin.LOCAL,
                AccountLocalDataCoordinator.Origin.ONLINE), seen.map { it?.origin })
            phone.ready = false
            phone.accounts.capture()
            phone.ready = true
            check(phone.accounts.verifyNativeAccount())
            assertTrue("same binding must not look signed out on the wire", seen.none { it == null })
            phone.accounts.revoke()
            assertNull("actual logout must still be synchronous", seen.last())
        } finally { observer.close() }
    }

    @Test fun firstQueueControlWaitsForRestoreAndSameAccountVerification() = runTest {
        val phone = ColdPhone()
        var restored = false
        var commands = 0
        val restoration = launch { delay(150); phone.verify(); restored = true }
        assertTrue(phone.session.awaitRestoredQueue(500) { restored })
        phone.session.use { commands++ }
        restoration.join()
        assertEquals(1, commands)
        assertEquals(AccountLocalDataCoordinator.Origin.ONLINE, phone.session.access().origin)
    }

    @Test fun aRestoreTimeoutCannotAcknowledgeOrReplayAnEmptyControl() = runTest {
        val phone = ColdPhone()
        var restored = false
        var commands = 0
        if (phone.session.awaitRestoredQueue(150) { restored }) phone.session.use { commands++ }
        assertEquals(0, commands)
        restored = true
        delay(500)
        assertEquals("a late queue is not an implicit retry of the old gesture", 0, commands)
        assertTrue(phone.session.awaitRestoredQueue(150) { restored })
        phone.session.use { commands++ } // Only a new explicit command may act.
        assertEquals(1, commands)
    }

    @Test fun queueRestorationForAccountBDoesNotReceiveAnOldAControl() = runTest {
        val phone = ColdPhone()
        var restored = false
        var commands = 0
        val restoration = launch { delay(50); phone.replace(); restored = true }
        try {
            if (phone.session.awaitRestoredQueue(500) { restored }) phone.session.use { commands++ }
            fail("a queue under B must reject A before applying the control")
        } catch (_: I11WearRequestSession.AccountChanged) { }
        restoration.join()
        assertEquals(0, commands)
    }

    @Test fun idleAccountACannotBorrowANativeAccountBAfterWake() = runTest {
        val phone = ColdPhone()
        phone.verify()
        val wire = phone.accounts.captureOnline()!!.requestSession
        phone.ready = false
        val request = I11WearRequestSession(phone.accounts, phone.accounts.captureRequest(wire)!!,
            requireNativeScope = false)
        var writes = 0
        try {
            request.onlineAccess(100) { phone.nativeId = "b"; phone.ready = true; true }
            request.onlineIO { writes++ }
            fail("canonical mismatch must revoke rather than rebind")
        } catch (_: I11WearRequestSession.AccountChanged) { }
        assertEquals(0, writes)
        assertNull(phone.accounts.capture())
        assertFalse(request.isCurrent())
    }

    @Test fun historyGenerationChangeCannotPromoteAnOlderRequestEvenForTheSameBinding() = runTest {
        val phone = ColdPhone()
        phone.verify()
        phone.ready = false
        val idle = phone.accounts.capture()!!
        val request = I11WearRequestSession(phone.accounts, idle, requireNativeScope = false)
        try {
            request.onlineAccess(100) {
                phone.verify() // First promotion is valid for this request.
                phone.history = HistoryAccess(HistoryAccount("a"), 2)
                phone.accounts.historyChanged() // A distinct history generation revokes the old request session.
                check(phone.accounts.verifyNativeAccount())
                true
            }
            fail("an old request cannot cross a generation change")
        } catch (_: I11WearRequestSession.AccountChanged) { }
        assertFalse(request.isCurrent())
        assertNotEquals(idle.requestSession, phone.accounts.capture()!!.requestSession)
    }

    /** Integration gate for the precise AccountLocalDataCoordinator patch assigned to root. */
    @Test fun durablePromotionKeepsWireSessionButNewLoginDoesNot() = runTest {
        val phone = ColdPhone()
        val original = phone.local.requestSession
        val online = phone.session.onlineAccess(100) { phone.verify(); true }!!
        assertEquals("root must preserve pending Wear request correlation", original, online.requestSession)
        phone.replace("a")
        assertNotEquals(original, phone.accounts.capture()!!.requestSession)
        assertNull(phone.accounts.captureRequest(original))
    }

    @Test fun unavailableLocalPlaylistHasAnExplicitFailure() = runTest {
        val phone = ColdPhone()
        val command = Command.AddToPlaylist("spotify:playlist:a", "spotify:track:a")
        val error = i11WearCommandResult(command, 100) {
            if (phone.session.onlineAccess(40) { false } == null) AckErrors.PLAYLIST else null
        }
        assertEquals(AckErrors.PLAYLIST, error)
    }

    @Test fun accountBIsNeverRecapturedForAnARequestDuringBootstrap() = runTest {
        val phone = ColdPhone()
        var writes = 0
        try {
            phone.session.onlineAccess(100) { phone.replace(); true }
            phone.session.onlineIO { writes++ }
            fail("A must not borrow B")
        } catch (_: I11WearRequestSession.AccountChanged) { }
        assertEquals(0, writes)
        assertEquals("b", phone.accounts.capture()!!.account.id)
        assertFalse(phone.session.isCurrent())
    }

    @Test fun aNewLoginOfTheSameCanonicalIsAlsoRejected() = runTest {
        val phone = ColdPhone()
        try {
            phone.session.onlineAccess(100) { phone.replace("a"); true }
            fail("same name is not the same durable authority")
        } catch (_: I11WearRequestSession.AccountChanged) { }
        assertFalse(phone.session.isCurrent())
    }

    @Test fun aRevokedOnlineRequestCannotDispatchOrPublishIntoB() = runTest {
        val phone = ColdPhone()
        phone.session.onlineAccess(100) { phone.verify(); true }
        phone.replace()
        var writes = 0
        try { phone.session.onlineIO { writes++ }; fail("revoked dispatch") }
        catch (_: I11WearRequestSession.AccountChanged) { }
        try { phone.session.use { writes++ }; fail("revoked publication") }
        catch (_: I11WearRequestSession.AccountChanged) { }
        assertEquals(0, writes)
    }

    @Test fun manualOfflineNeverWakesTheEngineButNoSessionMayRecover() = runTest {
        val phone = ColdPhone()
        var wakes = 0
        SpotifyNetworkPolicy.setManual(true)
        try {
            phone.session.onlineAccess(100) { wakes++; true }
            fail("manual network prohibition")
        } catch (_: SpotifyNetworkPolicy.Offline) { }
        assertEquals(0, wakes)
        SpotifyNetworkPolicy.setManual(false)
        assertNotNull(phone.session.onlineAccess(100) { wakes++; phone.verify(); true })
        assertEquals(1, wakes)
    }

    @Test fun callerCancellationNeverContinuesIntoAWriteOrAReply() = runTest {
        val phone = ColdPhone()
        val entered = CompletableDeferred<Unit>()
        var writes = 0
        val job = launch {
            phone.session.onlineAccess(7_000) { entered.complete(Unit); awaitCancellation() }
            phone.session.onlineIO { writes++ }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(0, writes)
        assertTrue(job.isCancelled)
    }

    @Test fun commandDeadlineReturnsTheActionError() = runTest {
        assertEquals(AckErrors.LIKE, i11WearCommandResult(Command.SetLiked("track", true), 20) { awaitCancellation() })
        assertEquals(AckErrors.TRANSFER, i11WearCommandResult(Command.Transfer("phone"), 20) { awaitCancellation() })
    }

    @Test fun coldAuthWaitsForBootstrapAndKeepsRequestCorrelation() = runTest {
        val phone = ColdPhone()
        val request = AuthRequest(17, accountSession = phone.local.requestSession)
        var tokenReads = 0
        val grant = i11WearAuthGrant(request, phone.session,
            bootstrap = { phone.verify(); true },
            token = { tokenReads++; "synthetic-short-lived" }, tokenCurrent = { true }, clientId = "synthetic")
        assertTrue(grant.ok)
        assertEquals(1, tokenReads)
        assertEquals("a", grant.username)
        assertEquals(request.accountSession, grant.accountSession)
    }

    @Test fun unavailableAuthAlwaysRepliesWithTheOriginalIdAndSession() = runTest {
        val phone = ColdPhone()
        val request = AuthRequest(18, accountSession = phone.local.requestSession)
        val grant = i11WearAuthGrant(request, phone.session, bootstrap = { false },
            token = { error("not admitted") }, tokenCurrent = { true }, clientId = "synthetic")
        assertFalse(grant.ok)
        assertEquals(request.id, grant.id)
        assertEquals(request.accountSession, grant.accountSession)
        assertNull(grant.accessToken)
    }

    @Test fun authForAAfterAccountSwitchNeverReadsOrLendsBTokens() = runTest {
        val phone = ColdPhone()
        var reads = 0
        val grant = i11WearAuthGrant(AuthRequest(19, accountSession = phone.local.requestSession), phone.session,
            bootstrap = { phone.replace(); true }, token = { reads++; "must-not-lend" },
            tokenCurrent = { true }, clientId = "synthetic")
        assertFalse(grant.ok)
        assertNull(grant.accessToken)
        assertEquals(0, reads)
    }

    @Test fun authTokenGenerationChangeDuringRefreshReturnsNoToken() = runTest {
        val phone = ColdPhone()
        var tokenCurrent = true
        val grant = i11WearAuthGrant(AuthRequest(20, accountSession = phone.local.requestSession), phone.session,
            bootstrap = { phone.verify(); true }, token = { tokenCurrent = false; "must-not-lend" },
            tokenCurrent = { tokenCurrent }, clientId = "synthetic")
        assertFalse(grant.ok)
        assertNull(grant.accessToken)
    }

    @Test fun networkRevokedDuringAuthCannotLendAnOtherwiseFreshToken() = runTest {
        val phone = ColdPhone()
        val grant = i11WearAuthGrant(AuthRequest(22, accountSession = phone.local.requestSession), phone.session,
            bootstrap = { phone.verify(); true }, token = { SpotifyNetworkPolicy.setManual(true); "must-not-lend" },
            tokenCurrent = { true }, clientId = "synthetic")
        assertFalse(grant.ok)
        assertNull(grant.accessToken)
    }

    @Test fun authDomainCancellationGetsARefusalButCallerCancellationPropagates() = runTest {
        val phone = ColdPhone()
        val request = AuthRequest(21, accountSession = phone.local.requestSession)
        val refused = i11WearAuthGrant(request, phone.session, bootstrap = { throw CancellationException("domain") },
            token = { error("not admitted") }, tokenCurrent = { true }, clientId = "synthetic")
        assertFalse(refused.ok)
        val call = async { i11WearAuthGrant(request, phone.session, bootstrap = { awaitCancellation() },
            token = { error("not admitted") }, tokenCurrent = { true }, clientId = "synthetic") }
        runCurrent()
        call.cancelAndJoin()
        assertTrue(call.isCancelled)
    }
}
