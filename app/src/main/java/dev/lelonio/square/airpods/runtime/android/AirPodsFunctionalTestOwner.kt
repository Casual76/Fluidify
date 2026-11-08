// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.content.Context
import dev.lelonio.square.BuildConfig
import dev.lelonio.square.airpods.AirPodsTransportOwnership

/** Not serializable, never read from an Intent. Only debug source calls issue().
 * Monotonic time and revocation are checked again at queued transport writes. */
internal class AirPodsFunctionalTestPermit private constructor(private val now: () -> Long) {
    private val sessionEnd = now() + MAX_WINDOW
    private var backgroundEnd = 0L
    private var revoked = false
    @Synchronized fun allowed(visible: Boolean): Boolean = !revoked && now() < deadline() && (visible || now() < backgroundEnd)
    @Synchronized fun deadline(): Long = if (backgroundEnd > 0) backgroundEnd else sessionEnd
    @Synchronized fun armBackground(duration: Long, visible: Boolean): Boolean {
        if (!visible || !allowed(true) || duration !in MIN_WINDOW..MAX_WINDOW || backgroundEnd != 0L) return false
        backgroundEnd = now() + duration
        return true
    }
    @Synchronized fun backgroundArmed(): Boolean = !revoked && now() < backgroundEnd
    @Synchronized fun revoke() { revoked = true }
    companion object {
        const val MIN_WINDOW = 10 * 60_000L
        const val MAX_WINDOW = 15 * 60_000L
        internal fun issue(now: () -> Long): AirPodsFunctionalTestPermit {
            check(BuildConfig.DEBUG)
            return AirPodsFunctionalTestPermit(now)
        }
    }
}

/** Association is published AFTER C4 and atomic lease acquisition, before FGS scheduling.
 * No extras, restoration, or production get() fallback. Stale cleanup cannot clear a new owner. */
internal object AirPodsServiceOwnerRegistry {
    private var owner: AirPodsAndroidController? = null
    private var lease: AirPodsTransportOwnership.Lease? = null
    private var ended = false
    @Synchronized fun claim(value: AirPodsAndroidController, token: AirPodsTransportOwnership.Lease): Boolean {
        if (ended && lease?.released == true) { owner = null; lease = null }
        if (token.released || owner != null) return false
        owner = value; lease = token; ended = false
        return true
    }
    @Synchronized fun resolve(): AirPodsAndroidController? = owner?.takeIf { !ended && lease?.released == false }
    @Synchronized fun serviceEnded(value: AirPodsAndroidController, action: String?) {
        if (owner === value && action == lease?.let { "dev.lelonio.square.airpods.STOP.${it.generation}" } ) {
            ended = true
            if (lease?.released == true) { owner = null; lease = null }
        }
    }
    @Synchronized fun release(value: AirPodsAndroidController, token: AirPodsTransportOwnership.Lease?, deferUntilServiceEnd: Boolean = false) {
        if (!deferUntilServiceEnd) ended = owner === value && lease === token || ended
        if (owner === value && lease === token && token?.released != false && ended) { owner = null; lease = null }
    }
}

/** The A2DP profile belongs to the process, shared by production and debug controllers.
 * Repeated debug sessions do not register a new profile proxy per controller. */
internal object AirPodsProcessBluetoothFacts {
    private var value: AndroidBluetoothFacts? = null
    @Synchronized fun get(context: Context): AndroidBluetoothFacts = value ?: AndroidBluetoothFacts(context.applicationContext).also { value = it }
}
