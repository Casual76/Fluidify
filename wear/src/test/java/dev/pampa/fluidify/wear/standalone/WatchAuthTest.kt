package dev.pampa.fluidify.wear.standalone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.protocol.AccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The watch signs out with the phone, and only then. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class WatchAuthTest {

    private lateinit var app: WearApp
    private lateinit var auth: WatchAuth
    private lateinit var prefs: StandalonePrefs

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        prefs = StandalonePrefs(app)
        auth = WatchAuth(app, app.link, prefs)
        keepCredential()
        prefs.username = "alice"
    }

    private fun keepCredential() {
        val file = auth.credentialsDir.resolve("reusable").resolve("credentials.json")
        file.parentFile!!.mkdirs()
        file.writeText("{}")
    }

    @Test
    fun aKeptCredentialMeansSignedIn() {
        assertTrue(auth.hasCredential)
        assertEquals(AuthState.SIGNED_IN, WatchAuth(app, app.link, prefs).state.value)
    }

    @Test
    fun thePhoneSigningOutDropsTheCredential() {
        auth.onAccount(AccountState(signedIn = false))
        assertFalse(auth.hasCredential)
        assertEquals(AuthState.SIGNED_OUT, auth.state.value)
        assertEquals(null, prefs.username)
    }

    @Test
    fun anotherAccountOnThePhoneDropsTheCredential() {
        auth.onAccount(AccountState(signedIn = true, username = "bob"))
        assertFalse(auth.hasCredential)
    }

    @Test
    fun theSameAccountKeepsIt() {
        auth.onAccount(AccountState(signedIn = true, username = "alice"))
        assertTrue(auth.hasCredential)
        assertEquals(AuthState.SIGNED_IN, auth.state.value)
    }

    @Test
    fun theDeviceIdNeverChanges() {
        val first = prefs.deviceId
        assertEquals(first, StandalonePrefs(app).deviceId)
        assertEquals(32, first.length)
    }
}
