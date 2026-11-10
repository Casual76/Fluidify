package dev.lelonio.square.data

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The only authority. Native mirrors acknowledge the host revision synchronously. */
object SpotifyNetworkPolicy {
    class Offline : IOException("Spotify manual offline mode or revoked lease")
    data class Snapshot(val revision: Long, val manual: Boolean)
    private val gate = Any()
    // Lock order: transitions -> gate. Never invoke consumers while holding gate.
    private val transitions = Any()
    private var current = Snapshot(0, false)
    private var mirrorFailed = false
    private var applyingRevision: Long? = null
    private val _manual = MutableStateFlow(false)
    val manual: StateFlow<Boolean> = _manual.asStateFlow()
    private val observers = mutableSetOf<(Boolean) -> Unit>()
    private val mirrors = mutableSetOf<(Snapshot) -> Unit>()
    private val nativeScopes = mutableSetOf<(Long) -> AutoCloseable>()
    fun snapshot(): Snapshot = synchronized(gate) { current }
    val revision: Long get() = snapshot().revision
    fun isCurrent(revision: Long): Boolean = synchronized(gate) {
        applyingRevision == null && !mirrorFailed && !current.manual && current.revision == revision
    }
    /** Close host admission first, apply all native mirrors, then notify ordinary observers. */
    fun setManual(value: Boolean) = synchronized(transitions) transition@{
        val next = synchronized(gate) {
            if (current.manual == value) null
            else Snapshot(current.revision + 1, value).also { current = it; applyingRevision = it.revision }
        } ?: return@transition
        var failure: Throwable? = null
        mirrors.toList().forEach { mirror ->
            try { mirror(next) } catch (error: Throwable) { failure = failure ?: error }
        }
        val listeners = synchronized(gate) {
            mirrorFailed = failure != null
            applyingRevision = null
            _manual.value = value
            observers.toList()
        }
        listeners.forEach { observer ->
            try { observer(value) } catch (error: Throwable) { failure = failure ?: error }
        }
        failure?.let { throw IllegalStateException("Spotify network barrier failed", it) }
    }
    /** Replay and registration serialize with transitions. Callback only applies native policy. */
    fun bindNative(enter: ((Long) -> AutoCloseable)? = null, mirror: (Snapshot) -> Unit): AutoCloseable = synchronized(transitions) {
        mirror(snapshot())
        mirrors += mirror
        if (enter != null) synchronized(gate) { nativeScopes += enter }
        AutoCloseable { synchronized(transitions) {
            mirrors -= mirror
            if (enter != null) synchronized(gate) { nativeScopes -= enter }
            Unit
        } }
    }
    fun checkAllowed() { capture() }
    fun capture(): Permit = synchronized(gate) {
        if (applyingRevision != null || current.manual || mirrorFailed) throw Offline()
        Permit(current.revision)
    }
    suspend fun <T> io(block: () -> T): T {
        val permit = capture()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { permit.dispatch(block) }
    }
    class Permit internal constructor(val revision: Long) {
        fun check() { if (!isCurrent(revision)) throw Offline() }
        /** Short dispatch/publication admission, serialized with revocation. Never block for IO here. */
        fun <T> admit(block: () -> T): T = synchronized(gate) { check(); block() }
        fun <T> dispatch(block: () -> T): T {
            check()
            val scopes = synchronized(gate) { nativeScopes.toList() }
            val opened = mutableListOf<AutoCloseable>()
            try {
                scopes.forEach { opened += it(revision) }
                check()
                val answer = block()
                check()
                return answer
            } finally { opened.asReversed().forEach { it.close() } }
        }
    }
    fun observe(observer: (Boolean) -> Unit): AutoCloseable {
        synchronized(gate) { observers += observer }
        return AutoCloseable { synchronized(gate) { observers -= observer; Unit } }
    }
}
