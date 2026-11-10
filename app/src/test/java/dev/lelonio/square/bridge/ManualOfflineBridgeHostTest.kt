// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.bridge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.bridge.RemoteCall
import dev.antigravity.fluidengine.ai.tools.*
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.auth.SpotifyOAuth
import dev.lelonio.square.auth.TokenStore
import dev.lelonio.square.data.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PREPARED, NOT EXECUTED. Real MusicTool/registry, TokenStore, Room and production HTTP client.
 * Only dependency construction and the HTTP endpoint are replaced; no Application.onCreate/JNI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ManualOfflineBridgeHostTest {
    @Before fun online() { SpotifyNetworkPolicy.setManual(false) }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }
    private val account = "syntheticAccount"
    private fun playlist(title: String = "old") = """{"id":"synthetic","uri":"spotify:playlist:synthetic","name":"$title","owner":{"id":"$account"},"public":false,"tracks":{"total":1}}"""
    private fun args(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf("account" to JsonPrimitive(account)) + values)

    private inner class Host(private val peer: BridgeLoopback? = null, fresh: Boolean = true,
        networkOperation: BridgeNetworkOperation? = null) : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        val history = ListeningRepository(context, db).also { it.connect(HistoryAccount(account)) }
        val prefs = context.getSharedPreferences("bridge-synthetic-${UUID.randomUUID()}", 0)
        var refreshes = 0
        var refresh: suspend (SpotifyNetworkPolicy.Permit) -> SpotifyOAuth.Tokens = {
            error("Unexpected OAuth refresh: synthetic transports only")
        }
        val tokens = TokenStore(prefs, UUID.randomUUID().toString(), { "synthetic-client" },
            { _, _ -> error("Must pass the original admitted permit") }, { 0L },
            refreshAdmitted = { _, _, permit -> refreshes++; refresh(permit) }).also {
            it.save(SpotifyOAuth.Tokens("synthetic-token", "synthetic-refresh", if (fresh) 3_600_000L else 0L))
        }
        val store = object : OperationStore {
            val data = mutableMapOf<String, JsonObject>()
            override fun get(key: String) = data[key]
            override fun put(key: String, value: JsonObject) { data[key] = value }
        }
        val registry = fluidifyRegistry()
        val observed = mutableListOf<BridgeNetworkOperation>()
        private val clients = mutableListOf<BridgeTestApi>()
        var beforeApi: (BridgeNetworkOperation) -> Unit = {}
        val ctx = FluidifyBridgeContext(SquareApplication(), RemoteCall("synthetic.caller", "it", true, "synthetic-request"),
            SnapshotPager(), PlaylistOperations(store), networkOperation = networkOperation, spotifyApi = { token, operation ->
                observed += operation; beforeApi(operation)
                BridgeTestApi(bridgeSpotifyApi(token, operation), checkNotNull(peer) { "No transport allowed in this case" }.url)
                    .also { clients += it }.api
            }, injectedTokens = tokens, injectedHistory = history)
        suspend fun run(name: String, input: JsonObject) = registry.find(name)!!.run(input, ctx)
        override fun close() { clients.forEach { it.close() }; db.close() }
    }
    private fun assertError(output: ToolOutput, code: String, uncertain: Boolean = false) {
        assertEquals(if (uncertain) ToolOutcome.UNCERTAIN else ToolOutcome.ERROR, output.result!!.outcome)
        assertEquals(code, output.result!!.error!!.code)
        assertFalse(output.result!!.error!!.retryable)
    }

    @Test fun manualCallerStopsBeforeExpiredTokenRefreshAndApiConstructionWithoutLosingAccount() = runBlocking {
        Host(fresh = false).use { host ->
            val access = host.history.session.value.access
            SpotifyNetworkPolicy.setManual(true)
            assertError(host.run("playlists", args()), "spotify_offline")
            assertEquals(0, host.refreshes); assertTrue(host.observed.isEmpty())
            assertEquals(access, host.history.session.value.access)
            assertEquals("synthetic-refresh", host.prefs.getString("refresh_token", null))
        }
    }
    @Test fun allLocalHistoryFamiliesAndCapabilitiesWorkOfflineWithoutTokenOrApi() = runBlocking {
        Host(fresh = false).use { host ->
            val access = host.history.session.value.access!!
            host.history.importPage(access, listOf(CatalogTrack("spotify:track:synthetic", "Synthetic", "Artist") to 1_000L))
            SpotifyNetworkPolicy.setManual(true)
            for (name in listOf("history_records", "history_aggregate", "history_track_counts", "history_artist_counts", "history_coverage")) {
                val input = if (name == "history_coverage") args() else args("from" to JsonPrimitive("0"), "until" to JsonPrimitive("10000"))
                val output = host.run(name, input)
                assertNotEquals(ToolOutcome.ERROR, output.result!!.outcome)
                assertNotEquals(ToolOutcome.UNCERTAIN, output.result!!.outcome)
                assertNotNull(output.result!!.data)
                if (name == "history_aggregate") assertEquals(1L, output.result!!.data!!["countedPlays"]!!.jsonPrimitive.long)
                if (name == "history_records") assertEquals("spotify:track:synthetic",
                    output.result!!.data!!["items"]!!.jsonArray.single().jsonObject["track"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
            }
            assertEquals(ToolOutcome.COMPLETED, host.run("capabilities", buildJsonObject {}).result!!.outcome)
            assertEquals(0, host.refreshes); assertTrue(host.observed.isEmpty())
        }
    }
    @Test fun callerPreservesReceivedRotatedTokenButCannotContinueAfterRapidRevoke() = runBlocking {
        val operation = BridgeNetworkOperation.capture {}
        Host(fresh = false, networkOperation = operation).use { host ->
            host.refresh = { original ->
                assertSame(operation.permit, original)
                original.check()
                SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
                SpotifyOAuth.Tokens("synthetic-rotated-access", "synthetic-rotated-refresh", 3_600_000L)
            }
            assertError(host.run("playlists", args()), "spotify_offline")
            assertEquals(1, host.refreshes); assertTrue(host.observed.isEmpty())
            assertEquals("synthetic-rotated-refresh", host.prefs.getString("refresh_token", null))
            assertEquals("synthetic-rotated-access", host.tokens.validAccessToken())
        }
    }
    @Test fun realCallerCollectsMultiplePagesWithOneAuthorityAndPinnedCredential() = runBlocking {
        BridgeLoopback { r -> BridgeLoopback.Reply(when {
            r.line.startsWith("GET /v1/me ") -> """{"id":"$account"}"""
            r.line.contains("offset=0") -> """{"items":[${playlist()}],"total":2,"next":"synthetic-next"}"""
            else -> """{"items":[${playlist("second")}],"total":2}"""
        }) }.use { peer -> Host(peer).use { host ->
            val output = host.run("playlists", args())
            assertEquals(ToolOutcome.COMPLETED, output.result!!.outcome)
            assertEquals(2, output.result!!.data!!["items"]!!.jsonArray.size)
            assertEquals(3, peer.requests.size); assertEquals(1, host.observed.size)
            assertFalse(host.observed.single().writeAdmitted)
        } }
    }
    @Test fun revokeAtFirstSourcePagePreventsNextPageAndSuccessPublication() = runBlocking {
        BridgeLoopback { r ->
            if (r.line.startsWith("GET /v1/me ")) BridgeLoopback.Reply("""{"id":"$account"}""")
            else {
                SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
                BridgeLoopback.Reply("""{"items":[${playlist()}],"next":"synthetic-next"}""")
            }
        }.use { peer -> Host(peer).use { host ->
            assertError(host.run("playlists", args()), "spotify_offline")
            assertEquals(2, peer.requests.size)
            assertEquals(1, peer.requests.count { it.line.startsWith("GET /v1/me/playlists") })
            assertTrue(peer.requests.none { it.manual })
        } }
    }
    @Test fun staleCursorCannotAcquireFreshAuthorityForMeDirectlyOrThroughSourceProof() = runBlocking {
        BridgeLoopback { r -> BridgeLoopback.Reply(if (r.line.startsWith("GET /v1/me "))
            """{"id":"$account"}""" else """{"items":[${playlist()},${playlist("second")}] }""") }.use { peer ->
            Host(peer).use { host ->
                val first = host.run("playlists", args("limit" to JsonPrimitive(1)))
                val cursor = checkNotNull(first.result!!.pagination!!.nextCursor)
                val count = peer.requests.size
                val input = args("limit" to JsonPrimitive(1), "cursor" to JsonPrimitive(cursor))
                SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
                assertError(host.run("playlists", input), "spotify_offline")
                assertError(sourceRead(host, "playlists", input), "spotify_offline")
                assertEquals(count, peer.requests.size); assertEquals(0, host.refreshes)
            }
        }
    }
    @Test fun appendPreconditionFailureBeforeActualMutationRemainsDefinite() = runBlocking {
        BridgeLoopback { r -> BridgeLoopback.Reply(when {
            r.line.startsWith("GET /v1/me ") -> """{"id":"$account"}"""
            r.line.contains("fields=snapshot_id") -> """{"snapshot_id":"changed","tracks":{"total":1}}"""
            else -> playlist()
        }) }.use { peer -> Host(peer).use { host ->
            val output = host.run("playlist_append", args("playlistId" to JsonPrimitive("synthetic"),
                "expectedSnapshotId" to JsonPrimitive("expected"), "expectedTrackCount" to JsonPrimitive(1),
                "uris" to JsonArray(listOf(JsonPrimitive("spotify:track:synthetic")))))
            assertError(output, "precondition_failed")
            assertTrue(peer.requests.all { it.line.startsWith("GET ") })
            assertFalse(host.observed.single().writeUncertain)
        } }
    }
    @Test fun admittedRenameIsUncertainAfterRevokeAndExplicitReconciliationNeverReplaysIt() = runBlocking {
        BridgeLoopback { r ->
            if (r.line.startsWith("PUT ")) { SpotifyNetworkPolicy.setManual(true); BridgeLoopback.Reply() }
            else BridgeLoopback.Reply(if (r.line.startsWith("GET /v1/me ")) """{"id":"$account"}""" else playlist())
        }.use { peer -> Host(peer).use { host ->
            val input = args("playlistId" to JsonPrimitive("synthetic"), "name" to JsonPrimitive("new"))
            assertError(host.run("playlist_rename", input), "write_uncertain", true)
            val countAtRevoke = peer.requests.size
            assertEquals(1, peer.requests.count { it.line.startsWith("PUT ") })
            assertError(host.run("playlist_rename", input), "spotify_offline", true)
            assertEquals(countAtRevoke, peer.requests.size)
            SpotifyNetworkPolicy.setManual(false)
            assertError(host.run("playlist_rename", input), "readback_uncertain", true)
            assertEquals(1, peer.requests.count { it.line.startsWith("PUT ") })
        } }
    }
    @Test fun accountAndTokenGenerationFencesStillBlockRealCallerBeforeTransport() = runBlocking {
        for (replaceTokens in listOf(false, true)) BridgeLoopback { error("No HTTP permitted") }.use { peer ->
            Host(peer).use { host ->
                host.beforeApi = { if (replaceTokens) host.tokens.clear() else host.history.disconnect() }
                assertError(host.run("playlists", args()), "account_revoked")
                assertTrue(peer.requests.isEmpty())
            }
        }
    }
    private suspend fun sourceRead(host: Host, tool: String, input: JsonObject): ToolOutput {
        val cap = host.run("source_capabilities", buildJsonObject {}).result!!.data!!["capability"]!!
        val leaseArgs = buildJsonObject { put("version", 1); put("capability", cap); put("tool", tool); put("arguments", input) }
        val lease = host.run("source_lease", leaseArgs).result!!.data!!["lease"]!!
        return host.run("source_read", JsonObject(leaseArgs + ("lease" to lease)))
    }
    @Test fun actualSourceProofRegistryKeepsLocalHistoryUsefulButDeniesSpotifyWhileManual() = runBlocking {
        Host(fresh = false).use { host ->
            SpotifyNetworkPolicy.setManual(true)
            val local = sourceRead(host, "history_coverage", args())
            assertEquals(ToolOutcome.COMPLETED, local.result!!.outcome)
            assertEquals(1, local.result!!.data!!["sourceProof"]!!.jsonObject["version"]!!.jsonPrimitive.int)
            assertError(sourceRead(host, "playlists", args()), "spotify_offline")
            assertEquals(0, host.refreshes); assertTrue(host.observed.isEmpty())
        }
    }
    @Test fun sourceProofReadUsesSameOperationAsProductionClientAndPreservesTypedV1Evidence() = runBlocking {
        BridgeLoopback { r -> BridgeLoopback.Reply(if (r.line.startsWith("GET /v1/me "))
            """{"id":"$account"}""" else """{"items":[]}""") }.use { peer -> Host(peer).use { host ->
            val output = sourceRead(host, "playlists", args())
            assertEquals(ToolOutcome.COMPLETED, output.result!!.outcome)
            val proof = output.result!!.data!!["sourceProof"]!!.jsonObject
            assertEquals("aria.source-proof", proof["contract"]!!.jsonPrimitive.content)
            assertEquals(1, proof["version"]!!.jsonPrimitive.int)
            assertEquals(account, proof["account"]!!.jsonPrimitive.content)
            assertEquals(1, host.observed.size); assertEquals(2, peer.requests.size)
        } }
    }
    @Test fun sourceProofWrapperCannotRenewAnInheritedRevokedOperation() = runBlocking {
        val original = BridgeNetworkOperation.capture {}
        Host(networkOperation = original).use { host ->
            SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
            assertError(sourceRead(host, "playlists", args()), "spotify_offline")
            assertTrue(host.observed.isEmpty()); assertEquals(0, host.refreshes)
        }
    }
    @Test fun registeredProofDecoratorFencesCompositionAfterUnderlyingReadReturns() = runBlocking {
        Host().use { host ->
            assertTrue(host.registry.find("source_read") is BridgeSourceRead)
            var authority: BridgeNetworkOperation? = null
            val original = object : AiTool<FluidifyBridgeContext> {
                override val name = "source_read"
                override val group = MusicGroup
                override val description = "Synthetic proof-composition boundary"
                override val parameters = Schema.obj(emptyMap())
                override suspend fun run(args: JsonObject, ctx: FluidifyBridgeContext): ToolOutput {
                    authority = ctx.networkOperation
                    SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
                    return ToolOutput("late", result = ToolResultDetails(ToolOutcome.COMPLETED))
                }
            }
            val output = BridgeSourceRead(original).run(buildJsonObject {
                put("tool", "playlists"); put("arguments", args())
            }, host.ctx)
            assertNotNull(authority); assertError(output, "spotify_offline")
            assertTrue(host.observed.isEmpty())
        }
    }
    @Test fun realFragmentToolRetainsSpotifyOriginLeaseAndLocalHistoryFragmentsRemainReadable() = runBlocking {
        BridgeLoopback { r -> BridgeLoopback.Reply(when {
            r.line.startsWith("GET /v1/me ") -> """{"id":"$account"}"""
            r.line.contains("fields=snapshot_id") -> """{"snapshot_id":"s","tracks":{"total":1}}"""
            else -> playlist("x".repeat(3000))
        }) }.use { peer -> Host(peer).use { host ->
            val remote = host.run("playlist_details", args("playlistId" to JsonPrimitive("synthetic"))).result!!.data!!
            host.history.importPage(host.history.session.value.access!!,
                listOf(CatalogTrack("spotify:track:synthetic", "x".repeat(3000), "Artist") to 1_000L))
            val local = host.run("history_records", args("from" to JsonPrimitive("0"), "until" to JsonPrimitive("10000"))).result!!.data!!
            fun fragment(d: JsonObject) = args("snapshot" to d["snapshot"]!!, "hash" to d["hash"]!!,
                "queryHash" to d["queryHash"]!!, "offset" to d["nextOffset"]!!)
            SpotifyNetworkPolicy.setManual(true)
            assertError(host.run("result_fragment", fragment(remote)), "spotify_offline")
            assertEquals(ToolOutcome.PARTIAL, host.run("result_fragment", fragment(local)).result!!.outcome)
            SpotifyNetworkPolicy.setManual(false)
            assertError(host.run("result_fragment", fragment(remote)), "spotify_offline")
            assertEquals(3, peer.requests.size)
        } }
    }
}
