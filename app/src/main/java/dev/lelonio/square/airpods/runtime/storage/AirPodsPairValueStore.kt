// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.storage

import android.content.SharedPreferences
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import java.io.*
import java.security.MessageDigest

/** Wall time is presentation metadata only. Never compare persisted elapsedRealtime clocks. */
data class SavedProperty(val value: PropertyValue, val atEpochMs: Long)
data class SavedChoice(val value: PropertyValue, val atEpochMs: Long, val status: DesiredStatus)
data class SavedCasePercent(val percent: Int, val atEpochMs: Long) {
    init { require(percent in 0..100) }
}
data class AirPodsPairValues(
    val observed: Map<CommandProperty, SavedProperty> = emptyMap(),
    val choices: Map<CommandProperty, SavedChoice> = emptyMap(),
    /** No charging flag: an old percentage cannot establish current charging. */
    val casePercent: SavedCasePercent? = null,
)

/** Separate from consent/setup persistence. Failure here cannot grant or revoke authority. */
interface PairValuePersistence {
    fun read(pair: PairAddress): ByteArray?
    fun write(pair: PairAddress, bytes: ByteArray): Boolean
    fun remove(pair: PairAddress): Boolean
}

class SharedPreferencesPairValuePersistence(private val preferences: SharedPreferences) : PairValuePersistence {
    private fun key(pair: PairAddress) = MessageDigest.getInstance("SHA-256")
        .digest(pair.value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    override fun read(pair: PairAddress): ByteArray? = preferences.getString(key(pair), null)?.let {
        java.util.Base64.getDecoder().decode(it)
    }
    override fun write(pair: PairAddress, bytes: ByteArray) = preferences.edit()
        .putString(key(pair), java.util.Base64.getEncoder().encodeToString(bytes)).commit()
    override fun remove(pair: PairAddress) = preferences.edit().remove(key(pair)).commit()
}

/** Call load/save/forget on IO. Repository coalesces persistence by pair, outside its monitor. */
class AirPodsPairValueStore(private val persistence: PairValuePersistence = object : PairValuePersistence {
    private val bytes = mutableMapOf<PairAddress, ByteArray>()
    @Synchronized override fun read(pair: PairAddress) = bytes[pair]?.clone()
    @Synchronized override fun write(pair: PairAddress, bytes: ByteArray): Boolean { this.bytes[pair] = bytes.clone(); return true }
    @Synchronized override fun remove(pair: PairAddress): Boolean { bytes.remove(pair); return true }
}) {
    fun load(pair: PairAddress): Result<AirPodsPairValues> = runCatching {
        persistence.read(pair)?.let(::decode) ?: AirPodsPairValues()
    }
    fun save(pair: PairAddress, values: AirPodsPairValues): Result<Unit> = runCatching {
        check(persistence.write(pair, encode(values))) { "Pair value cache write failed" }
    }
    fun forget(pair: PairAddress): Result<Unit> = runCatching {
        check(persistence.remove(pair)) { "Pair value cache removal failed" }
    }

    private fun valid(property: CommandProperty, value: PropertyValue, writing: Boolean): Boolean = when (value) {
        is PropertyValue.Number -> AirPodsProperties.spec(property)?.let {
            if (writing) it.accepts(value.value) else it.acceptsReceived(value.value)
        } == true
        is PropertyValue.Eq -> property == CommandProperty.EQ
        is PropertyValue.Name -> property == CommandProperty.NAME && if (writing)
            runCatching { AacpCodec.rename(value.value) }.isSuccess
            else value.value.toByteArray(Charsets.UTF_8).size <= 255 && '\u0000' !in value.value
    }
    private fun DataOutputStream.value(value: PropertyValue) {
        when (value) {
            is PropertyValue.Number -> { writeByte(0); writeInt(value.value) }
            is PropertyValue.Eq -> { writeByte(1); writeBoolean(value.enabled); writeInt(value.low); writeInt(value.mid); writeInt(value.high) }
            is PropertyValue.Name -> { writeByte(2); writeUTF(value.value) }
        }
    }
    private fun DataInputStream.value(): PropertyValue = when (readUnsignedByte()) {
        0 -> PropertyValue.Number(readInt())
        1 -> PropertyValue.Eq(readBoolean(), readInt(), readInt(), readInt())
        2 -> PropertyValue.Name(readUTF())
        else -> error("Unknown cached value type")
    }
    private fun encode(values: AirPodsPairValues): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeInt(values.observed.size)
            values.observed.forEach { (p, v) ->
                require(valid(p, v.value, false) && v.atEpochMs >= 0)
                out.writeUTF(p.name); out.value(v.value); out.writeLong(v.atEpochMs)
            }
            out.writeInt(values.choices.size)
            values.choices.forEach { (p, v) ->
                require(valid(p, v.value, true) && v.atEpochMs >= 0)
                out.writeUTF(p.name); out.value(v.value); out.writeLong(v.atEpochMs); out.writeUTF(v.status.name)
            }
            out.writeBoolean(values.casePercent != null)
            values.casePercent?.let { require(it.atEpochMs >= 0); out.writeInt(it.percent); out.writeLong(it.atEpochMs) }
        }
    }.toByteArray()
    private fun decode(bytes: ByteArray): AirPodsPairValues {
        require(bytes.size <= 65_536)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            fun count() = input.readInt().also { require(it in 0..CommandProperty.entries.size) }
            val observed = buildMap {
                repeat(count()) {
                    val p = CommandProperty.valueOf(input.readUTF()); val v = input.value(); val at = input.readLong()
                    require(valid(p, v, false) && at >= 0 && p !in this)
                    put(p, SavedProperty(v, at))
                }
            }
            val choices = buildMap {
                repeat(count()) {
                    val p = CommandProperty.valueOf(input.readUTF()); val v = input.value(); val at = input.readLong()
                    // Statuses are stored by name. One this build does not know (written by a newer
                    // build) is read as UNCERTAIN, which never wins effective, instead of failing the pair.
                    val name = input.readUTF()
                    val status = DesiredStatus.entries.firstOrNull { it.name == name } ?: DesiredStatus.UNCERTAIN
                    require(valid(p, v, true) && at >= 0 && p !in this)
                    put(p, SavedChoice(v, at, status))
                }
            }
            val case = if (input.readBoolean()) SavedCasePercent(input.readInt(), input.readLong().also { require(it >= 0) }) else null
            require(input.available() == 0)
            AirPodsPairValues(observed, choices, case)
        }
    }
}
