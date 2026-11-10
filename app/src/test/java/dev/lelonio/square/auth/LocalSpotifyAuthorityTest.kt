package dev.lelonio.square.auth

import android.app.Application
import androidx.room.Room
import dev.lelonio.square.data.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prepared only. All credential bytes and canonical identities in these tests are synthetic. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalSpotifyAuthorityTest {
    private val a = LocalSpotifyAuthority.KeptIdentity("account-a", "synthetic-credential-a")
    private val b = LocalSpotifyAuthority.KeptIdentity("account-b", "synthetic-credential-b")
    private val song = CatalogTrack("spotify:track:owned", "Owned", "Artist", addedAt = "2024-01-01T00:00:00Z")

    private class Disk(var state: String? = null, var kept: LocalSpotifyAuthority.KeptIdentity? = null) {
        fun authority() = LocalSpotifyAuthority({ state }, { state = it }, { kept })
    }
    private fun offline(authority: LocalSpotifyAuthority) = AccountLocalDataCoordinator(
        { null }, { null }, { error("Offline path must not perform native/provider identity lookup") }, authority, { false })

    @Test fun configuredNativeCacheBootstrapsOnlyLocalAuthorityWithoutUsernameLookup() {
        val disk = Disk(kept = a)
        val accounts = offline(disk.authority())
        assertEquals("account-a", accounts.capture()?.account?.id)
        assertEquals(AccountLocalDataCoordinator.Origin.LOCAL, accounts.capture()?.origin)
        assertNull(accounts.captureOnline())
        val current = accounts.capture()!!
        assertNull(accounts.captureRequest(null))
        assertSame(current, accounts.captureRequest(current.requestSession))
        try { accounts.requireOnline(current); fail("Local authority became provider proof") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun coldRestartRestoresOwnedCollectionsDatesAndSearchWithoutOnlineProof() = runTest {
        val context = accountDataTestContext()
        val first = AccountLocalDataFixture()
        val active = first.connect()
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, first.accounts)
        val searches = SearchHistoryStore(context, first.accounts, StandardTestDispatcher(testScheduler))
        store.setOwner(active, "playlist", listOf(song, song.copy(addedAt = "2025-01-01T00:00:00Z")), DownloadStore.OwnerLabel("A"))
        store.onCompleted(active, song.uri, DownloadStore.FileRecord("aabb", "OGG_320", 42, 320, 123))
        File(store.root, "audio/aa/bb").apply { parentFile!!.mkdirs(); writeText("synthetic-audio") }
        searches.record(active, song)
        val disk = Disk(first.receipt, first.kept)
        val restored = offline(disk.authority())
        val reopened = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, restored)
        reopened.load()
        assertEquals(listOf(song.addedAt, "2025-01-01T00:00:00Z"), reopened.tracksForOwner("playlist").map { it.addedAt })
        assertEquals(OwnerState.Complete, reopened.ownerState("playlist"))
        assertEquals(listOf(song), SearchHistoryStore(context, restored, StandardTestDispatcher(testScheduler)).tracks.value)
        assertNull(restored.captureOnline())
        assertNull(restored.captureRequest(active.requestSession))
        try { reopened.addSingle(active, song); fail("Lease from the previous process accepted") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun durableLogoutTombstoneWinsEvenIfProcessDiesBeforeCredentialDeletion() {
        val disk = Disk(kept = a)
        val authority = disk.authority()
        val accounts = offline(authority)
        val old = accounts.capture()!!
        authority.invalidateBefore { /* process stops before deleting the still present old blob */ }
        assertNull(accounts.capture())
        assertNull(offline(disk.authority()).capture())
        assertNull(accounts.captureRequest(old.requestSession))
    }

    @Test fun replacedOrMissingCredentialRevokesAndCannotResurrectWhenOldBytesReturn() {
        val disk = Disk(kept = a)
        val accounts = offline(disk.authority())
        val original = accounts.capture()!!
        disk.kept = b
        assertNull(accounts.capture())
        disk.kept = a
        assertNull(offline(disk.authority()).capture())
        assertFalse(accounts.isCurrent(original))
    }

    @Test fun durableRevocationFailurePreventsCredentialMutation() {
        var saved: String? = null
        var reject = false
        val authority = LocalSpotifyAuthority({ saved }, { if (reject) throw IOException("injected") else saved = it }, { a })
        val accounts = offline(authority)
        reject = true
        var changed = false
        try { authority.invalidateBefore { changed = true }; fail("Expected durable write failure") }
        catch (_: IOException) { }
        assertFalse(changed)
        assertNull(accounts.capture())
    }

    @Test fun unknownReceiptDoesNotFallBackToReusableCredentialOrLastPreference() {
        val disk = Disk("{invalid", a)
        assertNull(offline(disk.authority()).capture())
    }

    @Test fun historyRecordingOffStaysOffAndLocalRestoreDoesNotConnectHistory() {
        val context = accountDataTestContext()
        val prefs = context.getSharedPreferences("listening-controls", 0)
        prefs.edit().putString("connected-account", "account-a").putBoolean("record:account-a", false).commit()
        val db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        try {
            val repository = ListeningRepository(context, db)
            val accounts = AccountLocalDataCoordinator(repository, Disk(kept = a).authority(), { null }, { false })
            assertNull(repository.session.value.access) // local restore did not manufacture HistoryAccess
            assertEquals("account-a", accounts.capture()?.account?.id)
            assertFalse(prefs.getBoolean("record:account-a", true))
            repository.restoreAccount() // the existing service restore path, unchanged
            assertFalse(repository.session.value.recording)
            assertNull(accounts.captureOnline())
        } finally { db.close() }
    }

    @Test fun lateOnlineVerificationCannotInvalidateANewerVerifiedAccount() = runTest {
        val disk = Disk(kept = a)
        val authority = disk.authority()
        var history: HistoryAccess? = HistoryAccess(HistoryAccount("account-a"), 1)
        var epoch: Long? = 1
        val oldLookup = CompletableDeferred<String>()
        var hold = true
        val accounts = AccountLocalDataCoordinator({ history }, { epoch }, {
            if (hold) oldLookup.await() else "account-b"
        }, authority, { true })
        val old = async(start = CoroutineStart.UNDISPATCHED) { accounts.verifyNativeAccount() }
        accounts.revoke(); disk.kept = b; history = HistoryAccess(HistoryAccount("account-b"), 2); epoch = 2; hold = false
        assertTrue(accounts.verifyNativeAccount())
        val current = accounts.captureOnline()!!
        oldLookup.complete("account-a")
        assertFalse(old.await())
        assertSame(current, accounts.captureOnline())
    }

    @Test fun suspendedRequestCannotPublishOrCacheIntoBAndLocalCannotDispatchOnlineWork() = runTest {
        val fixture = AccountLocalDataFixture()
        val first = fixture.connect()
        val cache = AccountRequestCache<String>(fixture.accounts)
        val answer = CompletableDeferred<String>()
        val request = async(start = CoroutineStart.UNDISPATCHED) { cache.answer(first, true) { answer.await() } }
        val second = fixture.connect("account-b")
        answer.complete("private-A")
        try { request.await(); fail("Stale request admitted") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertNull(cache.get(second, "home"))
        val local = offline(Disk(kept = a).authority())
        var called = false
        try { AccountRequestCache<String>(local).answer(local.capture()!!, true) { called = true; "provider" }; fail("Local promoted") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(called)
    }

    @Test fun credentialChangeInsideDiskPublicationCannotRestoreOldObserverLists() = runTest {
        val context = accountDataTestContext()
        val fixture = AccountLocalDataFixture()
        val access = fixture.connect()
        var replace = false
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), { file, text ->
            writeDownloadIndexAtomically(file, text)
            if (replace) fixture.kept = b // synthetic native-writer race; no account selected by caller
        }, fixture.accounts)
        store.load()
        replace = true
        try { store.setOwner(access, "A", listOf(song), null); fail("Old projection published") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertNull(fixture.accounts.capture())
        assertTrue(store.owners.value.isEmpty())
        assertTrue(store.ownerStates.value.isEmpty())
        fixture.connect("account-b")
        assertTrue(store.owners.value.isEmpty())
    }

    @Test fun parserAcceptsOnlySyntheticStoredCanonicalCredentialAndClearRevokesBeforeDelete() {
        val context = accountDataTestContext()
        val file = EngineCredentials.dir(context).resolve("reusable/credentials.json").apply { parentFile!!.mkdirs() }
        file.writeText("""{"username":"account-a","auth_type":1,"auth_data":"c3ludGhldGlj"}""")
        assertEquals("account-a", LocalSpotifyAuthority.readKeptIdentity(file)?.canonical)
        val authority = LocalSpotifyAuthority.forContext(context)
        val accounts = offline(authority)
        assertNotNull(accounts.capture())
        EngineCredentials.clear(context)
        assertNull(accounts.capture())
        assertFalse(file.exists())
        file.parentFile!!.mkdirs()
        file.writeText("""{"username":"Display Name","auth_type":3,"auth_data":"c3ludGhldGlj"}""")
        assertNull(LocalSpotifyAuthority.readKeptIdentity(file))
        assertNull(accounts.capture())
    }

    @Test fun firstOAuthLoginAndLoginAfterLogoutStartEmptyAndRequireNewCanonicalReceipt() = runTest {
        val fixture = AccountLocalDataFixture()
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        store.load()
        assertEquals("null", store.nativeBindingForBoot())
        assertNull(fixture.accounts.capture()) // OAuth identity itself is not a local credential receipt.
        fixture.history = HistoryAccess(HistoryAccount("account-a"), 1)
        fixture.epoch = 1; fixture.nativeId = "account-a"
        assertFalse(fixture.accounts.verifyNativeAccount())
        fixture.kept = a
        assertTrue(fixture.accounts.verifyNativeAccount())
        val first = fixture.accounts.capture()!!
        assertTrue(store.nativeBindingForBoot().contains("owner"))
        fixture.logout(); fixture.kept = null
        assertEquals("null", store.nativeBindingForBoot())
        fixture.history = HistoryAccess(HistoryAccount("account-b"), 2)
        fixture.epoch = 2; fixture.nativeId = "account-b"; fixture.nativeReady = true
        assertFalse(fixture.accounts.verifyNativeAccount())
        fixture.kept = b
        assertEquals("null", store.nativeBindingForBoot()) // File observer alone cannot mint authority.
        assertTrue(fixture.accounts.verifyNativeAccount())
        assertFalse(fixture.accounts.isCurrent(first))
        assertEquals("account-b", fixture.accounts.capture()?.account?.id)
        assertTrue(store.nativeBindingForBoot().contains("owner"))
    }

    private fun writeIndex(file: File, text: String) = writeDownloadIndexAtomically(file, text)
}
