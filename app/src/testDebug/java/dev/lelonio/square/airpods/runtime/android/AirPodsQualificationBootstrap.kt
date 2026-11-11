// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.content.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.os.Build
import dev.lelonio.square.airpods.AirPodsFunctionalHarness
import kotlinx.coroutines.*

/** The root Application hunk calls this after the inert product installer. */
object AirPodsQualificationBootstrap {
    @Volatile private var installed = false
    @Volatile internal var receiptObservationInstalled = false
        private set
    @Synchronized fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                AirPodsFunctionalHarness.revokeAudioSetup()
                if (intent?.action == Intent.ACTION_TIME_CHANGED) AirPodsFunctionalHarness.existing()?.takeIf { it.isQualification }?.requestClose()
            }
        }
        val filter = IntentFilter().apply { addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED) }
        receiptObservationInstalled = runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
            true
        }.getOrDefault(false)
        installed = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { AirPodsFunctionalHarness.restoreQualification(context.applicationContext) }
            catch (_: Exception) { android.util.Log.w("FluidifyAirPods", "QUALIFICATION_RESTORE_FAILED_NV") }
        }
    }
    internal fun presence(address: String?, associationId: Int?) = AirPodsFunctionalHarness.presence(address, associationId)
}
