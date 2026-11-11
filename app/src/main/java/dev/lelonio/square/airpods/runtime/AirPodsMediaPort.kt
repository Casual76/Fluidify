// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime

import dev.lelonio.square.airpods.AacpEvent
import dev.lelonio.square.airpods.Received
import dev.lelonio.square.airpods.runtime.automation.AutomationSession
import dev.lelonio.square.airpods.runtime.automation.MediaVolume
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow

/** Process-local bridge. One Android media arbiter handles local and external sessions.
 * The service owns lifetime and validated RX order. Implementations own callback/tick scopes,
 * attribution and execution-time revalidation. No method acknowledges a hardware/media effect.
 */
interface AirPodsMediaPort : Closeable {
    /** Current phone STREAM_MUSIC observation during admitted lifetime, even without media.
     * Null means unavailable/stopped, never a guessed level or hardware acknowledgement.
     */
    val volume: StateFlow<MediaVolume?>
    fun start(session: AutomationSession, pair: PairAddress, settings: HostSettings)
    fun updateSettings(settings: HostSettings)
    fun setUiVisible(visible: Boolean)
    fun onEvent(event: Received<AacpEvent>)
    /** Explicit user volume/gesture actions invalidate conflicting automation ownership. */
    fun setUserVolume(value: Int): Boolean
    /** Owner captures its ticket before handler dispatch and rechecks current admission and
     * foreground action here. Implementations must evaluate this guard at actual execution.
     */
    fun setUserVolume(value: Int, stillCurrent: () -> Boolean): Boolean =
        stillCurrent() && setUserVolume(value)
    fun gestureContext(): Any? = null
    fun gesture(action: AirPodsMediaGesture): Boolean
    /** Immediate source-side revocation, callable from ANY thread. Must not block, wait for the
     * serial domain or call back into the owner. Once it returns no method, including work
     * already queued on the serial domain, may start a platform/media effect, and the port is
     * never restarted: the owner creates a new port per admitted session. Idempotent.
     * stop() still follows on the serial domain to release observers.
     * Default: a port without effects of its own relies on the owner's fences.
     */
    fun revoke() {}
    fun stop()
    override fun close() = stop()
}

enum class AirPodsMediaGesture { TOGGLE_PLAYBACK, NEXT, PREVIOUS, PAUSE, PLAY }
