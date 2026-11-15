package dev.lelonio.square.data

import dev.lelonio.square.auth.TokenStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RootAccountWriteApiTest {
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }
    @Test fun staleOriginalAccountOrNetworkCannotMintFromTheNewSession() = runTest {
        SpotifyNetworkPolicy.setManual(false)
        val fixture = AccountLocalDataFixture()
        val access = fixture.connect("a")
        val permit = SpotifyNetworkPolicy.capture()
        val prefs = accountDataTestContext().getSharedPreferences("write-test", 0)
        val tokens = TokenStore(prefs, UUID.randomUUID().toString(), { "synthetic" },
            { _, _ -> error("no refresh allowed") }, { 0 })
        var mints = 0
        fixture.connect("b")
        try {
            accountWriteApi(fixture.accounts, access, tokens, "user-follow-modify", permit,
                nativeToken = { mints++; "synthetic-a" }, requireNativeScope = false)
            fail("old account must be refused")
        } catch (_: CancellationException) { }
        assertEquals(0, mints)
        val current = fixture.accounts.captureOnline()!!
        SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
        try {
            accountWriteApi(fixture.accounts, current, tokens, "user-follow-modify", permit,
                nativeToken = { mints++; "synthetic-b" }, requireNativeScope = false)
            fail("old network permit must be refused")
        } catch (_: SpotifyNetworkPolicy.Offline) { }
        assertEquals(0, mints)
    }
    @Test fun alreadyPreparedWriteFromAIsRefusedBeforeDnsAfterAccountB() = runTest {
        SpotifyNetworkPolicy.setManual(false)
        val fixture = AccountLocalDataFixture()
        val access = fixture.connect("a")
        var resolutions = 0
        val client = playlistWriteClient("synthetic-fixed-a", SpotifyNetworkPolicy.capture()) {
            fixture.accounts.requireOnline(access)
        }.newBuilder().dns(object : Dns { override fun lookup(hostname: String): List<java.net.InetAddress> { resolutions++; error("DNS must never be reached") } }).build()
        fixture.connect("b")
        val request = Request.Builder().url("https://api.spotify.com/v1/me/tracks").build()
        try { client.newCall(request).execute().close(); fail("queued write must fail") }
        catch (_: IOException) { }
        assertEquals(0, resolutions)
    }
}
