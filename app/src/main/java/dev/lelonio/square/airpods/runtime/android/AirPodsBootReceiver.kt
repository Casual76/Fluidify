// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Process (re)creation entry point, manifest-declared like LibrePods' BootReceiver.
 * Starting the process installs the runtime via SquareApplication; this receiver only
 * prods the ordinary admission path. Consent, suppression and device facts are all
 * re-checked by the start gate — a boot broadcast is never by itself a consent, and
 * with no explicitly enabled pair this is a no-op (see [AirPodsAndroidController]). */
class AirPodsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        AirPodsAndroidRuntime.get()?.onSystemRestart()
    }
}
