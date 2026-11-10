// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.bridge

import dev.lelonio.square.data.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** PREPARED, NOT EXECUTED. Contract counterparts of the archived bug-affirming probes. */
class ManualOfflineBridgeTransportTest {
    @Before fun online() { SpotifyNetworkPolicy.setManual(false) }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }
    private suspend fun error(block: suspend () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }; throw AssertionError("Expected failure")
    }
    @Test fun retainedLegacyClientDeniesRealRetrofitMeAfterManualOn() = runBlocking {
        BridgeLoopback { BridgeLoopback.Reply("""{"id":"synthetic-account"}""") }.use { peer ->
            BridgeTestApi(bridgeSpotifyApi("synthetic-token") {}, peer.url).use { client ->
                SpotifyNetworkPolicy.setManual(true)
                assertEquals("spotify_offline", bridgeFailureCode(error { client.api.me() }, null))
                assertTrue(peer.requests.isEmpty())
            }
        }
    }
    @Test fun retainedLegacyClientDeniesRealRetrofitPutAfterManualOn() = runBlocking {
        BridgeLoopback { BridgeLoopback.Reply() }.use { peer ->
            BridgeTestApi(bridgeSpotifyApi("synthetic-token") {}, peer.url).use { client ->
                SpotifyNetworkPolicy.setManual(true)
                assertEquals("spotify_offline", bridgeFailureCode(error { client.api.renamePlaylist("synthetic", PlaylistNameDto("new")) }, null))
                assertTrue(peer.requests.isEmpty())
            }
        }
    }
    @Test fun onlineMeAndMutationUsePinnedBearerWithoutSessionFallback() = runBlocking {
        val op = BridgeNetworkOperation.capture {}
        BridgeLoopback { r -> BridgeLoopback.Reply(if (r.line.startsWith("GET")) """{"id":"synthetic-account"}""" else "") }.use { peer ->
            BridgeTestApi(bridgeSpotifyApi("synthetic-token", op), peer.url).use { client ->
                assertEquals("synthetic-account", client.api.me().id)
                assertFalse(op.writeAdmitted)
                client.api.renamePlaylist("synthetic", PlaylistNameDto("new"))
                assertTrue(op.writeAdmitted)
                assertEquals(2, peer.requests.size)
                peer.requests.forEach { r ->
                    assertTrue(r.headers.any { it.equals("Authorization: Bearer synthetic-token", true) })
                    assertFalse(r.headers.any { it.startsWith(ApiFactory.SESSION_AUTH + ":", true) })
                }
            }
        }
    }
    @Test fun rapidOnOffDoesNotReviveRetainedClientButNewOperationWorks() = runBlocking {
        BridgeLoopback { BridgeLoopback.Reply("""{"id":"synthetic-account"}""") }.use { peer ->
            BridgeTestApi(bridgeSpotifyApi("synthetic-token") {}, peer.url).use { old ->
                SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
                error { old.api.me() }; assertTrue(peer.requests.isEmpty())
                BridgeTestApi(bridgeSpotifyApi("synthetic-token") {}, peer.url).use { fresh ->
                    assertEquals("synthetic-account", fresh.api.me().id)
                }
                assertEquals(1, peer.requests.size)
            }
        }
    }
    @Test fun revokedAccountOrTokenGenerationDeniesDispatchIndependentlyOfOnlinePolicy() = runBlocking {
        var generation = 1
        val op = BridgeNetworkOperation.capture { if (generation != 1) throw BridgeFailure("account_revoked") }
        BridgeLoopback { BridgeLoopback.Reply("""{"id":"synthetic-account"}""") }.use { peer ->
            BridgeTestApi(bridgeSpotifyApi("synthetic-token", op), peer.url).use { client ->
                generation = 2
                assertEquals("account_revoked", bridgeFailureCode(error { client.api.me() }, op))
                assertTrue(peer.requests.isEmpty())
            }
        }
    }
    @Test fun lateAdmittedReadCannotPublishOrStartAnotherPageAfterRevoke() = runBlocking {
        val received = CountDownLatch(1); val release = CountDownLatch(1)
        val op = BridgeNetworkOperation.capture {}
        BridgeLoopback { received.countDown(); check(release.await(5, TimeUnit.SECONDS)); BridgeLoopback.Reply("""{"items":[],"next":"synthetic"}""") }.use { peer ->
            BridgeTestApi(bridgeSpotifyApi("synthetic-token", op), peer.url).use { client ->
                try {
                    val call = async(Dispatchers.Default) { runCatching { client.api.playlists() } }
                    assertTrue(received.await(5, TimeUnit.SECONDS))
                    SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false); release.countDown()
                    assertTrue(call.await().isFailure)
                    assertEquals("spotify_offline", bridgeFailureCode(error { client.api.playlists(offset = 1) }, op))
                    assertFalse(op.writeAdmitted); assertEquals(1, peer.requests.size)
                } finally { release.countDown() }
            }
        }
    }
    @Test fun writeAlreadyAtPeerBecomesUncertainAndReadbackHasNoNewAdmission() = runBlocking {
        val received = CountDownLatch(1); val release = CountDownLatch(1)
        val op = BridgeNetworkOperation.capture {}
        BridgeLoopback { received.countDown(); check(release.await(5, TimeUnit.SECONDS)); BridgeLoopback.Reply() }.use { peer ->
            BridgeTestApi(bridgeSpotifyApi("synthetic-token", op), peer.url).use { client ->
                try {
                    val call = async(Dispatchers.Default) { runCatching { client.api.renamePlaylist("synthetic", PlaylistNameDto("new")) } }
                    assertTrue(received.await(5, TimeUnit.SECONDS))
                    SpotifyNetworkPolicy.setManual(true); release.countDown()
                    val e = call.await().exceptionOrNull() as? Exception ?: throw AssertionError("Expected revoked write")
                    assertTrue(op.writeAdmitted); assertEquals("write_uncertain", bridgeFailureCode(e, op))
                    val out = failure(bridgeFailureCode(e, op), "operation", op.writeUncertain)
                    assertEquals(dev.antigravity.fluidengine.ai.tools.ToolOutcome.UNCERTAIN, out.result!!.outcome)
                    assertFalse(out.result!!.error!!.retryable)
                    error { client.api.me() }; assertEquals(1, peer.requests.size)
                } finally { release.countDown() }
            }
        }
    }
    @Test fun actualMutationDoesNotReplay503RetryAfterZeroOrFollowRedirect() = runBlocking {
        for (status in listOf("503 Service Unavailable", "307 Temporary Redirect")) {
            val op = BridgeNetworkOperation.capture {}
            BridgeLoopback { BridgeLoopback.Reply(status = status, headers = "Retry-After: 0\r\nLocation: /replayed\r\n") }.use { peer ->
                BridgeTestApi(bridgeSpotifyApi("synthetic-token", op), peer.url).use { client ->
                    error { client.api.renamePlaylist("synthetic", PlaylistNameDto("new")) }
                    assertTrue(op.writeAdmitted); assertEquals(1, peer.requests.size)
                    assertTrue(peer.requests.single().line.startsWith("PUT /v1/playlists/synthetic"))
                }
            }
        }
    }
}
