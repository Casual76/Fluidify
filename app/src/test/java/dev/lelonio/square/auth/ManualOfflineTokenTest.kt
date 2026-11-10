package dev.lelonio.square.auth

import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.data.accountDataTestContext
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ManualOfflineTokenTest {
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }

    @Test fun offlineRefreshPreservesCredentialsAndCanResumeWithoutLogout() = runTest {
        val prefs = accountDataTestContext().getSharedPreferences("tokens", 0)
        prefs.edit().putString("refresh_token", "synthetic-refresh").commit()
        var requests = 0
        val tokens = TokenStore(prefs, UUID.randomUUID().toString(), { "synthetic-client" },
            { _, _ -> requests++; SpotifyOAuth.Tokens("new", "rotated", 200_000) }, { 0L })
        SpotifyNetworkPolicy.setManual(true)
        try { tokens.validAccessToken(); fail("Offline refresh dispatched") }
        catch (_: SpotifyNetworkPolicy.Offline) { }
        assertEquals(0, requests)
        assertTrue(tokens.isLoggedIn)
        assertEquals("synthetic-refresh", prefs.getString("refresh_token", null))
        SpotifyNetworkPolicy.setManual(false)
        assertEquals("new", tokens.validAccessToken())
        assertEquals(1, requests)
        assertEquals("rotated", prefs.getString("refresh_token", null))
    }

    @Test fun revokedExpiredResponseDoesNotEraseTheAccountAndMutexWaiterCannotDispatch() = runTest {
        val prefs = accountDataTestContext().getSharedPreferences("tokens", 0)
        prefs.edit().putString("refresh_token", "synthetic-refresh").commit()
        var requests = 0
        val release = CompletableDeferred<Unit>()
        val tokens = TokenStore(prefs, UUID.randomUUID().toString(), { "synthetic-client" },
            { _, _ -> requests++; release.await(); throw SpotifyOAuth.SessionExpired("synthetic revoked grant") }, { 0L })
        SpotifyNetworkPolicy.setManual(false)
        // Catch inside each child so an expected IOException does not cancel the test parent.
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { tokens.validAccessToken() } }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { tokens.validAccessToken() } }
        SpotifyNetworkPolicy.setManual(true)
        SpotifyNetworkPolicy.setManual(false)
        release.complete(Unit)
        assertTrue(first.await().exceptionOrNull() is SpotifyNetworkPolicy.Offline)
        assertTrue(queued.await().exceptionOrNull() is SpotifyNetworkPolicy.Offline)
        assertEquals(1, requests)
        assertTrue(tokens.isLoggedIn)
        assertEquals("synthetic-refresh", prefs.getString("refresh_token", null))
    }

    @Test fun alreadyAdmittedRefreshKeepsRotationButDoesNotReleaseTokenToRevokedCaller() = runTest {
        val prefs = accountDataTestContext().getSharedPreferences("tokens", 0)
        prefs.edit().putString("refresh_token", "synthetic-refresh").commit()
        val release = CompletableDeferred<Unit>()
        val tokens = TokenStore(prefs, UUID.randomUUID().toString(), { "synthetic-client" },
            { _, _ -> release.await(); SpotifyOAuth.Tokens("new", "rotated", 200_000) }, { 0L })
        SpotifyNetworkPolicy.setManual(false)
        val work = async(start = CoroutineStart.UNDISPATCHED) { runCatching { tokens.validAccessToken() } }
        SpotifyNetworkPolicy.setManual(true)
        release.complete(Unit)
        assertTrue(work.await().exceptionOrNull() is SpotifyNetworkPolicy.Offline)
        assertEquals("rotated", prefs.getString("refresh_token", null))
        SpotifyNetworkPolicy.setManual(false)
        assertEquals("new", tokens.validAccessToken())
    }
}
