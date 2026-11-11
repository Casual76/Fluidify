package dev.lelonio.square.playback

import android.app.Application
import dev.lelonio.square.auth.LocalSpotifyAuthority
import dev.lelonio.square.auth.AccountLifecycle
import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.DownloadStore
import dev.lelonio.square.data.accountDataTestContext
import dev.lelonio.square.data.writeDownloadIndexAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeDownloadSourceOwnerTest {
    private fun field(binding: String, key: String) =
        Json.parseToJsonElement(binding).jsonObject.getValue(key).jsonPrimitive.content

    @Test fun credentialRenewalCannotChangeTheRunningNativeSourceOwner() = runTest {
        val account = AccountLocalDataFixture()
        val first = account.connect()
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler),
            ::writeDownloadIndexAtomically, account.accounts)
        store.load()
        val owner = NativeDownloadSourceOwner(store)
        val boot = owner.binding()

        // The reusable credential is replaced during online authentication. The old
        // receipt is revoked immediately; only live canonical verification admits the new one.
        account.kept = LocalSpotifyAuthority.KeptIdentity("account-a", "synthetic-refreshed")
        assertNull(account.accounts.capture())
        assertEquals("null", owner.binding())
        assertTrue(account.accounts.verifyNativeAccount())
        val renewed = account.accounts.capture()!!
        assertNotEquals(first.binding.lineage, renewed.binding.lineage)
        val online = owner.binding()
        // These are the exact immutable fields compared by native bind_download_source.
        for (key in listOf("owner", "namespace", "root")) {
            assertEquals("native source identity changed at credential renewal: $key",
                field(boot, key), field(online, key))
        }
        try { store.nativeBindingJson(first, sourceOwner = field(boot, "owner")); fail("Revoked account access was accepted") }
        catch (_: CancellationException) { }
    }

    @Test fun aNewNativePlayerGetsANewSourceOwner() = runTest {
        val account = AccountLocalDataFixture().also { it.connect() }
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler),
            ::writeDownloadIndexAtomically, account.accounts)
        store.load()
        val first = NativeDownloadSourceOwner(store).binding()
        val second = NativeDownloadSourceOwner(store).binding()
        assertNotEquals(field(first, "owner"), field(second, "owner"))
        assertEquals(field(first, "namespace"), field(second, "namespace"))
    }

    @Test fun logoutClearsTheSourceWithoutGrantingAuthorityFromKeptCredentials() = runTest {
        val account = AccountLocalDataFixture().also { it.connect() }
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler),
            ::writeDownloadIndexAtomically, account.accounts)
        store.load()
        val owner = NativeDownloadSourceOwner(store)
        assertNotEquals("null", owner.binding())
        account.accounts.revoke()
        assertNotNull(account.kept)
        assertEquals("null", owner.binding())
    }

    @Test fun anotherAccountStillHasADifferentNativeNamespace() = runTest {
        val account = AccountLocalDataFixture()
        account.connect("account-a")
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler),
            ::writeDownloadIndexAtomically, account.accounts)
        store.load()
        val owner = NativeDownloadSourceOwner(store)
        val first = owner.binding()
        account.connect("account-b")
        val second = owner.binding()
        assertNotEquals(field(first, "namespace"), field(second, "namespace"))
        // The native guard still rejects this namespace change for the same player.
        assertEquals(field(first, "owner"), field(second, "owner"))
    }

    @Test fun aFreshOwnerWithoutCanonicalAuthorityPublishesNoSource() = runTest {
        val account = AccountLocalDataFixture()
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler),
            ::writeDownloadIndexAtomically, account.accounts)
        store.load()
        assertEquals("null", NativeDownloadSourceOwner(store).binding())
    }

    @Test fun publicationWaitingForNativeStartupRecapturesTheRenewedAuthority() = runTest {
        val account = AccountLocalDataFixture()
        val first = account.connect()
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler),
            ::writeDownloadIndexAtomically, account.accounts)
        store.load()
        val owner = NativeDownloadSourceOwner(store)
        val boot = owner.binding()
        val lifecycle = AccountLifecycle()
        val releaseStartup = CompletableDeferred<Unit>()
        val startup = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.exclusive { releaseStartup.await() }
        }
        var published: String? = null
        val notification = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.runCurrent(lifecycle.generation) { published = owner.binding() }
        }
        account.kept = LocalSpotifyAuthority.KeptIdentity("account-a", "synthetic-refresh-while-starting")
        assertNull(account.accounts.capture())
        assertTrue(account.accounts.verifyNativeAccount())
        assertFalse(account.accounts.isCurrent(first))
        releaseStartup.complete(Unit)
        startup.join(); notification.join()
        assertNotNull(published)
        assertNotEquals("null", published)
        assertEquals(field(boot, "owner"), field(published!!, "owner"))
        assertEquals(field(boot, "namespace"), field(published!!, "namespace"))
    }
}
