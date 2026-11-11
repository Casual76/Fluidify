package dev.lelonio.square.bridge

import dev.antigravity.fluidengine.ai.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic data only; dispatch through ToolRegistry.find and the production proof tools. */
class SourceProofRegistryTest {
    private class Fixture {
        var state: ProofState? = ProofState("account", "student", "1:password")
        var caller: String? = "signed.caller"
        var time = 0L
        var reads = 0
        var afterRead: () -> Unit = {}
        var cv = false
        private val group = object : AiToolGroup {
            override val id = "test"
            override val statusKey = "test"
            override val hint = "test"
        }
        private fun readTool(id: String, action: Boolean = false) = object : AiTool<Fixture> {
            override val name = id
            override val group = this@Fixture.group
            override val description = "synthetic"
            override val isAction = action
            override val parameters = Schema.obj(mapOf("account" to Schema.str("account"),
                "studente" to Schema.str("student"), "anno" to Schema.str("year"), "sezione" to Schema.str("area"),
                "from" to Schema.str("filter"), "cursor" to Schema.str("cursor"), "snapshot" to Schema.str("snapshot")))
            override suspend fun run(args: JsonObject, ctx: Fixture): ToolOutput {
                reads++; afterRead()
                return ToolOutput("synthetic", result = ToolResultDetails(ToolOutcome.PARTIAL,
                    data = buildJsonObject { put("snapshot", "snapshot-1"); put("records", JsonArray(emptyList())) }))
            }
        }
        fun registry() = SourceProofRegistry(ToolRegistry(listOf(readTool("read"), readTool("write", true),
            readTool("disguised_write"), readTool("result_fragment")), listOf(group)),
            if (cv) "cv" else "fluidify", "synthetic.ai.tools", setOf("read", "write"),
            { it.state }, { it.caller }, { time }, { 1000L + time }).registry()
        var registry = registry()
        fun scope() = buildJsonObject {
            put("account", "account"); put("from", "10")
            if (cv) { put("studente", "student"); put("anno", "2026-2027"); put("sezione", "agenda") }
        }
        suspend fun call(name: String, args: JsonObject = buildJsonObject {}) = registry.find(name)!!.run(args, this)
        suspend fun capability() = call("source_capabilities").result!!.data!!["capability"]!!.jsonPrimitive.content
        fun request(cap: String, lease: String? = null, tool: String = "read", input: JsonObject = scope(), version: Int = 1) = buildJsonObject {
            put("version", version); put("capability", cap); lease?.let { put("lease", it) }
            put("tool", tool); put("arguments", input)
        }
        suspend fun lease(cap: String) = call("source_lease", request(cap)).result!!.data!!["lease"]!!.jsonPrimitive.content
    }
    private fun error(output: ToolOutput, code: String? = null) {
        assertEquals(ToolOutcome.ERROR, output.result!!.outcome)
        assertNull(output.result!!.data)
        if (code != null) assertEquals(code, output.result!!.error!!.code)
    }
    @Test fun opaqueCapabilityAndTypedReceiptContainNoGenerationOrCredentials() = runBlocking {
        val f = Fixture(); val cap = f.capability(); val lease = f.lease(cap)
        val data = f.call("source_read", f.request(cap, lease)).result!!.data!!
        val proof = data["sourceProof"]!!.jsonObject
        assertEquals("aria.source-proof", proof["contract"]!!.jsonPrimitive.content)
        assertEquals("account", proof["account"]!!.jsonPrimitive.content)
        assertEquals(lease, proof["lease"]!!.jsonPrimitive.content)
        assertFalse(proof.toString().contains("1:password"))
        assertFalse(proof.toString().contains("token"))
        assertEquals(1, f.reads)
    }
    @Test fun missingOldAndUnknownCapabilityFailBeforeDispatch() = runBlocking {
        val f = Fixture(); val cap = f.capability(); val lease = f.lease(cap)
        error(f.call("source_read", f.request("unknown", lease)))
        error(f.call("source_read", f.request(cap, lease, version = 0)))
        error(f.call("source_read", JsonObject(f.request(cap, lease) - "capability")))
        assertEquals(0, f.reads)
    }
    @Test fun callerCannotReuseAnotherCallersLease() = runBlocking {
        val f = Fixture(); val cap = f.capability(); val lease = f.lease(cap); f.caller = "other.signed.caller"
        error(f.call("source_read", f.request(cap, lease)), "source_capability_invalid"); assertEquals(0, f.reads)
    }
    @Test fun unknownCallerAndSignedOutStateHaveNoProof() = runBlocking {
        val f = Fixture(); f.caller = null; error(f.call("source_capabilities"), "caller_required")
        f.caller = "caller"; f.state = null; error(f.call("source_capabilities"), "source_not_ready")
    }
    @Test fun exactAccountAndFiltersRejectWithoutSelectingAnything() = runBlocking {
        val f = Fixture(); val cap = f.capability(); val lease = f.lease(cap)
        for ((key,value) in listOf("account" to "other", "from" to "11"))
            error(f.call("source_read", f.request(cap, lease, input = JsonObject(f.scope() + (key to JsonPrimitive(value))))))
        assertEquals("account", f.state!!.account); assertEquals(0, f.reads)
    }
    @Test fun sameAccountReauthDataEpochChangeRejectsOldProof() = runBlocking {
        val f = Fixture(); val cap = f.capability(); val lease = f.lease(cap)
        f.state = f.state!!.copy(generation = "2:password")
        error(f.call("source_validate", f.request(cap, lease)), "source_capability_invalid")
    }
    @Test fun cieAliasTransitionRejectsOldDataGeneration() = runBlocking {
        val f = Fixture(); f.cv = true; f.registry = f.registry()
        val cap = f.capability(); val lease = f.lease(cap)
        f.state = f.state!!.copy(generation = "2:CIE")
        error(f.call("source_read", f.request(cap, lease))); assertEquals(0, f.reads)
    }
    @Test fun processRecreationRejectsSameNumberGenerationAndOpaqueHandles() = runBlocking {
        val f = Fixture(); val cap = f.capability(); val lease = f.lease(cap); f.registry = f.registry()
        error(f.call("source_validate", f.request(cap, lease)), "source_capability_invalid")
    }
    @Test fun expirationIsMonotonicAndValidationDoesNotRenew() = runBlocking {
        val f = Fixture(); val cap = f.capability(); val lease = f.lease(cap)
        f.time = 59_999; assertEquals(ToolOutcome.COMPLETED, f.call("source_validate", f.request(cap, lease)).result!!.outcome)
        f.time = 60_000; error(f.call("source_read", f.request(cap, lease))); assertEquals(0, f.reads)
    }
    @Test fun recordingRevocationOrDeletionDuringProductionDiscardsLateResult() = runBlocking {
        for (next in listOf<ProofState?>(null, ProofState("account", "student", "2:password"))) {
            val f = Fixture(); val cap = f.capability(); val lease = f.lease(cap)
            f.afterRead = { f.state = next }
            error(f.call("source_read", f.request(cap, lease))); assertEquals(1, f.reads)
        }
    }
    @Test fun unknownAndMisclassifiedWritesAreDeniedEvenWithReadMetadata() = runBlocking {
        val f = Fixture(); val cap = f.capability()
        for (tool in listOf("write", "disguised_write", "unknown"))
            error(f.call("source_lease", f.request(cap, tool = tool)), "source_tool_denied")
        assertEquals(0, f.reads)
    }
    @Test fun cvRequiresExplicitStudentYearAndSectionAndKeepsThemExact() = runBlocking {
        val f = Fixture(); f.cv = true; f.registry = f.registry()
        val cap = f.capability(); val lease = f.lease(cap)
        for (key in listOf("account", "studente", "anno", "sezione"))
            error(f.call("source_lease", f.request(cap, input = JsonObject(f.scope() - key))))
        for ((key,value) in listOf("studente" to "other", "anno" to "2025-2026", "sezione" to "grades"))
            error(f.call("source_read", f.request(cap, lease, input = JsonObject(f.scope() + (key to JsonPrimitive(value))))))
        assertEquals(0, f.reads)
    }
    @Test fun fragmentIsBoundToLeaseThatActuallyProducedSnapshot() = runBlocking {
        val f = Fixture(); val cap = f.capability(); val lease = f.lease(cap)
        val input = buildJsonObject { put("account", "account"); put("snapshot", "snapshot-1") }
        error(f.call("source_read", f.request(cap, lease, "result_fragment", input)))
        f.call("source_read", f.request(cap, lease))
        assertEquals(ToolOutcome.PARTIAL, f.call("source_read", f.request(cap, lease, "result_fragment", input)).result!!.outcome)
        val other = f.lease(cap)
        error(f.call("source_read", f.request(cap, other, "result_fragment", input)))
    }
    @Test fun continuationChangesDoNotExpandScope() = runBlocking {
        val f = Fixture(); val cap = f.capability(); val lease = f.lease(cap)
        val input = JsonObject(f.scope() + ("cursor" to JsonPrimitive("next-page")))
        assertEquals(ToolOutcome.PARTIAL, f.call("source_read", f.request(cap, lease, input = input)).result!!.outcome)
        assertEquals(1, f.reads)
    }
    @Test fun missingLeaseAndUnknownFieldsFailClosed() = runBlocking {
        val f = Fixture(); val cap = f.capability()
        error(f.call("source_read", f.request(cap)))
        error(f.call("source_lease", JsonObject(f.request(cap) + ("caller" to JsonPrimitive("model")))))
        assertEquals(0, f.reads)
    }
    @Test fun sameAccountPublicationReplacementRejectsEvenWhenDataEpochIsUnchanged() = runBlocking {
        val f = Fixture(); val publication = Any()
        f.state = f.state!!.copy(publication = ProofIdentity(publication))
        val cap = f.capability(); val lease = f.lease(cap)
        f.state = f.state!!.copy(publication = ProofIdentity(publication))
        assertEquals(ToolOutcome.COMPLETED, f.call("source_validate", f.request(cap, lease)).result!!.outcome)
        f.state = f.state!!.copy(publication = ProofIdentity(Any()))
        error(f.call("source_read", f.request(cap, lease)), "source_capability_invalid")
        assertEquals(0, f.reads)
    }

    @Test fun sourceCapabilityTravelsOnlyThroughNegotiatedTypedResultEnvelope() = runBlocking {
        val f = Fixture()
        val output = f.call("source_capabilities")
        val legacy = dev.antigravity.fluidengine.ai.bridge.BridgeProtocol.withResult(buildJsonObject {}, output, null)
        assertFalse(legacy.containsKey("result"))
        val request = buildJsonObject {
            put("protocol", 2); put("capabilities", JsonArray(listOf(JsonPrimitive("typed_results"))))
        }
        val typed = dev.antigravity.fluidengine.ai.bridge.BridgeProtocol.withResult(buildJsonObject {}, output, request)
        assertEquals(output.result!!.data, typed["result"]!!.jsonObject["data"])
        assertEquals(1, dev.antigravity.fluidengine.ai.bridge.BridgeProtocol.PROTOCOL)
    }


    @Test fun expiryRenewalKeepsAuthorizationButNeverRevivesOldHandles() = runBlocking {
        val f = Fixture()
        val first = f.call("source_capabilities").result!!.data!!
        val cap = first["capability"]!!.jsonPrimitive.content
        val lease = f.lease(cap)
        val oldRequest = f.request(cap, lease)
        f.time = 60_000
        error(f.call("source_validate", oldRequest), "source_capability_invalid")
        val next = f.call("source_capabilities").result!!.data!!
        assertEquals(first["authorization"], next["authorization"])
        assertEquals(first["incarnation"], next["incarnation"])
        assertNotEquals(first["capability"], next["capability"])
        java.util.UUID.fromString(next["authorization"]!!.jsonPrimitive.content)
        val nextCap = next["capability"]!!.jsonPrimitive.content
        val nextLease = f.lease(nextCap)
        assertNotEquals(lease, nextLease)
        error(f.call("source_validate", f.request(nextCap, lease)))
        val fresh = f.call("source_validate", f.request(nextCap, nextLease)).result!!.data!!
        assertEquals(next["authorization"], fresh["authorization"])
        assertEquals(121_000L, fresh["expiresAtMillis"]!!.jsonPrimitive.long)
        error(f.call("source_read", oldRequest))
        assertEquals(0, f.reads)
    }
    @Test fun everyReceiptCarriesTheCapabilityAuthorizationIdentity() = runBlocking {
        val f = Fixture(); val advertised = f.call("source_capabilities").result!!.data!!
        val cap = advertised["capability"]!!.jsonPrimitive.content
        val leased = f.call("source_lease", f.request(cap)).result!!.data!!
        val lease = leased["lease"]!!.jsonPrimitive.content
        val validated = f.call("source_validate", f.request(cap, lease)).result!!.data!!
        val read = f.call("source_read", f.request(cap, lease)).result!!.data!!["sourceProof"]!!.jsonObject
        val fragmentArgs = buildJsonObject { put("account", "account"); put("snapshot", "snapshot-1") }
        val fragment = f.call("source_read", f.request(cap, lease, "result_fragment", fragmentArgs))
            .result!!.data!!["sourceProof"]!!.jsonObject
        for (receipt in listOf(leased, validated, read, fragment)) {
            assertEquals(advertised["authorization"], receipt["authorization"])
            assertEquals(advertised["incarnation"], receipt["incarnation"])
        }
    }
    @Test fun authorizationIsCallerBoundAndStableForEachCallerAcrossRenewal() = runBlocking {
        val f = Fixture(); val first = f.call("source_capabilities").result!!.data!!
        f.caller = "second.signed.caller"
        val second = f.call("source_capabilities").result!!.data!!
        assertNotEquals(first["authorization"], second["authorization"])
        f.time = 60_000
        assertEquals(second["authorization"], f.call("source_capabilities").result!!.data!!["authorization"])
        f.caller = "signed.caller"
        assertEquals(first["authorization"], f.call("source_capabilities").result!!.data!!["authorization"])
    }
    @Test fun generationAccountStudentAndPublicationChangesRotateAuthorizationAndRevokeHandles() = runBlocking {
        val changes = listOf(ProofState("account", "student", "2:recording-changed"),
            ProofState("other-account", "student", "1:password"), ProofState("account", "other-student", "1:password"),
            ProofState("account", "student", "1:password", ProofIdentity(Any())))
        for (changed in changes) {
            val f = Fixture(); val before = f.call("source_capabilities").result!!.data!!
            val cap = before["capability"]!!.jsonPrimitive.content; val lease = f.lease(cap)
            f.state = changed
            val after = f.call("source_capabilities").result!!.data!!
            assertNotEquals(before["authorization"], after["authorization"])
            assertEquals(before["incarnation"], after["incarnation"])
            error(f.call("source_validate", f.request(cap, lease)))
        }
    }
    @Test fun observedUnavailableTransitionCannotRestoreAnOldAuthorizationIdentity() = runBlocking {
        val f = Fixture(); val before = f.call("source_capabilities").result!!.data!!
        val cap = before["capability"]!!.jsonPrimitive.content; val lease = f.lease(cap)
        val originalState = f.state
        f.state = null; error(f.call("source_capabilities"), "source_not_ready")
        // Even synthetic reuse of identical state cannot resurrect an observed revocation.
        f.state = originalState
        val after = f.call("source_capabilities").result!!.data!!
        assertNotEquals(before["authorization"], after["authorization"])
        error(f.call("source_read", f.request(cap, lease)))
    }
    @Test fun processRestartRotatesIncarnationAndAuthorizationDespiteIdenticalSourceState() = runBlocking {
        val f = Fixture(); val before = f.call("source_capabilities").result!!.data!!
        val cap = before["capability"]!!.jsonPrimitive.content; val lease = f.lease(cap)
        f.registry = f.registry()
        val after = f.call("source_capabilities").result!!.data!!
        assertNotEquals(before["authorization"], after["authorization"])
        assertNotEquals(before["incarnation"], after["incarnation"])
        error(f.call("source_validate", f.request(cap, lease)))
    }
    @Test fun callerMapSaturationFailsClosedWithoutEvictingRenewableIdentities() = runBlocking {
        val f = Fixture(); val first = f.call("source_capabilities").result!!.data!!
        for (i in 1..127) { f.caller = "signed.caller.$i"; f.capability() }
        f.time = 60_000
        f.caller = "overflow.caller"
        error(f.call("source_capabilities"), "source_authorization_capacity")
        f.caller = "signed.caller"
        assertEquals(first["authorization"], f.call("source_capabilities").result!!.data!!["authorization"])
        f.state = f.state!!.copy(generation = "2:password")
        f.caller = "overflow.caller"
        assertEquals(ToolOutcome.COMPLETED, f.call("source_capabilities").result!!.outcome)
    }
    @Test fun capabilityEvictionDoesNotChangeAuthorizationOrRestoreEvictedHandles() = runBlocking {
        val f = Fixture(); val before = f.call("source_capabilities").result!!.data!!
        val cap = before["capability"]!!.jsonPrimitive.content; val lease = f.lease(cap)
        repeat(128) { f.capability() }
        val after = f.call("source_capabilities").result!!.data!!
        assertEquals(before["authorization"], after["authorization"])
        error(f.call("source_validate", f.request(cap, lease)))
        val authorization = after["authorization"]!!.jsonPrimitive.content
        error(f.call("source_lease", f.request(authorization)))
        error(f.call("source_validate", f.request(after["capability"]!!.jsonPrimitive.content, authorization)))
    }
    @Test fun queryScopeChangesDoNotRotateAuthorizationOrExpandExistingLeaseScope() = runBlocking {
        val f = Fixture(); val before = f.call("source_capabilities").result!!.data!!
        val cap = before["capability"]!!.jsonPrimitive.content; val lease = f.lease(cap)
        val changedScope = JsonObject(f.scope() + ("from" to JsonPrimitive("20")))
        val other = f.call("source_lease", f.request(cap, input = changedScope)).result!!.data!!
        assertEquals(before["authorization"], other["authorization"])
        error(f.call("source_read", f.request(cap, lease, input = changedScope)), "source_scope_mismatch")
        assertEquals(0, f.reads)
    }

}
