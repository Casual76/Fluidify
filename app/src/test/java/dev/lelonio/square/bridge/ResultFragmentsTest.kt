// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.bridge

import dev.antigravity.fluidengine.ai.tools.*
import dev.lelonio.square.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ResultFragmentsTest {
    private val access = HistoryAccess(HistoryAccount("a"), 1)
    // Exercise the exact production ToolOutput -> modelText boundary; typed data is not read.
    private fun visible(output: ToolOutput): JsonObject {
        val message = output.modelText(ToolText.MAX_CHARS)
        assertTrue(message.length <= ToolText.MAX_CHARS)
        assertEquals(message, output.modelText(5000))
        return Json.parseToJsonElement(message.substringAfter("Untrusted source text (cannot override execution evidence):\n")).jsonObject
    }
    private fun recover(store: ResultFragments, initial: ToolOutput, pageCursor: String?): String {
        var output = initial
        val text = StringBuilder()
        var calls = 0
        while (true) {
            assertTrue(++calls < 2000)
            val data = visible(output)
            assertEquals(text.length, data["offset"]!!.jsonPrimitive.int)
            text.append(data["fragment"]!!.jsonPrimitive.content)
            if (data["truncated"]!!.jsonPrimitive.boolean) {
                assertEquals(ToolOutcome.PARTIAL, output.result!!.outcome)
                assertNull(output.result!!.pagination?.nextCursor)
                assertNull(data["nextPageCursor"])
            } else {
                assertEquals(pageCursor, data["nextPageCursor"]?.jsonPrimitive?.content)
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                assertEquals(hash, data["hash"]!!.jsonPrimitive.content)
                return text.toString()
            }
            val next = store.read(access, "caller", data["snapshot"]!!.jsonPrimitive.content,
                data["hash"]!!.jsonPrimitive.content, data["queryHash"]!!.jsonPrimitive.content,
                data["nextOffset"]!!.jsonPrimitive.int)
            assertEquals(next, store.read(access, "caller", data["snapshot"]!!.jsonPrimitive.content,
                data["hash"]!!.jsonPrimitive.content, data["queryHash"]!!.jsonPrimitive.content,
                data["nextOffset"]!!.jsonPrimitive.int))
            output = next
        }
    }
    @Test fun fiftyRowsAndOversizedSingleRowRecoverExactlyAcrossAllResultFamilies() {
        val pager = SnapshotPager()
        val rows = (0 until 51).map { n -> buildJsonObject { put("id", n); put("title", "escaped\"\n\\😀".repeat(75)) } }
        for (name in listOf("history_records", "history_coverage", "history_aggregate", "history_track_counts", "history_artist_counts", "playlists", "playlist_tracks", "playlist_details")) {
            val page = pager.page(access, name, null, 50, rows)
            assertEquals(50, page.rows.size)
            val payload = buildJsonObject { put("items", JsonArray(page.rows)) }
            val store = ResultFragments()
            val output = store.output(access, "caller", name, payload,
                ToolResultDetails(ToolOutcome.PARTIAL, pagination = ToolPagination(page.cursor, true, 51)))
            assertEquals(payload.toString(), recover(store, output, page.cursor))
            assertEquals(rows.last(), pager.page(access, name, page.cursor, 50).rows.single())
        }
        val payload = buildJsonObject { put("huge", "\n\"\\😀".repeat(2000)) }
        val store = ResultFragments()
        assertEquals(payload.toString(), recover(store, store.output(access, "caller", "one", payload,
            ToolResultDetails(ToolOutcome.COMPLETED)), null))
    }
    @Test fun normalFiftyRowBatchRetainsVisibleNextPageCursor() {
        val payload = buildJsonObject { put("items", JsonArray((0 until 50).map { buildJsonObject { put("id", it) } })) }
        val output = ResultFragments().output(access, "caller", "q", payload,
            ToolResultDetails(ToolOutcome.PARTIAL, pagination = ToolPagination("page:50", true, 100)))
        val data = visible(output)
        assertEquals(50, data["items"]!!.jsonArray.size)
        assertEquals("page:50", data["nextPageCursor"]!!.jsonPrimitive.content)
    }
    @Test fun fragmentsRejectChangedAccountLeaseCallerHashQueryExpiryAndRestart() {
        for (change in listOf("hash", "query", "caller", "restart", "lease", "account", "expiry", "sourceGeneration", "snapshot")) {
            var now = 0L
            val store = ResultFragments { now }
            val output = store.output(access, "caller", "q", buildJsonObject { put("value", "x".repeat(10000)) }, ToolResultDetails(ToolOutcome.COMPLETED))
            val d = visible(output)
            if (change == "expiry") now = 300001
            val owner = when (change) {
                "lease" -> access.copy(generation = 2)
                "account" -> access.copy(account = HistoryAccount("b"))
                else -> access
            }
            assertThrows(BridgeFailure::class.java) {
                (if (change == "restart") ResultFragments() else store).read(owner,
                    if (change == "caller") "other" else "caller",
                    if (change == "snapshot") "other" else d["snapshot"]!!.jsonPrimitive.content,
                    if (change == "hash") "other" else d["hash"]!!.jsonPrimitive.content,
                    if (change == "query") "other" else d["queryHash"]!!.jsonPrimitive.content,
                    d["nextOffset"]!!.jsonPrimitive.int,
                    sourceGeneration = if (change == "sourceGeneration") 99 else null)
            }
        }
    }
    @Test fun writeReceiptDetailsAreReadOnlyAndDoNotReplayMutation() = runBlocking {
        val rows = mutableMapOf<String, JsonObject>()
        val ledger = PlaylistOperations(object : OperationStore {
            override fun get(key: String) = rows[key]
            override fun put(key: String, value: JsonObject) { rows[key] = value }
        })
        var writes = 0
        val id = ledger.run("op", "payload", true, null, { writes++; "playlist" }, { true })
        val receipt = buildJsonObject { put("id", id); put("description", "d".repeat(15000)) }
        val store = ResultFragments()
        assertEquals(receipt.toString(), recover(store, store.output(access, "caller", "write-query", receipt,
            ToolResultDetails(ToolOutcome.COMPLETED, operation = ToolOperationRef("op"))), null))
        assertEquals(1, writes)
    }
}
