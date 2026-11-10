package dev.lelonio.square.bridge

import android.content.Context
import dev.lelonio.square.data.HistoryAccess
import dev.lelonio.square.data.SpotifyNetworkPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

internal class BridgeFailure(val code: String) : IllegalStateException(code)

/** Immutable bounded snapshots: process death, revocation and expiry invalidate cursors. */
internal class SnapshotPager(private val now: () -> Long = System::currentTimeMillis) {
    private data class Snapshot(val access: HistoryAccess, val query: String, val rows: List<JsonObject>, val at: Long,
        val network: SpotifyNetworkPolicy.Permit?, val sourceGeneration: Long?)
    private val snapshots = linkedMapOf<String, Snapshot>()
    data class Page(val rows: List<JsonObject>, val cursor: String?, val total: Int, val at: Long)
    /** Resolve the original authority BEFORE token acquisition, including via source_read. */
    @Synchronized fun network(access: HistoryAccess, query: String, cursor: String, sourceGeneration: Long): SpotifyNetworkPolicy.Permit? {
        val parts = cursor.split(":")
        if (parts.size != 2) throw BridgeFailure("invalid_cursor")
        val offset = parts[1].toIntOrNull() ?: throw BridgeFailure("invalid_cursor")
        val s = snapshots[parts[0]]?.takeIf { now() - it.at <= 300_000 } ?: throw BridgeFailure("expired_cursor")
        if (s.access != access || s.query != query || offset !in 0..s.rows.size) throw BridgeFailure("invalid_cursor")
        if (s.sourceGeneration != sourceGeneration) throw BridgeFailure("account_revoked")
        s.network?.check()
        return s.network
    }
    @Synchronized fun page(access: HistoryAccess, query: String, cursor: String?, limit: Int,
        rows: List<JsonObject>? = null, network: SpotifyNetworkPolicy.Permit? = null, sourceGeneration: Long? = null): Page {
        network?.check()
        require(limit in 1..100)
        snapshots.entries.removeAll { now() - it.value.at > 300_000 || it.value.access != access }
        val id: String
        val offset: Int
        if (cursor == null) {
            if (snapshots.size >= 8) snapshots.remove(snapshots.keys.first())
            id = UUID.randomUUID().toString(); offset = 0
            val captured = rows ?: error("Missing snapshot")
            if (captured.size > 20_000) throw BridgeFailure("snapshot_limit")
            snapshots[id] = Snapshot(access, query, captured.toList(), now(), network, sourceGeneration)
        } else {
            val pieces = cursor.split(":")
            if (pieces.size != 2) throw BridgeFailure("invalid_cursor")
            id = pieces[0]; offset = pieces[1].toIntOrNull() ?: throw BridgeFailure("invalid_cursor")
        }
        val s = snapshots[id] ?: throw BridgeFailure("expired_cursor")
        if (s.access != access || s.query != query || offset !in 0..s.rows.size) throw BridgeFailure("invalid_cursor")
        if (s.sourceGeneration != sourceGeneration) throw BridgeFailure("account_revoked")
        val end = minOf(offset + limit, s.rows.size)
        // A cursor retains its original lease, even when its caller has a fresh online lease.
        val page = Page(s.rows.subList(offset, end), if (end < s.rows.size) "$id:$end" else null, s.rows.size, s.at)
        return s.network?.admit { page } ?: page
    }
}

internal interface OperationStore {
    fun get(key: String): JsonObject?
    fun put(key: String, value: JsonObject)
}
internal class DurableOperationStore(context: Context) : OperationStore {
    private val prefs = context.getSharedPreferences("aria-playlist-operations", Context.MODE_PRIVATE)
    override fun get(key: String) = prefs.getString(key, null)?.let { Json.parseToJsonElement(it).jsonObject }
    override fun put(key: String, value: JsonObject) {
        check(prefs.edit().putString(key, value.toString()).commit()) { "operation_persistence_failed" }
    }
}

/** Persist before dispatch. A prior claim only reconciles; it never dispatches again. */
internal class PlaylistOperations(private val store: OperationStore) {
    private val mutex = Mutex()
    /** Local evidence only; an offline rejection must not imply an earlier durable claim was undone. */
    suspend fun claimed(key: String, fingerprint: String): Boolean = mutex.withLock {
        val old = store.get(key) ?: return@withLock false
        if (old["fingerprint"]?.jsonPrimitive?.content != fingerprint) throw BridgeFailure("operation_conflict")
        true
    }
    suspend fun run(key: String, fingerprint: String, create: Boolean, target: String?,
        dispatch: suspend () -> String, readback: suspend (String) -> Boolean): String =
        runOwned(key, fingerprint, create, target, null, dispatch, readback)

    suspend fun run(key: String, fingerprint: String, create: Boolean, target: String?,
        operation: BridgeNetworkOperation, dispatch: suspend () -> String, readback: suspend (String) -> Boolean): String =
        runOwned(key, fingerprint, create, target, operation, dispatch, readback)

    private suspend fun runOwned(key: String, fingerprint: String, create: Boolean, target: String?,
        operation: BridgeNetworkOperation?, dispatch: suspend () -> String, readback: suspend (String) -> Boolean): String = mutex.withLock {
        operation?.check() // A revoked FIFO waiter cannot claim, refresh, dispatch or reconcile.
        val old = store.get(key)
        if (old != null && old["fingerprint"]?.jsonPrimitive?.content != fingerprint) throw BridgeFailure("operation_conflict")
        fun save(id: String?) = store.put(key, buildJsonObject {
            put("fingerprint", fingerprint); id?.let { put("id", it) }
        })
        if (old != null) operation?.reconciling()
        var id = old?.get("id")?.jsonPrimitive?.content
        if (old == null) {
            operation?.check()
            // For rename the target is known even if transport dies before its response.
            save(if (create) null else target)
            id = dispatch()
            if (id.isBlank()) throw BridgeFailure("write_uncertain")
            // Keep an admitted mutation's receipt across network revocation for the same owner.
            // This is local durable reconciliation, never publication or renewed network authority.
            operation?.checkAccount()
            save(id)
        }
        operation?.check()
        val actual = id ?: throw BridgeFailure("write_uncertain")
        if (!readback(actual)) throw BridgeFailure("readback_uncertain")
        operation?.publish { actual } ?: actual
    }
}

/** Exact postcondition; unknown/relinked/missing tracks cannot certify the append. */
internal fun appendReadbackMatches(before: String?, after: String?, total: Int?, baseCount: Int,
    expected: List<String>, actual: List<String?>): Boolean =
    before != null && before == after && total == baseCount + expected.size && actual == expected

/** Legacy argument may confirm, but can never supply or replace transport identity. */
internal fun durableWriteIdentity(executionId: String?, requestId: String, legacy: JsonElement?): String {
    val id = executionId ?: requestId
    if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw BridgeFailure("operation_identity_required")
    if (executionId != null && executionId != requestId) throw BridgeFailure("operation_identity_conflict")
    if (legacy != null && (legacy !is JsonPrimitive || !legacy.isString || legacy.content != id))
        throw BridgeFailure("operation_identity_conflict")
    return id
}

internal fun currentAccount(access: HistoryAccess, requested: String?, discovery: Boolean): String {
    if ((!discovery && requested == null) || (requested != null && requested != access.account.id))
        throw BridgeFailure("account_mismatch")
    return access.account.id
}
