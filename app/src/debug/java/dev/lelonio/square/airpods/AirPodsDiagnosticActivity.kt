// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.content.Context
import android.content.Intent

/** Retained internal debug entry. The old independent POC owner has been removed:
 * all radio/media work now uses the explicit functional lane and the C4/lease owner. */
class AirPodsDiagnosticActivity : AirPodsFunctionalHarnessActivity() {
    companion object {
        fun intent(context: Context) = Intent(context, AirPodsDiagnosticActivity::class.java)
    }
}
