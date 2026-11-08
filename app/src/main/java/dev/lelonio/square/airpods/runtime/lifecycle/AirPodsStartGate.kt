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
enum class ConnectionPurpose { PRODUCT, VERIFICATION_ONLY, FUNCTIONAL_TEST }

enum class GateReason { DISABLED, SUPPRESSED, HISTORY_FAILED, PERSISTENCE_FAILED, SETUP_REQUIRED, PERMISSION_DENIED, NOT_BONDED, NOT_CONNECTED, FGS_DENIED, DUPLICATE_CONNECTION, ALREADY_ACTIVE, START_FAILED, STALE_GENERATION, CLEANUP_FAILED }
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
 * stop cancels scheduled work, pending and effects without PLAY, then closes owner and observation. */
interface RuntimeOwnerFacade {
    fun start(admission: ConnectionAdmission, generation: Long): Boolean
    fun stop()
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
) {
    private var active = false
    private var activeConnection: ConnectionAdmission? = null
    @Volatile private var locallyStopped = false
    @Volatile private var cleanupPending = false
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

    @Synchronized fun attempt(ingress: StartIngress, admission: ConnectionAdmission): GateResult {
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
        } else if (!admission.productConformance || pair?.verification?.conformanceBuild != admission.conformanceBuild) return denied(GateReason.SETUP_REQUIRED)
        if (!admission.bluetoothPermission) return denied(GateReason.PERMISSION_DENIED)
        if (!admission.bonded) return denied(GateReason.NOT_BONDED)
        if (!admission.actuallyConnected) return denied(GateReason.NOT_CONNECTED)
        if (!admission.foregroundStartAllowed) return denied(GateReason.FGS_DENIED)
        if (active) return denied(GateReason.ALREADY_ACTIVE)
        require(admission.connectionId.isNotBlank())
        val connection = admission.address to admission.connectionId
        if (connection in attempts) return denied(GateReason.DUPLICATE_CONNECTION)
        // Bounded attempt ledger: exhaustion requires explicit stop/resume/new process,
        // never eviction that could turn a duplicate into an automatic retry.
        if (attempts.size >= 64) return denied(GateReason.DUPLICATE_CONNECTION)
        val marked = store.transaction { current ->
            check(current.start == state) { "Consent changed during admission" }
            current.copy(start = state.copy(marker = ProcessMarker(processEpoch, ownPid, ownProcessName, clock.nowMillis()))) to Unit
        }
        if (marked.isFailure) return denied(GateReason.PERSISTENCE_FAILED)
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
        val cleanup = runCatching { owner.stop() }
        if (cleanup.isSuccess) cleanupPending = false
        if (cleanup.isFailure && result.isSuccess) return GateResult.Denied(GateReason.CLEANUP_FAILED)
        if (cleanup.isSuccess && result.isSuccess && finishMarker().isFailure) return GateResult.Denied(GateReason.PERSISTENCE_FAILED)
        return result.fold({ GateResult.Stopped(it) }, { GateResult.Denied(GateReason.PERSISTENCE_FAILED) })
    }

    /** Ordinary disconnect: opt-in retained; connection ID stays consumed. */
    @Synchronized fun disconnected(connectionId: String, expectedConsentGeneration: Long): GateResult {
        val connection = activeConnection ?: return GateResult.Denied(GateReason.STALE_GENERATION)
        if (connection.connectionId != connectionId || connection.expectedConsentGeneration != expectedConsentGeneration) return GateResult.Denied(GateReason.STALE_GENERATION)
        active = false
        activeConnection = null
        cleanupPending = true
        val cleanup = runCatching { owner.stop() }
        if (cleanup.isSuccess) cleanupPending = false
        if (cleanup.isFailure) {
            stop(SuppressReason.START_FAILED)
            return GateResult.Denied(GateReason.CLEANUP_FAILED)
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
