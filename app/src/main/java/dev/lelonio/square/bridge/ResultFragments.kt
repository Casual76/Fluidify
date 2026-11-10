// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.bridge

import dev.antigravity.fluidengine.ai.tools.*
import dev.lelonio.square.data.HistoryAccess
import dev.lelonio.square.data.SpotifyNetworkPolicy
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

/** Read-only immutable result/receipt continuation; never invokes the original tool again.
 * Text fits the default 2400-character model budget including execution evidence. Fragments concatenate
 * to the exact original JSON (UTF-16 offsets), authenticated by its SHA-256 UTF-8 hash.
 */
internal class ResultFragments(private val now: () -> Long = System::currentTimeMillis) {
    private data class Saved(val access: HistoryAccess, val caller: String?, val queryHash: String,
        val text: String, val hash: String, val at: Long, val details: ToolResultDetails, val sourceGeneration: Long?, val network: SpotifyNetworkPolicy.Permit?)
    private val saved = linkedMapOf<String, Saved>()
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    @Synchronized fun output(access: HistoryAccess, caller: String?, query: String,
        data: JsonObject, details: ToolResultDetails, sourceGeneration: Long? = null,
        network: SpotifyNetworkPolicy.Permit? = null): ToolOutput {
        network?.check()
        val text = data.toString()
        if (text.length <= 1300) {
            val visible = details.pagination?.nextCursor?.let { cursor ->
                JsonObject(data + ("nextPageCursor" to JsonPrimitive(cursor)))
            } ?: data
            val output = ToolOutput(visible.toString(), result = details)
            return network?.admit { output } ?: output
        }
        prune(access)
        if (text.length > 16_000_000) throw BridgeFailure("result_snapshot_limit")
        if (saved.size >= 8) saved.remove(saved.keys.first())
        val id = UUID.randomUUID().toString()
        val snapshot = Saved(access, caller, hash(query), text, hash(text), now(), details, sourceGeneration, network)
        val output = chunk(id, snapshot, 0)
        fun publish(): ToolOutput { saved[id] = snapshot; return output }
        return network?.admit { publish() } ?: publish()
    }
    private fun prune(access: HistoryAccess) {
        saved.entries.removeAll { now() - it.value.at > 300_000 || it.value.access != access }
    }
    @Synchronized fun read(access: HistoryAccess, caller: String?, snapshot: String,
        hash: String, queryHash: String, offset: Int, sourceGeneration: Long? = null): ToolOutput {
        prune(access)
        val s = saved[snapshot] ?: throw BridgeFailure("expired_result_snapshot")
        if (s.access != access || s.caller != caller || s.sourceGeneration != sourceGeneration || s.hash != hash || s.queryHash != queryHash ||
            offset !in 0 until s.text.length) throw BridgeFailure("invalid_result_fragment")
        val output = chunk(snapshot, s, offset)
        return s.network?.admit { output } ?: output
    }
    /** Internal metadata only. The read still checks caller/account/hash/generation/expiry. */
    @Synchronized fun network(snapshot: String): SpotifyNetworkPolicy.Permit? = saved[snapshot]?.network

    private fun chunk(id: String, s: Saved, offset: Int): ToolOutput {
        var end = minOf(offset + 1000, s.text.length)
        fun envelope(): JsonObject {
            // Never split a Unicode supplementary character across transport messages.
            if (end < s.text.length && end > offset && s.text[end - 1].isHighSurrogate() && s.text[end].isLowSurrogate()) end--
            return buildJsonObject {
                put("snapshot", id); put("hash", s.hash); put("queryHash", s.queryHash)
                put("offset", offset); put("end", end); put("totalChars", s.text.length)
                put("encoding", "concatenate_json_text_utf16"); put("truncated", end < s.text.length)
                put("fragment", s.text.substring(offset, end))
                if (end < s.text.length) {
                    put("nextTool", "result_fragment"); put("nextOffset", end)
                } else {
                    s.details.pagination?.nextCursor?.let { put("nextPageCursor", it) }
                }
            }
        }
        var data = envelope()
        while (data.toString().length > 1600) {
            end = offset + (end - offset) / 2
            data = envelope()
        }
        val more = end < s.text.length
        val details = s.details.copy(outcome = if (more) ToolOutcome.PARTIAL else s.details.outcome,
            data = data, pagination = if (more) ToolPagination(hasMore = true, total = s.details.pagination?.total) else s.details.pagination)
        return ToolOutput(data.toString(), result = details)
    }
}
