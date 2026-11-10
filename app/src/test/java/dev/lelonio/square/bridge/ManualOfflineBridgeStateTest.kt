// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.bridge

import dev.antigravity.fluidengine.ai.tools.*
import dev.lelonio.square.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/** PREPARED, NOT EXECUTED. Real FIFO, cursor, fragment and publication implementations. */
class ManualOfflineBridgeStateTest {
    @Before fun online() { SpotifyNetworkPolicy.setManual(false) }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }
    private val access = HistoryAccess(HistoryAccount("synthetic-account"), 1)
    private val rows = (1..3).map { buildJsonObject { put("id", it) } }
    private class Store : OperationStore {
        val rows = mutableMapOf<String, JsonObject>()
        override fun get(key: String) = rows[key]
        override fun put(key: String, value: JsonObject) { rows[key] = value }
    }
    @Test fun tokenAcquisitionReceivesExactOriginalPermitAndCannotRenewAfterWait() = runBlocking {
        val op = BridgeNetworkOperation.capture {}
        var tokenReads = 0
        assertEquals("token", op.token { assertSame(op.permit, it); tokenReads++; "token" })
        SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
        assertTrue(runCatching { op.token { tokenReads++; "renewed" } }.exceptionOrNull() is SpotifyNetworkPolicy.Offline)
        assertEquals(1, tokenReads)
    }
    @Test fun lateTokenCannotEscapeItsOriginalPermit() = runBlocking {
        val op = BridgeNetworkOperation.capture {}
        val e = runCatching { op.token {
            SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false); "late-token"
        } }.exceptionOrNull()
        assertTrue(e is SpotifyNetworkPolicy.Offline)
        assertFalse(op.writeUncertain)
    }
    @Test fun revokedFifoWaiterCannotClaimDispatchOrReadback() = runBlocking {
        val store = Store(); val ledger = PlaylistOperations(store)
        val release = CompletableDeferred<Unit>()
        val first = BridgeNetworkOperation.capture {}; val second = BridgeNetworkOperation.capture {}
        var secondCalls = 0
        val running = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
            ledger.run("first", "p", true, null, first, {
                first.admit("POST"); release.await(); "id"
            }, { true })
        } }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
            ledger.run("second", "p", true, null, second, { secondCalls++; "other" }, { secondCalls++; true })
        } }
        SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false); release.complete(Unit)
        assertTrue(running.await().isFailure); assertTrue(queued.await().isFailure)
        assertEquals(0, secondCalls); assertFalse(store.rows.containsKey("second"))
        assertEquals("id", store.rows.getValue("first")["id"]!!.jsonPrimitive.content)
    }
    @Test fun admittedWriteReceiptSurvivesOfflineButCannotReadBackOrPublish() = runBlocking {
        val store = Store(); val op = BridgeNetworkOperation.capture {}; var reads = 0
        val e = runCatching { PlaylistOperations(store).run("key", "p", true, null, op, {
            op.admit("POST"); SpotifyNetworkPolicy.setManual(true); "id"
        }, { reads++; true }) }.exceptionOrNull() as Exception
        assertEquals("id", store.rows.getValue("key")["id"]!!.jsonPrimitive.content)
        assertEquals(0, reads); assertEquals("write_uncertain", bridgeFailureCode(e, op))
    }
    @Test fun accountReplacementRejectsLateReceiptAndPublication() = runBlocking {
        val store = Store(); var sameAccount = true
        val op = BridgeNetworkOperation.capture { if (!sameAccount) throw BridgeFailure("account_revoked") }
        val e = runCatching { PlaylistOperations(store).run("key", "p", true, null, op, {
            op.admit("POST"); sameAccount = false; "foreign-late-id"
        }, { true }) }.exceptionOrNull() as Exception
        assertNull(store.rows.getValue("key")["id"])
        assertEquals("account_revoked", bridgeFailureCode(e, op)); assertTrue(op.writeUncertain)
    }
    @Test fun reconciliationAfterRecreationUsesReadbackWithoutReplayAndRemainsUncertainIfRevoked() = runBlocking {
        val store = Store(); var mutations = 0
        val first = BridgeNetworkOperation.capture {}
        assertTrue(runCatching { PlaylistOperations(store).run("key", "p", false, "id", first,
            { first.admit("PUT"); mutations++; throw java.io.IOException("lost response") }, { true }) }.isFailure)
        val second = BridgeNetworkOperation.capture {}
        val e = runCatching { PlaylistOperations(store).run("key", "p", false, "id", second,
            { mutations++; "id" }, { SpotifyNetworkPolicy.setManual(true); true }) }.exceptionOrNull() as Exception
        assertEquals(1, mutations); assertTrue(second.writeUncertain)
        assertEquals("write_uncertain", bridgeFailureCode(e, second))
        SpotifyNetworkPolicy.setManual(false)
        assertEquals("id", PlaylistOperations(store).run("key", "p", false, "id", BridgeNetworkOperation.capture {},
            { mutations++; "id" }, { true }))
        assertEquals(1, mutations)
    }
    @Test fun preconditionFailureBeforeMutationIsDefiniteNotUncertain() = runBlocking {
        val op = BridgeNetworkOperation.capture {}
        val e = runCatching { PlaylistOperations(Store()).run("key", "p", false, "id", op,
            { throw BridgeFailure("precondition_failed") }, { true }) }.exceptionOrNull() as Exception
        assertEquals("precondition_failed", bridgeFailureCode(e, op)); assertFalse(op.writeUncertain)
    }
    @Test fun spotifyCursorRetainsOriginatingPermitWhileLocalHistoryCursorSurvivesToggle() {
        val pager = SnapshotPager()
        val remote = pager.page(access, "spotify", null, 1, rows, SpotifyNetworkPolicy.capture())
        val local = pager.page(access, "history", null, 1, rows)
        SpotifyNetworkPolicy.setManual(true)
        assertThrows(SpotifyNetworkPolicy.Offline::class.java) { pager.page(access, "spotify", remote.cursor, 1) }
        assertEquals(rows[1], pager.page(access, "history", local.cursor, 1).rows.single())
        SpotifyNetworkPolicy.setManual(false)
        assertThrows(SpotifyNetworkPolicy.Offline::class.java) {
            pager.page(access, "spotify", remote.cursor, 1, network = SpotifyNetworkPolicy.capture())
        }
    }
    @Test fun spotifyFragmentsCannotRecaptureButLocalHistoryFragmentsRemainUsefulOffline() {
        val fragments = ResultFragments()
        val data = buildJsonObject { put("value", "synthetic".repeat(1000)) }
        fun save(query: String, network: SpotifyNetworkPolicy.Permit?) = fragments.output(access, "caller", query, data,
            ToolResultDetails(ToolOutcome.COMPLETED), 1L, network).result!!.data!!
        val remote = save("spotify", SpotifyNetworkPolicy.capture()); val local = save("history", null)
        fun read(d: JsonObject) = fragments.read(access, "caller", d["snapshot"]!!.jsonPrimitive.content,
            d["hash"]!!.jsonPrimitive.content, d["queryHash"]!!.jsonPrimitive.content, d["nextOffset"]!!.jsonPrimitive.int, 1L)
        SpotifyNetworkPolicy.setManual(true)
        assertThrows(SpotifyNetworkPolicy.Offline::class.java) { read(remote) }
        assertEquals(ToolOutcome.PARTIAL, read(local).result!!.outcome)
        SpotifyNetworkPolicy.setManual(false)
        assertThrows(SpotifyNetworkPolicy.Offline::class.java) { read(remote) }
    }
    @Test fun spotifyCursorCannotCrossTokenGenerationWithSameHistoryAccount() {
        val pager = SnapshotPager()
        val first = pager.page(access, "spotify", null, 1, rows, SpotifyNetworkPolicy.capture(), 1L)
        val error = assertThrows(BridgeFailure::class.java) {
            pager.page(access, "spotify", first.cursor, 1, network = SpotifyNetworkPolicy.capture(), sourceGeneration = 2L)
        }
        assertEquals("account_revoked", error.code)
    }
    @Test fun lateProofCompositionCannotPublishSpotifySuccess() = runBlocking {
        val op = BridgeNetworkOperation.capture {}
        val e = runCatching { bridgeSourcePublication(op) {
            SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
            ToolOutput("late source proof", result = ToolResultDetails(ToolOutcome.COMPLETED))
        } }.exceptionOrNull()
        assertTrue(e is SpotifyNetworkPolicy.Offline)
    }
    @Test fun proofCompositionKeepsFailureEvidenceDeliverableAfterRevocation() = runBlocking {
        val op = BridgeNetworkOperation.capture {}
        val output = failure("spotify_offline")
        assertSame(output, bridgeSourcePublication(op) { SpotifyNetworkPolicy.setManual(true); output })
    }
}
