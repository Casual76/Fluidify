// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

data class OrdinaryCallState(val state: Int, val ambiguous: Boolean = false) {
    companion object { const val UNKNOWN = -1; const val IDLE = 0; const val RINGING = 1; const val ACTIVE = 2 }
}
interface OrdinaryCallPort {
    fun state(): OrdinaryCallState
    fun dispatch(answer: Boolean): PlatformResult
}

/** One request per ringing epoch. Observable call waiting stays blocked until idle.
 * Aggregate telephony does not expose every held/same-SIM call: no call identity guarantee. */
class OrdinaryCallOperations(private val port: OrdinaryCallPort) {
    private var previous = OrdinaryCallState(OrdinaryCallState.UNKNOWN)
    private var waiting = false
    private var sent = false
    var epoch = 0L; private set
    @Synchronized fun observe(): OrdinaryCallState {
        val value = port.state()
        if (value != previous) {
            if (previous.state == OrdinaryCallState.ACTIVE && value.state == OrdinaryCallState.RINGING) waiting = true
            if (value.state == OrdinaryCallState.IDLE) { waiting = false; sent = false }
            previous = value; epoch++
        }
        return value.copy(ambiguous = value.ambiguous || waiting || sent)
    }
    @Synchronized fun request(answer: Boolean, expectedEpoch: Long, authorized: () -> Boolean): PlatformResult {
        val value = observe()
        if (epoch != expectedEpoch || !authorized()) return PlatformResult.Denied(PlatformFailure.STALE)
        if (value.state != OrdinaryCallState.RINGING || value.ambiguous) return PlatformResult.Denied(PlatformFailure.CALL_OR_ROUTE_MIX)
        sent = true // never retry on rejection or a duplicate gesture
        return port.dispatch(answer)
    }
    @Synchronized fun reset() { previous = OrdinaryCallState(OrdinaryCallState.UNKNOWN); waiting = false; sent = false; epoch++ }
}

/** Ordinary deprecated public APIs, no dialer role, hidden telephony or call-log access. */
@Suppress("DEPRECATION", "MissingPermission")
class AndroidOrdinaryCallPort(private val context: Context) : OrdinaryCallPort {
    private fun permission(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    override fun state(): OrdinaryCallState = try {
        if (!permission(Manifest.permission.READ_PHONE_STATE)) OrdinaryCallState(OrdinaryCallState.UNKNOWN)
        else {
            val tm = context.getSystemService(TelephonyManager::class.java)
            val telecom = context.getSystemService(TelecomManager::class.java)
            val state = tm?.callState ?: OrdinaryCallState.UNKNOWN
            val subs = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty().map { tm?.createForSubscriptionId(it.subscriptionId)?.callStateForSubscription }
                else emptyList()
            val mode = context.getSystemService(AudioManager::class.java)?.mode
            val communication = mode == AudioManager.MODE_IN_COMMUNICATION || (state == OrdinaryCallState.RINGING && mode == AudioManager.MODE_IN_CALL)
            OrdinaryCallState(state, communication || subs.count { it != null && it != OrdinaryCallState.IDLE } > 1 ||
                (state == OrdinaryCallState.IDLE && telecom?.isInCall == true))
        }
    } catch (_: RuntimeException) { OrdinaryCallState(OrdinaryCallState.UNKNOWN) }
    override fun dispatch(answer: Boolean): PlatformResult = try {
        if (!permission(Manifest.permission.READ_PHONE_STATE) || !permission(Manifest.permission.ANSWER_PHONE_CALLS))
            PlatformResult.Denied(PlatformFailure.PERMISSION)
        else if (!answer && Build.VERSION.SDK_INT < 28) PlatformResult.Denied(PlatformFailure.PLATFORM_UNAVAILABLE)
        else if (state().let { it.state != OrdinaryCallState.RINGING || it.ambiguous }) PlatformResult.Denied(PlatformFailure.CALL_OR_ROUTE_MIX)
        else {
            val telecom = context.getSystemService(TelecomManager::class.java)
            if (telecom == null) PlatformResult.Denied(PlatformFailure.PLATFORM_UNAVAILABLE)
            else if (answer) { telecom.acceptRingingCall(); PlatformResult.RequestAccepted }
            else if (telecom.endCall()) PlatformResult.RequestAccepted else PlatformResult.Denied(PlatformFailure.REJECTED)
        }
    } catch (_: SecurityException) { PlatformResult.Denied(PlatformFailure.SECURITY)
    } catch (_: RuntimeException) { PlatformResult.Denied(PlatformFailure.PLATFORM_UNAVAILABLE) }
}
