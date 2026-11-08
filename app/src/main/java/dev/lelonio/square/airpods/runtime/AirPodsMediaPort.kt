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
    val volume: StateFlow<MediaVolume?>
    fun start(session: AutomationSession, pair: PairAddress, settings: HostSettings)
    fun updateSettings(settings: HostSettings)
    fun setUiVisible(visible: Boolean)
    fun onEvent(event: Received<AacpEvent>)
    /** Explicit user volume/gesture actions invalidate conflicting automation ownership. */
    fun setUserVolume(value: Int): Boolean
    fun gestureContext(): Any? = null
    fun gesture(action: AirPodsMediaGesture): Boolean
    fun stop()
    override fun close() = stop()
}

enum class AirPodsMediaGesture { TOGGLE_PLAYBACK, NEXT, PREVIOUS, PAUSE, PLAY }
