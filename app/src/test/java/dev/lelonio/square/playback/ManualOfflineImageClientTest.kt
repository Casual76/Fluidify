package dev.lelonio.square.playback

import dev.lelonio.square.data.SpotifyMediaClient
import dev.lelonio.square.data.SpotifyNetworkPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** PREPARED ONLY. The Coil client's real transport and policy, with synthetic loopback peers. */
class ManualOfflineImageClientTest {
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }

    @Test fun offlinePreflightOpensNothingAndFreshOnlineImagesStillLoad() {
        SyntheticMediaPeer(byteArrayOf(7, 8, 9)).use { peer ->
            val client = SpotifyMediaClient.create()
            try {
                val request = Request.Builder().url(peer.url).build()
                SpotifyNetworkPolicy.setManual(true)
                assertThrows(SpotifyNetworkPolicy.Offline::class.java) { client.newCall(request).execute().close() }
                assertEquals(0, peer.requests.get())
                SpotifyNetworkPolicy.setManual(false)
                client.newCall(request).execute().use { assertArrayEquals(byteArrayOf(7, 8, 9), it.body!!.bytes()) }
                assertEquals(1, peer.requests.get())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun confirmedOfflinePreventsAnImageRedirectAndRapidOffDoesNotRenewIt() = runBlocking {
        SyntheticMediaPeer(byteArrayOf(7), held = true, redirect = true).use { peer ->
            val client = SpotifyMediaClient.create()
            try {
                SpotifyNetworkPolicy.setManual(false)
                val work = async(Dispatchers.IO) { runCatching { client.newCall(Request.Builder().url(peer.url).build()).execute().use { it.body!!.bytes() } } }
                assertTrue(peer.received.await(5, TimeUnit.SECONDS))
                SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
                peer.release.countDown()
                assertTrue(work.await().isFailure)
                assertEquals(1, peer.requests.get())
                assertEquals(0, peer.afterOffline.get())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun explicitlyTaggedMediaOrCreditsOperationKeepsItsOriginalPermit() {
        SyntheticMediaPeer(byteArrayOf(3)).use { peer ->
            SpotifyNetworkPolicy.setManual(false)
            val old = SpotifyNetworkPolicy.capture()
            val request = Request.Builder().url(peer.url).tag(SpotifyNetworkPolicy.Permit::class.java, old).build()
            val client = SpotifyMediaClient.create()
            try {
                SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
                assertThrows(SpotifyNetworkPolicy.Offline::class.java) { client.newCall(request).execute().close() }
                assertEquals(0, peer.requests.get())
                val fresh = request.newBuilder().tag(SpotifyNetworkPolicy.Permit::class.java, SpotifyNetworkPolicy.capture()).build()
                client.newCall(fresh).execute().use { assertArrayEquals(byteArrayOf(3), it.body!!.bytes()) }
                assertEquals(1, peer.requests.get())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun freshOnlineImageRedirectRemainsSupported() {
        SyntheticMediaPeer(byteArrayOf(4, 5), redirect = true).use { peer ->
            SpotifyNetworkPolicy.setManual(false)
            val client = SpotifyMediaClient.create()
            try {
                client.newCall(Request.Builder().url(peer.url).build()).execute().use { assertArrayEquals(byteArrayOf(4, 5), it.body!!.bytes()) }
                assertEquals(2, peer.requests.get())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }
}
