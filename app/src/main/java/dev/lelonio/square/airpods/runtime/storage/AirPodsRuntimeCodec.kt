// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Explicit versioned private format; no Java deserialization, account data or RX cache. */
object AirPodsRuntimeCodec {
    private const val MAGIC = 0x41505231
    fun encode(data: AirPodsRuntimeData): ByteArray {
        data.validate()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(4)
            with(data.start) {
                out.writeBoolean(enabled); out.writeBoolean(suppressUntilExplicitResume)
                out.writeInt(suppressReason?.ordinal ?: -1); out.writeLong(consentGeneration)
                out.writeBoolean(marker != null)
                marker?.let { out.writeUTF(it.epoch); out.writeInt(it.pid); out.writeUTF(it.processName); out.writeLong(it.startedAtMillis); out.writeBoolean(it.clean) }
                out.keys(consumedExits); out.keys(explicitlyAcknowledged)
                out.writeBoolean(lastExplicitlyAcknowledged != null); lastExplicitlyAcknowledged?.let { out.key(it) }
                out.writeBoolean(ledgerTruncated)
            }
            out.writeBoolean(data.selectedPair != null); data.selectedPair?.let { out.writeUTF(it.value) }
            out.writeInt(data.pairs.size)
            data.pairs.forEach { (address, pair) ->
                out.writeUTF(address.value); out.writeBoolean(pair.verification != null)
                pair.verification?.let { out.writeUTF(it.modelNumber); out.writeUTF(it.conformanceBuild); out.writeLong(it.verifiedAtMillis) }
                out.writeInt(pair.secondMode?.ordinal ?: -1); out.writeBoolean(pair.headPacketAlternate)
                with(pair.host) {
                    listOf(automationsEnabled, otherAppsEnabled, fluidifyBackgroundEnabled, earPause, earResume, conversationDuck, conversationPause, conversationRelative).forEach(out::writeBoolean)
                    out.writeInt(conversationVolumePercent); out.writeBoolean(headGestures); out.writeBoolean(disconnectUnworn)
                }
                out.writeInt(pair.leftLongPress.ordinal); out.writeInt(pair.rightLongPress.ordinal)
                out.writeInt(pair.nodAction.ordinal); out.writeInt(pair.shakeAction.ordinal)
            }
            out.writeBoolean(data.pocMigrationCompleted)
            out.writeInt(data.pendingCleanup.size)
            data.pendingCleanup.forEach { out.cleanup(it) }
            out.writeInt(data.cancelledCleanup.size)
            data.cancelledCleanup.forEach { out.cleanup(it) }
        }
        return bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): AirPodsRuntimeData {
        require(bytes.size <= 1_048_576)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            val version = input.readInt(); require(version in 1..4) { "Unknown AirPods schema" }
            val enabled = input.readBoolean(); val suppress = input.readBoolean()
            val reason = input.readInt().let { if (it == -1) null else SuppressReason.entries[it] }
            val generation = input.readLong()
            val marker = if (input.readBoolean()) ProcessMarker(input.readUTF(), input.readInt(), input.readUTF(), input.readLong(), input.readBoolean()) else null
            val consumed = input.keys(); val acknowledged = input.keys()
            val last = if (input.readBoolean()) input.key() else null
            val truncated = input.readBoolean()
            val selected = if (input.readBoolean()) PairAddress(input.readUTF()) else null
            val count = input.readInt(); require(count in 0..64)
            val pairs = mutableMapOf<PairAddress, PairSettings>()
            repeat(count) {
                val address = PairAddress(input.readUTF()); require(address !in pairs)
                val verification = if (input.readBoolean()) PairVerification(input.readUTF(), input.readUTF(), input.readLong()) else null
                val second = input.readInt().let { if (it == -1) null else QuickMode.entries[it] }
                val alternate = input.readBoolean()
                val host = HostSettings(input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readInt(), input.readBoolean(), input.readBoolean())
                pairs[address] = if (version == 1) PairSettings(verification, second, alternate, host) else
                    PairSettings(verification, second, alternate, host, LongPressAction.entries[input.readInt()], LongPressAction.entries[input.readInt()], HeadAction.entries[input.readInt()], HeadAction.entries[input.readInt()])
            }
            val migrated = input.readBoolean()
            val cleanup = if (version < 3) emptyList() else {
                val size = input.readInt(); require(size in 0..64)
                List(size) { PendingCompanionCleanup(input.readUTF(), PairAddress(input.readUTF()),
                    input.readInt().let { if (it == -1) null else it }, input.readUTF(),
                    (if (input.readBoolean()) input.readUTF() else null), input.readBoolean(),
                    handleCaptured = if (version >= 4) input.readBoolean() else false).let {
                        if (version < 4) it.copy(handleCaptured = it.associationId != null) else it
                    } }
            }
            val cancelled = if (version < 4) emptyList() else {
                val size = input.readInt(); require(size in 0..64)
                List(size) { input.cleanup() }
            }
            AirPodsRuntimeData(start = StartState(enabled, suppress, reason, generation, marker, consumed, acknowledged, last, truncated), selectedPair = selected, pairs = pairs.toMap(), pocMigrationCompleted = migrated, pendingCleanup = cleanup, cancelledCleanup = cancelled).also {
                require(input.available() == 0); it.validate()
            }
        }
    }
    private fun DataOutputStream.cleanup(c: PendingCompanionCleanup) {
        writeUTF(c.key); writeUTF(c.address.value); writeInt(c.associationId ?: -1)
        writeUTF(c.packageName); writeBoolean(c.identity != null); c.identity?.let { writeUTF(it) }
        writeBoolean(c.knownAbsent); writeBoolean(c.handleCaptured)
    }
    private fun DataInputStream.cleanup() = PendingCompanionCleanup(readUTF(), PairAddress(readUTF()),
        readInt().let { if (it == -1) null else it }, readUTF(),
        if (readBoolean()) readUTF() else null, readBoolean(), readBoolean())
    private fun DataOutputStream.key(key: ExitKey) { writeLong(key.timestampMillis); writeInt(key.pid); writeUTF(key.processName); writeInt(key.reason) }
    private fun DataOutputStream.keys(keys: List<ExitKey>) { writeInt(keys.size); keys.forEach { key(it) } }
    private fun DataInputStream.key() = ExitKey(readLong(), readInt(), readUTF(), readInt())
    private fun DataInputStream.keys(): List<ExitKey> { val count = readInt(); require(count in 0..64); return List(count) { key() } }
}
