// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.storage

import android.content.SharedPreferences
import android.util.Base64

/** Root supplies a NEW private preference file, never airpods_internal. Single writer only. */
class SharedPreferencesRuntimePersistence(private val preferences: SharedPreferences) : RuntimePersistence {
    // Android may retain changed memory after a failed disk commit. Poison this adapter
    // until process restart so that such memory can never authorize a subsequent start.
    private var failed = false
    @Synchronized override fun read(): ByteArray? {
        check(!failed) { "AirPods persistence unavailable" }
        return preferences.getString("runtime_v1", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    }
    @Synchronized override fun commit(bytes: ByteArray): Boolean {
        check(!failed)
        return try {
            preferences.edit().putString("runtime_v1", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit().also { if (!it) failed = true }
        } catch (error: Exception) { failed = true; throw error }
    }
}
