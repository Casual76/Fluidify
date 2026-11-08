// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.storage

/** Private pairing address. Deliberately redacted from logs, including nested data classes. */
class PairAddress(value: String) {
    val value = value.uppercase(java.util.Locale.ROOT)
    init { require(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(this.value)) }
    override fun equals(other: Any?) = other is PairAddress && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = "PairAddress(<private>)"
}

enum class QuickMode { OFF, TRANSPARENCY, ADAPTIVE }
enum class SuppressReason { USER_STOP, DISABLED, REVOKED, UNPAIRED, USER_REQUESTED_EXIT, UNCLEAN_PROCESS, START_FAILED }
data class ExitKey(val timestampMillis: Long, val pid: Int, val processName: String, val reason: Int)
data class ProcessMarker(val epoch: String, val pid: Int, val processName: String, val startedAtMillis: Long, val clean: Boolean = false)
data class StartState(
    val enabled: Boolean = false,
    val suppressUntilExplicitResume: Boolean = false,
    val suppressReason: SuppressReason? = null,
    val consentGeneration: Long = 0,
    val marker: ProcessMarker? = null,
    val consumedExits: List<ExitKey> = emptyList(),
    val explicitlyAcknowledged: List<ExitKey> = emptyList(),
    val lastExplicitlyAcknowledged: ExitKey? = null,
    // Events at/before this watermark which have fallen out of the bounded ledger are
    // ambiguous, never silently acknowledged. The gate conservatively asks for resume.
    val ledgerTruncated: Boolean = false,
)

/** Local host intent only. These fields are never hardware RX and never replayed on connect. */
data class HostSettings(
    val automationsEnabled: Boolean = false,
    val otherAppsEnabled: Boolean = false,
    val fluidifyBackgroundEnabled: Boolean = false,
    val earPause: Boolean = false,
    val earResume: Boolean = false,
    val conversationDuck: Boolean = false,
    val conversationPause: Boolean = false,
    val conversationRelative: Boolean = true,
    val conversationVolumePercent: Int = 40,
    val headGestures: Boolean = false,
    val disconnectUnworn: Boolean = false,
) {
    fun validate() { require(conversationVolumePercent in 10..85) }
}

/** Only full current setup may populate verification; importing POC cache never does. */
data class PairVerification(val modelNumber: String, val conformanceBuild: String, val verifiedAtMillis: Long)
enum class LongPressAction { CYCLE_NOISE_CONTROL_MODES, DIGITAL_ASSISTANT }
enum class HeadAction { NONE, TOGGLE_PLAYBACK, NEXT, PREVIOUS }

data class PairSettings(
    val verification: PairVerification? = null,
    val secondMode: QuickMode? = null, // null: derive candidate from current model, never RX
    val headPacketAlternate: Boolean = true,
    val host: HostSettings = HostSettings(),
    val leftLongPress: LongPressAction = LongPressAction.CYCLE_NOISE_CONTROL_MODES,
    val rightLongPress: LongPressAction = LongPressAction.CYCLE_NOISE_CONTROL_MODES,
    val nodAction: HeadAction = HeadAction.NONE,
    val shakeAction: HeadAction = HeadAction.NONE,
)
data class AirPodsRuntimeData(
    val schemaVersion: Int = 4,
    val start: StartState = StartState(),
    val selectedPair: PairAddress? = null,
    val pairs: Map<PairAddress, PairSettings> = emptyMap(),
    val pocMigrationCompleted: Boolean = false,
    val pendingCleanup: List<PendingCompanionCleanup> = emptyList(),
    // Bounded audit of user-cancelled removal requests; never hardware-removal receipts.
    val cancelledCleanup: List<PendingCompanionCleanup> = emptyList(),
) {
    fun validate() {
        require(schemaVersion in 1..4 && pairs.size <= 64)
        require(pendingCleanup.size <= 64 && pendingCleanup.map { it.key }.distinct().size == pendingCleanup.size)
        pendingCleanup.forEach { it.validate() }
        require(cancelledCleanup.size <= 64)
        cancelledCleanup.forEach { it.validate() }
        require(selectedPair == null || pairs.containsKey(selectedPair))
        require(start.consentGeneration >= 0)
        require(start.consumedExits.size <= EXIT_LEDGER_LIMIT && start.explicitlyAcknowledged.size <= EXIT_LEDGER_LIMIT)
        (start.consumedExits + start.explicitlyAcknowledged + listOfNotNull(start.lastExplicitlyAcknowledged)).forEach {
            require(it.timestampMillis >= 0 && it.pid > 0 && it.processName.isNotBlank() && it.reason >= 0)
        }
        start.marker?.let { require(it.epoch.isNotBlank() && it.pid > 0 && it.processName.isNotBlank() && it.startedAtMillis >= 0) }
        require(!start.suppressUntilExplicitResume || start.suppressReason != null)
        pairs.values.forEach { pair ->
            pair.host.validate()
            pair.verification?.let { require(it.modelNumber.isNotBlank() && it.conformanceBuild.isNotBlank() && it.verifiedAtMillis >= 0) }
        }
    }
    companion object { const val EXIT_LEDGER_LIMIT = 64 }
}

/** Cache-only migration input. Does not import enabled, build conformance or setup success. */
data class LegacyPocSnapshot(val cachedAddress: String?, val secondMode: String?) {
    override fun toString() = "LegacyPocSnapshot(cachedAddress=<private>, secondMode=$secondMode)"
}

/** Cleanup authority only, never selection, verification or consent. identity is retained
 * for v3 compatibility ONLY; mutable parcel hashes are never compared or trusted.
 * An unresolved capture cannot acquire a new handle on retry. */
data class PendingCompanionCleanup(
    val key: String,
    val address: PairAddress,
    val associationId: Int?,
    val packageName: String,
    val identity: String?,
    val knownAbsent: Boolean = false,
    val handleCaptured: Boolean = associationId != null,
) {
    fun validate() {
        require(key.isNotBlank() && packageName.isNotBlank())
        require(associationId == null || associationId > 0)
        require(identity == null || identity.matches(Regex("[0-9a-f]{64}")))
        require(!knownAbsent || (associationId == null && identity == null && !handleCaptured))
    }
}
