// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.bridge

import dev.antigravity.fluidengine.ai.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Keep source-proof v1/v2typed unchanged, including its account/caller/lease validation. */
internal class BridgeSourceRead(private val delegate: AiTool<FluidifyBridgeContext>) : AiTool<FluidifyBridgeContext> by delegate {
    override suspend fun run(args: JsonObject, ctx: FluidifyBridgeContext): ToolOutput =
        guarded(args, ctx) { delegate.run(args, it) }

    override suspend fun run(args: JsonObject, ctx: FluidifyBridgeContext, execution: ToolExecution): ToolOutput =
        guarded(args, ctx) { delegate.run(args, it, execution) }

    private suspend fun guarded(args: JsonObject, ctx: FluidifyBridgeContext,
        read: suspend (FluidifyBridgeContext) -> ToolOutput): ToolOutput {
        val target = (args["tool"] as? JsonPrimitive)?.content
        val input = args["arguments"] as? JsonObject
        val storedPermit = if (target == "result_fragment")
            (input?.get("snapshot") as? JsonPrimitive)?.content?.let { ctx.fragments.network(it) } else null
        if (target !in setOf("playlists", "playlist_details", "playlist_tracks") && storedPermit == null) return read(ctx)

        val access = ctx.history.session.value.access ?: return read(ctx)
        val generation = ctx.tokens.generation
        var operation: BridgeNetworkOperation? = null
        return try {
            val fence: () -> Unit = {
                if (ctx.history.session.value.access != access || ctx.tokens.generation != generation)
                    throw BridgeFailure("account_revoked")
            }
            ctx.networkOperation?.check()
            val cursorPermit = if (target in setOf("playlists", "playlist_tracks") && input != null)
                (input["cursor"] as? JsonPrimitive)?.content?.let {
                    ctx.pager.network(access, bridgeQuery(checkNotNull(target), input), it, generation)
                } else null
            val owned = if (storedPermit != null || cursorPermit != null)
                BridgeNetworkOperation(checkNotNull(storedPermit ?: cursorPermit), fence)
                else ctx.networkOperation ?: BridgeNetworkOperation.capture(fence)
            owned.check()
            operation = owned
            val scoped = FluidifyBridgeContext(ctx.app, ctx.call, ctx.pager, ctx.operations, ctx.fragments, owned, ctx.spotifyApi, ctx.tokens, ctx.history)
            bridgeSourcePublication(owned) { read(scoped) }
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { failure(bridgeFailureCode(e, operation)) }
    }
}

/** Includes proof composition after the underlying tool has returned; local reads bypass it. */
internal suspend fun bridgeSourcePublication(operation: BridgeNetworkOperation, read: suspend () -> ToolOutput): ToolOutput {
    operation.check()
    val output = read()
    // Failure evidence must remain deliverable after a revocation; success data must not.
    if (output.result?.outcome in setOf(ToolOutcome.ERROR, ToolOutcome.UNCERTAIN)) return output
    return operation.publish { output }
}
