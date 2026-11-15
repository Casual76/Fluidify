package dev.lelonio.square.wear

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lelonio.square.auth.SpotifyOAuth
import dev.lelonio.square.auth.TokenStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Requires the additive expectedGeneration TokenStore patch supplied to root. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class I11TokenGenerationFenceTest {
    @Test fun requestCapturedBeforeBootstrapCannotReadAFreshBToken() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = "i11-synthetic-${UUID.randomUUID()}"
        var refreshes = 0
        val store = TokenStore(context.getSharedPreferences(key, Context.MODE_PRIVATE), key,
            { "synthetic" }, { _, _ -> refreshes++; error("no network allowed") }, { 100L })
        store.save(SpotifyOAuth.Tokens("synthetic-a", "refresh-a", 1_000_000L))
        val a = store.generation
        // Replacement happens before validAccessToken is entered, after request/bootstrap admission.
        store.save(SpotifyOAuth.Tokens("synthetic-b", "refresh-b", 1_000_000L))
        try {
            store.validAccessToken(expectedGeneration = a)
            fail("an A request must not obtain B's already-fresh token")
        } catch (_: CancellationException) { }
        assertEquals(0, refreshes)
        assertEquals("synthetic-b", store.validAccessToken(expectedGeneration = store.generation))
    }
}
