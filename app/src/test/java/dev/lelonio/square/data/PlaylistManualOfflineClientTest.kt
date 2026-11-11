package dev.lelonio.square.data

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual separate write client with a recording application transport; never opens a socket. */
class PlaylistManualOfflineClientTest {
    @Before fun online() { SpotifyNetworkPolicy.setManual(false) }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }
    private fun request() = Request.Builder().url("https://synthetic.invalid/playlist").build()
    private fun response(request: Request) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(204).message("Synthetic receipt").body("".toResponseBody()).build()

    @Test fun manualOnBlocksBothClientOverloadsBeforeTransport() {
        val permit = SpotifyNetworkPolicy.capture()
        val calls = AtomicInteger()
        val client = playlistWriteClient("synthetic", permit) { }.newBuilder()
            .addInterceptor { chain -> calls.incrementAndGet(); response(chain.request()) }.build()
        try {
            SpotifyNetworkPolicy.setManual(true)
            assertThrows(SpotifyNetworkPolicy.Offline::class.java) { playlistWriteClient("synthetic") { } }
            assertThrows(SpotifyNetworkPolicy.Offline::class.java) { client.newCall(request()).execute().close() }
            assertEquals(0, calls.get())
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun rapidOnOffDoesNotRenewCapturedWriteClientAndFreshOperationWorks() {
        val calls = AtomicInteger()
        val old = playlistWriteClient("synthetic") { }.newBuilder()
            .addInterceptor { chain -> calls.incrementAndGet(); response(chain.request()) }.build()
        SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
        try {
            assertThrows(SpotifyNetworkPolicy.Offline::class.java) { old.newCall(request()).execute().close() }
            val fresh = playlistWriteClient("synthetic") { }.newBuilder()
                .addInterceptor { chain -> calls.incrementAndGet(); response(chain.request()) }.build()
            try {
                fresh.newCall(request()).execute().use { assertEquals(204, it.code) }
                assertEquals(1, calls.get())
                assertFalse(fresh.retryOnConnectionFailure); assertFalse(fresh.followRedirects)
                assertFalse(fresh.followSslRedirects)
            } finally { fresh.connectionPool.evictAll(); fresh.dispatcher.executorService.shutdownNow() }
        } finally { old.connectionPool.evictAll(); old.dispatcher.executorService.shutdownNow() }
    }

    @Test fun admittedWriteWhoseReceiptArrivesAfterRevocationIsRejectedWithoutReplay() {
        val calls = AtomicInteger()
        val client = playlistWriteClient("synthetic") { }.newBuilder().addInterceptor { chain ->
            calls.incrementAndGet()
            SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
            response(chain.request())
        }.build()
        try {
            assertThrows(SpotifyNetworkPolicy.Offline::class.java) { client.newCall(request()).execute().close() }
            assertEquals(1, calls.get())
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
}
