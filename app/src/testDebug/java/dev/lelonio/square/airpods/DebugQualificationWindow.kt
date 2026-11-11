// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import dev.lelonio.square.BuildConfig
import dev.lelonio.square.airpods.runtime.android.AirPodsQualificationLifecycleAuthority
import dev.lelonio.square.airpods.runtime.storage.*
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.android.*
import java.io.File
import org.json.JSONObject

/** Isolated no-backup bytes. No product preferences or saved Activity state are read. */
internal class DebugQualificationPersistence(context: Context) : RuntimePersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "airpods_qualification_runtime_v1"))
    private var failed = false
    @Synchronized override fun read(): ByteArray? {
        check(!failed)
        return if (file.baseFile.exists()) file.readFully() else null
    }
    @Synchronized override fun commit(bytes: ByteArray): Boolean {
        check(!failed)
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream); true
        } catch (_: Exception) { failed = true; file.failWrite(stream); false }
    }
}

/** This is a finite *qualification opt-in*, never a serialized transport/UI permit. */
internal data class DebugQualificationWindow(
    val pair: PairAddress, val build: String, val boot: Int,
    val startElapsed: Long, val endElapsed: Long, val startWall: Long,
    val consent: Long = -1, val enabled: Boolean = true,
) {
    fun valid(build: String, boot: Int, elapsed: Long, wall: Long): Boolean = enabled &&
        this.build == build && this.boot == boot && boot >= 0 && consent >= 0 &&
        startElapsed >= 0 && endElapsed > startElapsed && endElapsed - startElapsed in 1..900_000 &&
        elapsed in startElapsed until endElapsed && startWall >= 0 && wall >= startWall && wall - startWall <= 905_000 &&
        kotlin.math.abs((wall - startWall) - (elapsed - startElapsed)) <= 5_000
}

internal class DebugQualificationAuthority private constructor(
    private val context: Context, private var window: DebugQualificationWindow,
    private var observed: ObservationRegistration? = null,
) : AirPodsQualificationLifecycleAuthority {
    private val file = AtomicFile(File(context.noBackupFilesDir, "airpods_qualification_authority_v1"))
    private var failed = false
    val end get() = window.endElapsed
    val pair get() = window.pair
    fun fresh() = build(context)?.let { window.valid(it, boot(context), SystemClock.elapsedRealtime(), System.currentTimeMillis()) } == true
    @Synchronized override fun sealObservation(registration: ObservationRegistration?): Boolean {
        if (registration != null && (registration.association.address != pair || registration.association.packageName != context.packageName)) return false
        observed = registration
        return write()
    }
    /** Cleanup uses only the recorded own public handle, and never disassociates or starts observation. */
    @Synchronized fun cleanupObservation(): Boolean {
        val handle = observed ?: return true
        return runCatching {
            val registry = AndroidCompanionRegistry(context, AirPodsProcessBluetoothFacts.get(context))
            val matches = registry.ownAssociations().filter { it.sameEndpoint(handle.association) }
            if (matches.size > 1) false
            else if (matches.isEmpty() || registry.stop(handle)) { observed = null; write() } else false
        }.getOrDefault(false)
    }
    @Synchronized override fun valid(pair: PairAddress, consent: Long): Boolean = !failed &&
        window.pair == pair && window.consent == consent && fresh()
    @Synchronized override fun sealExplicitConsent(pair: PairAddress, consent: Long): Boolean {
        if (failed || window.pair != pair || !window.enabled || SystemClock.elapsedRealtime() !in window.startElapsed until window.endElapsed) return false
        window = window.copy(consent = consent)
        return write()
    }
    @Synchronized fun revoke() { window = window.copy(enabled = false); write() }
    private fun write(): Boolean {
        if (failed) return false
        var stream: java.io.FileOutputStream? = null
        return try {
            val w = window
            val json = JSONObject().put("pair", w.pair.value).put("build", w.build).put("boot", w.boot)
                .put("startElapsed", w.startElapsed).put("endElapsed", w.endElapsed).put("startWall", w.startWall)
                .put("consent", w.consent).put("enabled", w.enabled)
            observed?.let { json.put("observationKind", it.kind.name).put("associationId", it.association.id ?: -1) }
            stream = file.startWrite(); stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream); true
        } catch (_: Exception) { failed = true; file.failWrite(stream); false }
    }
    companion object {
        private fun build(context: Context) = airPodsQualificationBuild(context)
        private fun boot(context: Context) = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        fun create(context: Context, pair: PairAddress): DebugQualificationAuthority {
            check(BuildConfig.DEBUG && boot(context) >= 0)
            val now = SystemClock.elapsedRealtime()
            return DebugQualificationAuthority(context, DebugQualificationWindow(pair, requireNotNull(build(context)), boot(context), now, now + 900_000, System.currentTimeMillis()))
        }
        fun load(context: Context): DebugQualificationAuthority? = runCatching {
            val file = AtomicFile(File(context.noBackupFilesDir, "airpods_qualification_authority_v1"))
            if (!file.baseFile.exists()) return null
            val j = JSONObject(String(file.readFully(), Charsets.UTF_8))
            val w = DebugQualificationWindow(PairAddress(j.getString("pair")), j.getString("build"), j.getInt("boot"),
                j.getLong("startElapsed"), j.getLong("endElapsed"), j.getLong("startWall"), j.getLong("consent"), j.getBoolean("enabled"))
            val observed = if (!j.has("observationKind")) null else ObservationRegistration(
                ObservationKind.valueOf(j.getString("observationKind")), OwnedAssociation(j.getInt("associationId").takeIf { it > 0 }, w.pair, context.packageName), 0)
            DebugQualificationAuthority(context, w, observed)
        }.getOrNull()
    }
}
