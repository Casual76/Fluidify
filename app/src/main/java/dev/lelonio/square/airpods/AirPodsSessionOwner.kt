// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import kotlinx.coroutines.*
import java.io.Closeable

/** Host calls on its serialized lifecycle thread. No receiver, service, polling or reconnect loop.
 * acquire examines actual BT state and may attempt once; profileConnected is a real deduplicated
 * profile transition supplied by host, never a periodic retry. Not wired into the application. */
class AirPodsSessionOwner(
    private val scope: CoroutineScope,
    private val clock: AirPodsClock,
    private val acquire: () -> Unit,
    private val release: () -> Unit,
) : Closeable {
    private var enabled = false
    private var allowed = false
    private var suppressed = false
    private var uiVisible = false
    private var playing = false
    private var pausedAt: Long? = null
    private var leased = false
    private var pauseTimer: Job? = null
    private var profileWasConnected = false

    fun configure(enabled: Boolean, permissionAndBondValid: Boolean) {
        this.enabled = enabled; allowed = permissionAndBondValid
        if (!enabled || !allowed) { pausedAt = null; playing = false }
        reconcile()
    }
    fun setUiVisible(visible: Boolean) { uiVisible = visible; reconcile() }
    fun localPlayback(active: Boolean) {
        if (active) pausedAt = null
        else if (playing) pausedAt = clock.nowMs()
        playing = active
        reconcile()
    }
    fun transferredToRemote() { playing = false; pausedAt = null; reconcile() }
    fun explicitConnectOrNewUserSession() { suppressed = false; reconcile() }
    fun disconnectOrLogout() { suppressed = true; playing = false; pausedAt = null; reconcile() }
    fun profileConnected(connected: Boolean) {
        val newConnection = connected && !profileWasConnected
        profileWasConnected = connected
        if (newConnection && leased && !suppressed) acquire()
    }
    private fun reconcile() {
        pauseTimer?.cancel(); pauseTimer = null
        val remaining = pausedAt?.let { (90_000 - (clock.nowMs() - it)).coerceAtLeast(0) } ?: 0
        val wanted = enabled && allowed && !suppressed && (uiVisible || playing || remaining > 0)
        if (wanted != leased) {
            leased = wanted
            if (wanted) acquire() else release()
        }
        if (wanted && remaining > 0) pauseTimer = scope.launch { delay(remaining); reconcile() }
    }
    override fun close() {
        suppressed = true; pausedAt = null; playing = false; uiVisible = false
        reconcile()
    }
}
