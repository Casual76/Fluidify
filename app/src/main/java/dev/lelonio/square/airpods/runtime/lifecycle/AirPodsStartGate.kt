// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.lifecycle

import dev.lelonio.square.airpods.runtime.storage.*

fun interface RuntimeClock { fun nowMillis(): Long }
enum class ExitCause { USER_REQUESTED, RELIABLE_OTHER, UNKNOWN }
data class OwnProcessExit(val key: ExitKey, val cause: ExitCause)
sealed class ExitHistory {
    /** Provider must query OWN package and map Android reasons without guessing. */
    data class Available(val records: List<OwnProcessExit>) : ExitHistory()
    data object Unavailable : ExitHistory()
    data object QueryFailed : ExitHistory()
}
fun interface ExitHistoryProvider { fun query(): ExitHistory }
enum class StartIngress { EXPLICIT_ENABLE, EXPLICIT_RESUME, APP_OPEN, APPLICATION, RECEIVER, COMPANION, RESTORE, TILE_LISTENING, NLS_CALLBACK, TILE_USER_ACTION }
enum class ConnectionPurpose { PRODUCT, VERIFICATION_ONLY, FUNCTIONAL_TEST, QUALIFICATION_TEST }

enum class GateReason { DISABLED, SUPPRESSED, HISTORY_FAILED, PERSISTENCE_FAILED, SETUP_REQUIRED, PERMISSION_DENIED, NOT_BONDED, NOT_CONNECTED, FGS_DENIED, DUPLICATE_CONNECTION, ALREADY_ACTIVE, START_FAILED, STALE_GENERATION, CLEANUP_FAILED,
    /** The previous owner is revoked and suppression is durable, but a cleanup confirmation from
     * another thread has not arrived. Nothing failed; no new owner starts until it settles. */
    CLEANUP_PENDING }
sealed class GateResult {
    data class Started(val generation: Long) : GateResult()
    data class Resumed(val generation: Long) : GateResult()
    data class Stopped(val generation: Long) : GateResult()
    data class Denied(val reason: GateReason) : GateResult()
}
data class ConnectionAdmission(
    val address: PairAddress,
    /** Stable for the REAL connection lifetime, shared across receiver/companion/UI.
     * A callback invocation UUID is not a connection ID. */
    val connectionId: String,
    val bonded: Boolean,
    val bluetoothPermission: Boolean,
    val actuallyConnected: Boolean,
    val foregroundStartAllowed: Boolean,
    val conformanceBuild: String,
    val expectedConsentGeneration: Long,
    val purpose: ConnectionPurpose = ConnectionPurpose.PRODUCT,
    val explicitSetupVisible: Boolean = false,
    val productConformance: Boolean = false,
)
/** Called under gate lock AFTER durable marker. Implementations must be short, nonblocking
 * scheduling facades, not synchronous Bluetooth I/O. No other entry may start FGS/socket.
 * stop cancels scheduled work, pending and effects without PLAY, then closes owner and observation.
 * Main-thread readers take this same gate lock, so neither stop nor cleanup may wait for the main
 * thread (or any thread that can need the gate). stop revokes every old authority on the calling
 * thread; what it could only queue elsewhere is reported by cleanup, never awaited here. */
interface RuntimeOwnerFacade {
    fun start(admission: ConnectionAdmission, generation: Long): Boolean
    fun stop()
    /** Asked under the gate lock after stop() returned normally, and by settleCleanup(). */
    fun cleanup(): OwnerCleanup = OwnerCleanup.COMPLETE
}
enum class OwnerCleanup {
    COMPLETE,
    /** Authority is already revoked; only confirmations from another thread are missing. */
    OUTSTANDING,
    /** A confirmation arrived as a failure. Retried only by an explicit stop or consent. */
    FAILED,
}

class AirPodsStartGate(
    private val store: AirPodsRuntimeStore,
    private val history: ExitHistoryProvider,
    private val clock: RuntimeClock,
    private val processEpoch: String,
    private val ownPid: Int,
    private val ownProcessName: String,
    private val apiLevel: Int,
    private val owner: RuntimeOwnerFacade,
    // Debug owner validates a private process-local permit. Production default denies.
    private val testEntitled: (ConnectionAdmission) -> Boolean = { false },
    private val qualificationEntitled: (ConnectionAdmission) -> Boolean = { false },
) {
    private var active = false
    private var activeConnection: ConnectionAdmission? = null
    @Volatile private var locallyStopped = false
    @Volatile private var cleanupPending = false
    /** A cleanup whose only missing part is a confirmation from another thread. [disconnect]: it
     * began as an ordinary disconnect, so a late failure escalates like a synchronous one.
     * [persisted]: its suppression is durable, so the marker may be concluded when it settles. */
    private class Outstanding(val disconnect: Boolean, val persisted: Boolean)
    private var outstanding: Outstanding? = null
    /** Answer of the last settled cleanup, for a caller that waited off-gate and arrives late. */
    private var settlement: GateResult? = null
    /** Lock-free observation for a worker deciding whether to call settleCleanup(). Not an authorization. */
    val cleanupUnconfirmed: Boolean get() = cleanupPending
    private val attempts = LinkedHashSet<Pair<PairAddress, String>>()

    init { require(processEpoch.isNotBlank() && ownPid > 0 && ownProcessName.isNotBlank()) }

    /** Additive C4 check for ordinary platform operations, including disconnected audio.
     * No acquisition, marker, consent, attempt consumption or owner callback. Does not take
     * the gate monitor: cold profile callbacks must not invert gate -> owner cleanup locks.
     * Store transactions serialize reconciliation against stop/consent writes. */
    fun platformAuthorization(pair: PairAddress, expectedConsent: Long): GateReason? {
        val exits = query()
        val data = store.transaction { before ->
            val after = before.copy(start = reconcile(before.start, exits))
            after to after
        }.getOrElse { return GateReason.PERSISTENCE_FAILED }
        return when {
            exits is ExitHistory.QueryFailed || (apiLevel >= 30 && exits is ExitHistory.Unavailable) -> GateReason.HISTORY_FAILED
            locallyStopped || cleanupPending || data.start.suppressUntilExplicitResume -> GateReason.SUPPRESSED
            !data.start.enabled -> GateReason.DISABLED
            data.start.consentGeneration != expectedConsent -> GateReason.STALE_GENERATION
            data.selectedPair != pair -> GateReason.SETUP_REQUIRED
            else -> null
        }
    }

    /** Pure app opening/listening/binding is never consent. Explicit UI enable/resume
     * is handled separately; every subsequent acquisition still calls attempt(). */
    @Synchronized fun explicitConsent(ingress: StartIngress): GateResult {
        require(ingress == StartIngress.EXPLICIT_ENABLE || ingress == StartIngress.EXPLICIT_RESUME)
        val exits = query()
        if (exits is ExitHistory.QueryFailed || (apiLevel >= 30 && exits is ExitHistory.Unavailable)) return GateResult.Denied(GateReason.HISTORY_FAILED)
        if (outstanding != null) {
            // The confirmation may have arrived. While it is merely missing there is nothing to
            // retry: neither a stop nor a consent is written, and the caller may ask again.
            val settled = settleCleanup()
            if (settled == GateResult.Denied(GateReason.CLEANUP_PENDING)) return settled
        }
        if (active || cleanupPending) {
            val stopped = stop()
            if (stopped is GateResult.Denied) return stopped
        }
        val records = ownRecords(exits).map { it.key }
        val result = store.transaction { data ->
            val generation = Math.addExact(data.start.consentGeneration, 1)
            val acknowledged = bounded(data.start.explicitlyAcknowledged + records)
            val consumed = bounded(data.start.consumedExits + records)
            data.copy(start = data.start.copy(enabled = true, suppressUntilExplicitResume = false,
                suppressReason = null, consentGeneration = generation,
                explicitlyAcknowledged = acknowledged, consumedExits = consumed,
                lastExplicitlyAcknowledged = records.maxByOrNull { it.timestampMillis } ?: data.start.lastExplicitlyAcknowledged,
                ledgerTruncated = data.start.ledgerTruncated || records.size > AirPodsRuntimeData.EXIT_LEDGER_LIMIT,
                // Explicit acknowledgement retires an old uncertain marker atomically.
                marker = data.start.marker?.copy(clean = true))) to generation
        }
        return result.fold({ generation ->
            locallyStopped = false
            attempts.clear() // manual consent permits a fresh attempt on the same connection
            GateResult.Resumed(generation)
        }, { GateResult.Denied(GateReason.PERSISTENCE_FAILED) })
    }

    /** [acquisitionCurrent] only retires a queued acquisition; it never grants consent or
     * bypasses a gate check. Rechecked across durable marker I/O before any owner callback. */
    @Synchronized fun attempt(ingress: StartIngress, admission: ConnectionAdmission,
        acquisitionCurrent: () -> Boolean = { true }): GateResult {
        // The argument documents ALL ingress callers; it conveys no implicit consent.
        @Suppress("UNUSED_VARIABLE") val entry = ingress
        val exits = query()
        val reconciled = store.transaction { data ->
            val state = reconcile(data.start, exits)
            data.copy(start = state) to data.copy(start = state)
        }.getOrElse { return GateResult.Denied(GateReason.PERSISTENCE_FAILED) }
        if (exits is ExitHistory.QueryFailed || (apiLevel >= 30 && exits is ExitHistory.Unavailable)) return GateResult.Denied(GateReason.HISTORY_FAILED)
        val state = reconciled.start
        fun denied(reason: GateReason) = GateResult.Denied(reason)
        if (locallyStopped || state.suppressUntilExplicitResume) return denied(GateReason.SUPPRESSED)
        if (!state.enabled) return denied(GateReason.DISABLED)
        if (admission.expectedConsentGeneration != state.consentGeneration) return denied(GateReason.STALE_GENERATION)
        val pair = reconciled.pairs[admission.address]
        if (reconciled.selectedPair != admission.address || admission.conformanceBuild.isBlank()) return denied(GateReason.SETUP_REQUIRED)
        if (admission.purpose == ConnectionPurpose.VERIFICATION_ONLY) {
            if (ingress != StartIngress.EXPLICIT_ENABLE && ingress != StartIngress.EXPLICIT_RESUME) return denied(GateReason.SETUP_REQUIRED)
            if (!admission.explicitSetupVisible) return denied(GateReason.SETUP_REQUIRED)
        } else if (admission.purpose == ConnectionPurpose.FUNCTIONAL_TEST) {
            if (ingress !in setOf(StartIngress.EXPLICIT_ENABLE, StartIngress.EXPLICIT_RESUME) ||
                !admission.explicitSetupVisible || admission.productConformance || !testEntitled(admission)) return denied(GateReason.SETUP_REQUIRED)
        } else if (admission.purpose == ConnectionPurpose.QUALIFICATION_TEST) {
            if (ingress !in setOf(StartIngress.EXPLICIT_ENABLE, StartIngress.EXPLICIT_RESUME, StartIngress.RECEIVER, StartIngress.COMPANION) ||
                admission.productConformance || !qualificationEntitled(admission)) return denied(GateReason.SETUP_REQUIRED)
        } else if (!admission.productConformance || pair?.verification?.conformanceBuild != admission.conformanceBuild) return denied(GateReason.SETUP_REQUIRED)
        if (!admission.bluetoothPermission) return denied(GateReason.PERMISSION_DENIED)
        if (!admission.bonded) return denied(GateReason.NOT_BONDED)
        if (!admission.actuallyConnected) return denied(GateReason.NOT_CONNECTED)
        if (!admission.foregroundStartAllowed) return denied(GateReason.FGS_DENIED)
        if (active) return denied(GateReason.ALREADY_ACTIVE)
        // An ordinary disconnect keeps the opt-in, so only this check stands between a revoked
        // owner whose cleanup is unconfirmed and a second one. The connection ID is not consumed.
        if (cleanupPending) return denied(GateReason.CLEANUP_PENDING)
        require(admission.connectionId.isNotBlank())
        val connection = admission.address to admission.connectionId
        if (connection in attempts) return denied(GateReason.DUPLICATE_CONNECTION)
        // Bounded attempt ledger: exhaustion requires explicit stop/resume/new process,
        // never eviction that could turn a duplicate into an automatic retry.
        if (attempts.size >= 64) return denied(GateReason.DUPLICATE_CONNECTION)
        if (!runCatching(acquisitionCurrent).getOrDefault(false)) return denied(GateReason.FGS_DENIED)
        val marked = store.transaction { current ->
            check(current.start == state) { "Consent changed during admission" }
            current.copy(start = state.copy(marker = ProcessMarker(processEpoch, ownPid, ownProcessName, clock.nowMillis()))) to Unit
        }
        if (marked.isFailure) return denied(GateReason.PERSISTENCE_FAILED)
        // A foreground opening can retire while the durable marker is being written. No
        // owner or connection attempt exists yet: conclude that marker without suppression,
        // and leave the opt-in available for a later real opening. Failure stays fail-closed.
        if (!runCatching(acquisitionCurrent).getOrDefault(false)) {
            if (finishMarker().isFailure) { locallyStopped = true; return denied(GateReason.PERSISTENCE_FAILED) }
            return denied(GateReason.FGS_DENIED)
        }
        attempts.add(connection)
        active = true
        activeConnection = admission
        val started = runCatching { owner.start(admission, state.consentGeneration) }.getOrDefault(false)
        if (!started) {
            stop(SuppressReason.START_FAILED)
            return denied(GateReason.START_FAILED)
        }
        return GateResult.Started(state.consentGeneration)
    }

    /** Persist suppression BEFORE cleanup. Even failed persistence latches in this process.
     * Safe to call repeatedly. No automatic retry and no media commands in this policy. */
    @Synchronized fun stop(reason: SuppressReason = SuppressReason.USER_STOP): GateResult {
        locallyStopped = true
        val result = store.transaction { data ->
            val changed = !data.start.suppressUntilExplicitResume || data.start.suppressReason != reason
            val generation = if (changed) Math.addExact(data.start.consentGeneration, 1) else data.start.consentGeneration
            val unpair = reason == SuppressReason.UNPAIRED
            val next = data.copy(start = data.start.copy(
                enabled = if (reason == SuppressReason.DISABLED || unpair) false else data.start.enabled,
                suppressUntilExplicitResume = true, suppressReason = reason, consentGeneration = generation),
                pairs = if (unpair) data.selectedPair?.let { data.pairs - it } ?: data.pairs else data.pairs,
                selectedPair = if (unpair) null else data.selectedPair)
            next to generation
        }
        active = false
        activeConnection = null
        cleanupPending = true
        outstanding = null
        settlement = null
        val cleanup = ownerCleanup()
        if (cleanup == OwnerCleanup.COMPLETE) cleanupPending = false
        // The marker stays unconcluded and cleanupPending latched until settleCleanup().
        if (cleanup == OwnerCleanup.OUTSTANDING) outstanding = Outstanding(disconnect = false, persisted = result.isSuccess)
        if (cleanup == OwnerCleanup.FAILED && result.isSuccess) return GateResult.Denied(GateReason.CLEANUP_FAILED)
        if (cleanup == OwnerCleanup.OUTSTANDING && result.isSuccess) return GateResult.Denied(GateReason.CLEANUP_PENDING)
        if (cleanup == OwnerCleanup.COMPLETE && result.isSuccess && finishMarker().isFailure) return GateResult.Denied(GateReason.PERSISTENCE_FAILED)
        return result.fold({ GateResult.Stopped(it) }, { GateResult.Denied(GateReason.PERSISTENCE_FAILED) })
    }

    private fun ownerCleanup(): OwnerCleanup = runCatching { owner.stop(); owner.cleanup() }.getOrDefault(OwnerCleanup.FAILED)

    /** Concludes a cleanup that stop()/disconnected() left with confirmations outstanding. The
     * owner calls it when they arrive; a caller that waited off-gate calls it for the final
     * answer. Idempotent. It never calls owner.stop() for a merely outstanding cleanup or for
     * the late failure of an explicit stop: those stay latched until an explicit stop/consent.
     * The late failure of an ordinary disconnect escalates exactly like a synchronous one, once. */
    @Synchronized fun settleCleanup(): GateResult {
        if (!cleanupPending) return settlement ?: store.snapshot().fold(
            { GateResult.Stopped(it.start.consentGeneration) }, { GateResult.Denied(GateReason.PERSISTENCE_FAILED) })
        val pending = outstanding ?: return GateResult.Denied(GateReason.CLEANUP_FAILED)
        return when (runCatching { owner.cleanup() }.getOrDefault(OwnerCleanup.FAILED)) {
            OwnerCleanup.OUTSTANDING -> GateResult.Denied(GateReason.CLEANUP_PENDING)
            OwnerCleanup.FAILED -> {
                outstanding = null
                if (pending.disconnect) stop(SuppressReason.START_FAILED)
                GateResult.Denied(GateReason.CLEANUP_FAILED)
            }
            OwnerCleanup.COMPLETE -> {
                outstanding = null
                cleanupPending = false
                // A suppression that could not be written keeps its unconcluded marker: the next
                // process must still find the uncertainty this process only latched in memory.
                val settled: GateResult = if (!pending.persisted) GateResult.Denied(GateReason.PERSISTENCE_FAILED)
                    else finishMarker().fold({ GateResult.Stopped(it) }, {
                        if (pending.disconnect) locallyStopped = true
                        GateResult.Denied(GateReason.PERSISTENCE_FAILED)
                    })
                settlement = settled
                settled
            }
        }
    }

    /** Ordinary disconnect: opt-in retained; connection ID stays consumed. */
    @Synchronized fun disconnected(connectionId: String, expectedConsentGeneration: Long): GateResult {
        val connection = activeConnection ?: return GateResult.Denied(GateReason.STALE_GENERATION)
        if (connection.connectionId != connectionId || connection.expectedConsentGeneration != expectedConsentGeneration) return GateResult.Denied(GateReason.STALE_GENERATION)
        active = false
        activeConnection = null
        cleanupPending = true
        outstanding = null
        settlement = null
        val cleanup = ownerCleanup()
        if (cleanup == OwnerCleanup.COMPLETE) cleanupPending = false
        if (cleanup == OwnerCleanup.FAILED) {
            stop(SuppressReason.START_FAILED)
            return GateResult.Denied(GateReason.CLEANUP_FAILED)
        }
        if (cleanup == OwnerCleanup.OUTSTANDING) {
            // Opt-in retained and nothing suppressed: the outcome is decided by settleCleanup().
            outstanding = Outstanding(disconnect = true, persisted = true)
            return GateResult.Denied(GateReason.CLEANUP_PENDING)
        }
        val result = finishMarker()
        return result.fold({ GateResult.Stopped(it) }, { locallyStopped = true; GateResult.Denied(GateReason.PERSISTENCE_FAILED) })
    }

    private fun finishMarker() = store.transaction { data ->
        data.copy(start = data.start.copy(marker = data.start.marker?.copy(clean = true))) to data.start.consentGeneration
    }

    private fun query(): ExitHistory = if (apiLevel < 30) ExitHistory.Unavailable else runCatching { history.query() }.getOrDefault(ExitHistory.QueryFailed)
    private fun ownRecords(history: ExitHistory) = (history as? ExitHistory.Available)?.records.orEmpty().filter { it.key.processName == ownProcessName }
    private fun bounded(keys: List<ExitKey>) = keys.distinct().sortedWith(compareBy<ExitKey> { it.timestampMillis }.thenBy { it.pid }.thenBy { it.processName }.thenBy { it.reason }).takeLast(AirPodsRuntimeData.EXIT_LEDGER_LIMIT)
    private fun reconcile(state: StartState, history: ExitHistory): StartState {
        val records = ownRecords(history)
        val freshStops = records.filter { it.cause == ExitCause.USER_REQUESTED && it.key !in state.consumedExits && it.key !in state.explicitlyAcknowledged }
        val oldMarker = state.marker?.takeIf { !it.clean && it.epoch != processEpoch }
        val explained = oldMarker != null && records.any {
            it.key.pid == oldMarker.pid && it.key.processName == oldMarker.processName &&
                it.key.timestampMillis >= oldMarker.startedAtMillis && it.cause == ExitCause.RELIABLE_OTHER
        }
        val uncertain = oldMarker != null && !explained
        val reason = when { freshStops.isNotEmpty() -> SuppressReason.USER_REQUESTED_EXIT; uncertain -> SuppressReason.UNCLEAN_PROCESS; else -> null }
        val keys = state.consumedExits + freshStops.map { it.key }
        return state.copy(suppressUntilExplicitResume = state.suppressUntilExplicitResume || reason != null,
            suppressReason = reason ?: state.suppressReason, consumedExits = bounded(keys),
            ledgerTruncated = state.ledgerTruncated || keys.distinct().size > AirPodsRuntimeData.EXIT_LEDGER_LIMIT,
            marker = if (explained) oldMarker?.copy(clean = true) else state.marker)
    }
}
