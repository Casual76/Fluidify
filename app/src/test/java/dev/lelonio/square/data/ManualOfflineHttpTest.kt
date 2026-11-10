package dev.lelonio.square.data

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Uses the production application interceptor and a terminal synthetic transport. No sockets. */
class ManualOfflineHttpTest {
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }

    @Test fun blockedCallsNeverReachAuthOrTransportAndFreshOnlineCallsRecover() {
        var auth = 0
        var requests = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(SpotifyNetworkInterceptor())
            .addInterceptor { chain -> auth++; chain.proceed(chain.request()) }
            .addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("synthetic").body("[]".toResponseBody()).build()
            }.build()
        val request = Request.Builder().url("https://synthetic.invalid/catalog").build()
        SpotifyNetworkPolicy.setManual(true)
        try { client.newCall(request).execute().close(); fail("Manual offline reached HTTP") }
        catch (_: SpotifyNetworkPolicy.Offline) { }
        assertEquals(0, auth)
        assertEquals(0, requests)
        SpotifyNetworkPolicy.setManual(false)
        client.newCall(request).execute().use { assertEquals(200, it.code) }
        assertEquals(1, auth)
        assertEquals(1, requests)
    }

    @Test fun retryAfterOnOffCannotReuseTheRevokedAdmission() {
        var requests = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(SpotifyNetworkInterceptor())
            .addInterceptor { chain ->
                // Represents an auth fallback or a retry queued after an earlier response.
                SpotifyNetworkPolicy.setManual(true)
                SpotifyNetworkPolicy.setManual(false)
                chain.proceed(chain.request())
            }
            .addInterceptor(SpotifyNetworkInterceptor(networkAttempt = true))
            .addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("synthetic").body("[]".toResponseBody()).build()
            }.build()
        SpotifyNetworkPolicy.setManual(false)
        try {
            client.newCall(Request.Builder().url("https://synthetic.invalid/retry").build()).execute().close()
            fail("Revoked retry reached the transport")
        } catch (_: IOException) { }
        assertEquals(0, requests)
    }
}
