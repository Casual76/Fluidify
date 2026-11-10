package dev.lelonio.square.bridge

import dev.lelonio.square.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BridgeStateTest {
    private val access = HistoryAccess(HistoryAccount("a"), 1)
    private val rows = (0..4).map { buildJsonObject { put("id", it) } }
    @Test fun pagesAreImmutableReplayableAndScopeBound() {
        val pager = SnapshotPager { 100L }
        val first = pager.page(access, "q", null, 2, rows)
        val second = pager.page(access, "q", first.cursor, 2)
        assertEquals(rows.subList(2,4), second.rows)
        assertEquals(second, pager.page(access, "q", first.cursor, 2))
        assertThrows(BridgeFailure::class.java) { pager.page(access, "other", first.cursor, 2) }
        assertThrows(BridgeFailure::class.java) { pager.page(access.copy(generation = 2), "q", first.cursor, 2) }
    }
    @Test fun cursorCannotCrossAccountOrSurviveProcessRestart() {
        val pager = SnapshotPager { 100L }
        val first = pager.page(access, "q", null, 2, rows)
        assertThrows(BridgeFailure::class.java) { pager.page(access.copy(account = HistoryAccount("b")), "q", first.cursor, 2) }
        assertThrows(BridgeFailure::class.java) { SnapshotPager().page(access, "q", first.cursor, 2) }
    }
    @Test fun cursorExpiresAndEmptyIsKnownEmpty() {
        var time = 0L; val pager = SnapshotPager { time }
        val first = pager.page(access, "q", null, 2, rows)
        time = 300001
        assertThrows(BridgeFailure::class.java) { pager.page(access, "q", first.cursor, 2) }
        val empty = pager.page(access, "empty", null, 10, emptyList())
        assertEquals(0, empty.total); assertNull(empty.cursor)
    }
    private class Store : OperationStore {
        val rows = mutableMapOf<String, JsonObject>()
        override fun get(key: String) = rows[key]
        override fun put(key: String, value: JsonObject) { rows[key] = value }
    }
    @Test fun duplicateAndProcessRecreationReadBackActualIdWithoutDispatch() = runBlocking {
        val store = Store(); var calls = 0
        suspend fun run(ledger: PlaylistOperations) = ledger.run("a:op", "payload", true, null,
            { calls++; assertNotNull(store.get("a:op")); "actualId" }, { it == "actualId" })
        assertEquals("actualId", run(PlaylistOperations(store)))
        assertEquals("actualId", run(PlaylistOperations(store)))
        assertEquals(1, calls)
    }
    @Test fun lostCreateResponseNeverAutomaticallyDispatchesAgain() = runBlocking {
        val store = Store(); var calls = 0
        suspend fun attempt() = PlaylistOperations(store).run("op", "p", true, null,
            { calls++; throw IllegalStateException("transport") }, { true })
        try { attempt(); fail() } catch (_: IllegalStateException) { }
        try { attempt(); fail() } catch (e: BridgeFailure) { assertEquals("write_uncertain", e.code) }
        assertEquals(1, calls)
    }
    @Test fun failedReadbackReconcilesButNeverReplaysMutation() = runBlocking {
        val store = Store(); var calls = 0; var visible = false
        suspend fun attempt() = PlaylistOperations(store).run("op", "p", false, "id",
            { calls++; "id" }, { visible })
        try { attempt(); fail() } catch (e: BridgeFailure) { assertEquals("readback_uncertain", e.code) }
        visible = true; assertEquals("id", attempt()); assertEquals(1, calls)
        try { PlaylistOperations(store).run("op", "different", false, "id", { fail(); "id" }, { true }); fail() }
        catch (e: BridgeFailure) { assertEquals("operation_conflict", e.code) }
    }
    @Test fun persistenceFailurePreventsDispatch() = runBlocking {
        var calls = 0
        val store = object : OperationStore {
            override fun get(key: String): JsonObject? = null
            override fun put(key: String, value: JsonObject) { error("disk full") }
        }
        try { PlaylistOperations(store).run("op", "p", true, null, { calls++; "id" }, { true }); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, calls)
    }
    @Test fun appendRequiresStableExactReadbackIncludingDuplicatesAndUnknownTracks() {
        val uris = listOf("spotify:track:a", "spotify:track:a")
        assertTrue(appendReadbackMatches("s", "s", 12, 10, uris, uris))
        assertFalse(appendReadbackMatches("s", "changed", 12, 10, uris, uris))
        assertFalse(appendReadbackMatches(null, null, 12, 10, uris, uris))
        assertFalse(appendReadbackMatches("s", "s", 13, 10, uris, uris))
        assertFalse(appendReadbackMatches("s", "s", 12, 10, uris, listOf(null, "spotify:track:a")))
    }
    @Test fun capturedRowsAreCopiedAndOversizeSnapshotsRejected() {
        val source = rows.toMutableList()
        val pager = SnapshotPager { 0L }
        val page = pager.page(access, "q", null, 2, source)
        source.clear()
        assertEquals(rows.subList(2, 4), pager.page(access, "q", page.cursor, 2).rows)
        assertThrows(BridgeFailure::class.java) { pager.page(access, "large", null, 100, List(20001) { rows[0] }) }
    }
}
