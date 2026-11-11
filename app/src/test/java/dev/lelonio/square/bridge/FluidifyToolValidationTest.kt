package dev.lelonio.square.bridge

import dev.antigravity.fluidengine.ai.bridge.RemoteCall
import dev.antigravity.fluidengine.ai.tools.ToolOutcome
import dev.lelonio.square.data.HistoryAccess
import dev.lelonio.square.data.HistoryAccount
import dev.lelonio.square.SquareApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [35])
class FluidifyToolValidationTest {
    @Test fun malformedWritesReturnErrorBeforeTouchingUnattachedApplication() = runBlocking {
        val store = object : OperationStore {
            override fun get(key: String): JsonObject? = error("must not access ledger")
            override fun put(key: String, value: JsonObject) = error("must not persist")
        }
        val ctx = FluidifyBridgeContext(SquareApplication(), RemoteCall("caller", "it", true, "request"), SnapshotPager(), PlaylistOperations(store))
        val tool = fluidifyRegistry().find("playlist_create")!!
        for (args in listOf(buildJsonObject { put("_raw", "{") }, buildJsonObject { put("name", "x") },
            buildJsonObject { put("account", "a"); put("name", JsonArray(emptyList())); put("operationId", "op") })) {
            assertEquals(ToolOutcome.ERROR, tool.run(args, ctx).result!!.outcome)
        }
    }
    @Test fun transportIdentityWinsAndConflictsFailClosed() {
        assertEquals("parent", durableWriteIdentity("parent", "parent", null))
        assertEquals("parent", durableWriteIdentity(null, "parent", JsonPrimitive("parent")))
        assertThrows(BridgeFailure::class.java) { durableWriteIdentity("parent", "parent", JsonPrimitive("model")) }
        assertThrows(BridgeFailure::class.java) { durableWriteIdentity("other", "parent", null) }
        assertThrows(BridgeFailure::class.java) { durableWriteIdentity(null, "", JsonPrimitive("model")) }
    }
    @Test fun discoveryNeedsNoAccountAndNeverSelectsAnotherAccount() {
        val access = HistoryAccess(HistoryAccount("authenticated"), 1)
        assertEquals("authenticated", currentAccount(access, null, true))
        assertThrows(BridgeFailure::class.java) { currentAccount(access, "other", true) }
        assertThrows(BridgeFailure::class.java) { currentAccount(access, null, false) }
        val schema = fluidifyRegistry().find("capabilities")!!.parameters
        assertFalse(schema["properties"]!!.jsonObject.containsKey("account"))
        assertFalse(fluidifyRegistry().find("playlist_create")!!.parameters["properties"]!!.jsonObject.containsKey("operationId"))
    }
    @Test fun durableDuplicateClaimsSurviveStoreRecreation() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("aria-playlist-operations", 0).edit().clear().commit()
        var dispatches = 0
        suspend fun attempt(payload: String = "payload") = PlaylistOperations(DurableOperationStore(context))
            .run("parent", payload, true, null, { dispatches++; "actual" }, { it == "actual" })
        assertEquals("actual", attempt())
        assertEquals("actual", attempt())
        try { attempt("conflict"); fail() } catch (e: BridgeFailure) { assertEquals("operation_conflict", e.code) }
        assertEquals(1, dispatches)
    }
    @Test fun threeArgumentWriteRejectsModelIdentityBeforeAnyApplicationAccess() = runBlocking {
        val store = object : OperationStore {
            override fun get(key: String): JsonObject? = error("no ledger access")
            override fun put(key: String, value: JsonObject) = error("no ledger access")
        }
        val ctx = FluidifyBridgeContext(SquareApplication(), RemoteCall("caller", "it", true, "parent"), SnapshotPager(), PlaylistOperations(store))
        val args = buildJsonObject { put("account", "a"); put("name", "x"); put("operationId", "model") }
        val out = fluidifyRegistry().find("playlist_create")!!.run(args, ctx, dev.antigravity.fluidengine.ai.tools.ToolExecution("parent"))
        assertEquals("operation_identity_conflict", out.result!!.error!!.code)
        assertEquals(ToolOutcome.ERROR, out.result!!.outcome)
    }
    @Test fun sourceProofToolsAreInActualRegistryWithoutWeakeningWriteConfirmation() {
        val registry = fluidifyRegistry()
        for (name in listOf("source_capabilities", "source_lease", "source_validate", "source_read")) {
            assertFalse(registry.find(name)!!.isAction)
            assertFalse(registry.find(name)!!.needsConfirmation)
        }
        assertTrue(registry.find("playlist_append")!!.needsConfirmation)
        assertTrue(registry.find("playlist_create")!!.isAction)
    }

}
