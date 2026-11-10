// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.content.Context
import dev.lelonio.square.airpods.runtime.storage.AirPodsRuntimeStore
import dev.lelonio.square.airpods.runtime.storage.LegacyPocSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only migration of the historical POC cache. RuntimeStore is the only new writer.
 * The old "verified" label is not full setup evidence. Never import enabled/build/model,
 * delete the cache on failure, select a pair automatically or invoke any Bluetooth API.
 */
internal class AirPodsPreferences(context: Context) {
    private val app = context.applicationContext

    suspend fun migrateInto(store: AirPodsRuntimeStore): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!store.snapshot().getOrThrow().pocMigrationCompleted) {
                val prefs = app.getSharedPreferences("airpods_internal", Context.MODE_PRIVATE)
                val address = prefs.getString("verified_address", null)
                val mode = address?.let { prefs.getString("second_$it", null) }
                store.migratePoc(LegacyPocSnapshot(address, mode)).getOrThrow()
            }
        }
    }
}
