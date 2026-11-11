package dev.lelonio.square.bridge

import dev.antigravity.fluidengine.ai.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID

/** Source-local point-in-time authorization. No credentials, storage, network or UI access. */
internal class ProofIdentity(private val value: Any) {
    override fun equals(other: Any?) = other is ProofIdentity && value === other.value
    override fun hashCode() = System.identityHashCode(value)
    override fun toString() = "opaque-publication"
}
internal data class ProofState(val account: String, val student: String?, val generation: String,
    val publication: ProofIdentity? = null)
internal class ProofRejected(val code: String) : IllegalArgumentException(code)

internal class SourceProofRegistry<C>(
    private val base: ToolRegistry<C>, private val domain: String, private val authority: String,
    private val allowed: Set<String>, private val state: (C) -> ProofState?,
    private val caller: (C) -> String?,
    private val tick: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wall: () -> Long = System::currentTimeMillis,
) {
    private val incarnation = UUID.randomUUID().toString()
    private data class Capability(val caller: String, val state: ProofState, val until: Long, val authorization: String)
    private data class Lease(val capability: String, val scope: JsonObject, val until: Long, val expires: Long)
    // Identity lifetime is the current authorization state, not the capability TTL.
    // Saturation rejects new callers instead of rotating an unchanged caller's identity.
    private val authorizations = linkedMapOf<String, String>()
    private var observedState: ProofState? = null
    private val capabilities = linkedMapOf<String, Capability>()
    private val leases = linkedMapOf<String, Lease>()
    private val snapshots = linkedMapOf<String, String>()
    private val group = base.groups.first()
    private val controls = setOf("cursor", "cursore", "dettaglio_offset", "dettaglio_hash", "aggiorna")
    private fun reject(code: String): Nothing = throw ProofRejected(code)
    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: reject("invalid_arguments")
    @Synchronized private fun current(ctx: C): ProofState {
        val current = state(ctx)?.takeIf { it.account.isNotBlank() }
        if (observedState != current) {
            authorizations.clear()
            capabilities.clear()
            leases.clear()
            snapshots.clear()
            observedState = current
        }
        return current ?: reject("source_not_ready")
    }
    @Synchronized private fun authorization(who: String): String {
        authorizations[who]?.let { return it }
        if (authorizations.size >= 128) reject("source_authorization_capacity")
        return UUID.randomUUID().toString().also { authorizations[who] = it }
    }
    private fun identity(ctx: C) = caller(ctx)?.takeIf { it.isNotBlank() } ?: reject("caller_required")
    private fun scope(tool: String, args: JsonObject, s: ProofState): JsonObject {
        if (tool !in allowed) reject("source_tool_denied")
        val original = base.find(tool) ?: reject("source_tool_denied")
        if (original.isAction || original.needsConfirmation) reject("source_tool_denied")
        ToolArgumentValidator.error(args, original.parameters)?.let { reject("invalid_arguments") }
        if (args.keys.any { it !in original.parameters["properties"]!!.jsonObject }) reject("invalid_arguments")
        if (args.str("account") != s.account) reject("source_scope_mismatch")
        if (domain == "cv") {
            if (args.str("studente") != s.student) reject("source_scope_mismatch")
            val year = args.str("anno")
            if (!Regex("[0-9]{4}-[0-9]{4}").matches(year) || year.takeLast(4).toInt() != year.take(4).toInt() + 1)
                reject("invalid_arguments")
            args.str("sezione")
        }
        return buildJsonObject { put("tool", tool); put("arguments", JsonObject(args.filterKeys { it !in controls })) }
    }
    @Synchronized private fun prune() {
        val t = tick()
        capabilities.entries.removeAll { t >= it.value.until }
        leases.entries.removeAll { t >= it.value.until || it.value.capability !in capabilities }
        snapshots.entries.removeAll { it.value !in leases }
    }
    @Synchronized private fun capability(id: String, ctx: C): Capability {
        prune()
        val who = identity(ctx)
        val s = current(ctx)
        val cap = capabilities[id] ?: reject("source_capability_invalid")
        if (cap.caller != who || cap.state != s || authorizations[who] != cap.authorization)
            reject("source_capability_invalid")
        return cap
    }
    @Synchronized private fun validate(args: JsonObject, ctx: C, fragment: Boolean = false): Lease {
        val cap = args.str("capability")
        capability(cap, ctx)
        val lease = leases[args.str("lease")] ?: reject("source_lease_invalid")
        if (lease.capability != cap) reject("source_lease_invalid")
        val target = args.str("tool")
        val input = args["arguments"] as? JsonObject ?: reject("invalid_arguments")
        if (fragment && target == "result_fragment" && domain == "fluidify") {
            if (input.str("account") != current(ctx).account || snapshots[input.str("snapshot")] != args.str("lease"))
                reject("source_scope_mismatch")
        } else if (lease.scope != scope(target, input, current(ctx))) reject("source_scope_mismatch")
        return lease
    }
    private fun receipt(cap: String, id: String, lease: Lease, c: Capability) = buildJsonObject {
        val s = c.state
        put("authorization", c.authorization)
        put("contract", "aria.source-proof"); put("version", 1); put("domain", domain); put("authority", authority)
        put("incarnation", incarnation); put("capability", cap); put("lease", id); put("account", s.account)
        put("student", s.student?.let(::JsonPrimitive) ?: JsonNull); put("scope", lease.scope)
        put("checkedAtMillis", wall()); put("expiresAtMillis", lease.expires); put("valid", true)
    }
    private val proofFields = mapOf("version" to Schema.int("Source proof contract version", 1, 1),
        "capability" to Schema.str("Opaque source capability"), "lease" to Schema.str("Opaque source lease"),
        "tool" to Schema.str("Exact original source tool"), "arguments" to Schema.obj(emptyMap()))
    fun registry(): ToolRegistry<C> = ToolRegistry(base.tools + listOf(
        proofTool("source_capabilities", emptyMap()),
        proofTool("source_lease", proofFields - "lease"),
        proofTool("source_validate", proofFields), proofTool("source_read", proofFields),
    ), base.groups, base.actionGroup)
    private fun proofTool(id: String, fields: Map<String, JsonObject>): AiTool<C> = object : AiTool<C> {
        override val name = id
        override val group = this@SourceProofRegistry.group
        override val description = "Source-owned authorization proof v1: $id. Typed v2 required; never a distributed lock."
        override val parameters = Schema.obj(fields, fields.keys.toList())
        override suspend fun run(args: JsonObject, ctx: C): ToolOutput {
            return try {
                ToolArgumentValidator.error(args, parameters)?.let { reject("invalid_arguments") }
                if (args.keys.any { it !in fields }) reject("invalid_arguments")
                val data = when (id) {
                    "source_capabilities" -> synchronized(this@SourceProofRegistry) {
                        prune()
                        val who = identity(ctx); val s = current(ctx)
                        val authorization = authorization(who)
                        if (capabilities.size >= 128) capabilities.remove(capabilities.keys.first())
                        val cap = UUID.randomUUID().toString()
                        capabilities[cap] = Capability(who, s, tick() + 60_000, authorization)
                        buildJsonObject {
                            put("contract", "aria.source-proof"); put("version", 1); put("capability", cap)
                            put("incarnation", incarnation); put("authorization", authorization)
                            put("domain", domain); put("authority", authority)
                            put("account", s.account); put("student", s.student?.let(::JsonPrimitive) ?: JsonNull)
                            put("ttlMillis", 60_000); put("readTools", JsonArray(allowed.sorted().map(::JsonPrimitive)))
                        }
                    }
                    "source_lease" -> synchronized(this@SourceProofRegistry) {
                        val cap = args.str("capability"); val c = capability(cap, ctx)
                        val scoped = scope(args.str("tool"), args["arguments"]!!.jsonObject, c.state)
                        if (leases.size >= 256) leases.remove(leases.keys.first())
                        val lease = Lease(cap, scoped, c.until, wall() + (c.until - tick()).coerceAtLeast(0))
                        val key = UUID.randomUUID().toString(); leases[key] = lease
                        receipt(cap, key, lease, capability(cap, ctx))
                    }
                    "source_validate" -> {
                        val lease = validate(args, ctx)
                        receipt(args.str("capability"), args.str("lease"), lease, capability(args.str("capability"), ctx))
                    }
                    else -> {
                        validate(args, ctx, fragment = true)
                        val target = args.str("tool")
                        if (target !in allowed && !(domain == "fluidify" && target == "result_fragment")) reject("source_tool_denied")
                        val tool = base.find(target) ?: reject("source_tool_denied")
                        if (tool.isAction || tool.needsConfirmation) reject("source_tool_denied")
                        val input = args["arguments"]!!.jsonObject
                        ToolArgumentValidator.error(input, tool.parameters)?.let { reject("invalid_arguments") }
                        if (input.keys.any { it !in tool.parameters["properties"]!!.jsonObject }) reject("invalid_arguments")
                        val output = tool.run(input, ctx)
                        val lease = validate(args, ctx, fragment = true)
                        val details = output.result ?: reject("source_typed_result_required")
                        if (details.outcome == ToolOutcome.ERROR || details.outcome == ToolOutcome.UNCERTAIN) return output
                        val original = details.data ?: reject("source_typed_result_required")
                        if (domain == "cv" && target == "registro_dati") {
                            val expected = lease.scope["arguments"]!!.jsonObject
                            for ((returned, requested) in listOf("account" to "account", "student" to "studente",
                                "year" to "anno", "section" to "sezione")) {
                                if (original[returned] != expected[requested]) reject("source_scope_mismatch")
                            }
                        }
                        synchronized(this@SourceProofRegistry) {
                            (original["snapshot"] as? JsonPrimitive)?.content?.let {
                                if (snapshots.size >= 256 && it !in snapshots) snapshots.remove(snapshots.keys.first())
                                snapshots[it] = args.str("lease")
                            }
                        }
                        val proof = receipt(args.str("capability"), args.str("lease"), lease, capability(args.str("capability"), ctx))
                        val result = JsonObject(original + ("sourceProof" to proof))
                        return output.copy(result = details.copy(data = result))
                    }
                }
                ToolOutput("Source proof v1: $id", result = ToolResultDetails(ToolOutcome.COMPLETED, data = data))
            } catch (e: CancellationException) { throw e }
              catch (e: ProofRejected) { ToolOutput("Source proof rejected: ${e.code}",
                result = ToolResultDetails(ToolOutcome.ERROR, error = ToolError(e.code, e.code, false))) }
        }
    }
}
