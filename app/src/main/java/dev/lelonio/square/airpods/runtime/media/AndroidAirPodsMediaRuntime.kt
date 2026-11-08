// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.lelonio.square.airpods.AacpEvent
import dev.lelonio.square.airpods.Received
import dev.lelonio.square.airpods.runtime.*
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.*
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process owner creates ONE instance. All port calls must be serial on main, RX in arrival order.
 * Merely constructing this object registers nothing and acquires no playback/socket/service.
 */
class AndroidAirPodsMediaRuntime(
    context: Context,
    local: LocalAirPodsMediaBridge? = null,
    private val environment: AndroidMediaEnvironment = AndroidMediaEnvironment(context, local),
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : AirPodsMediaPort {
    private val mutableVolume = MutableStateFlow<MediaVolume?>(null)
    override val volume: StateFlow<MediaVolume?> = mutableVolume
    private var session: AutomationSession? = null
    private val queue = object : MediaRuntimeQueue {
        override fun post(block: () -> Unit) { handler.post { block() } }
        override fun later(delayMillis: Long, block: () -> Unit): Closeable {
            val runnable = Runnable { block() }
            handler.postDelayed(runnable, delayMillis)
            return Closeable { handler.removeCallbacks(runnable) }
        }
    }
    private val core = MediaRuntimeCore(environment, queue, AutomationClock { SystemClock.elapsedRealtime() }) {
        mutableVolume.value = it
    }
    private fun serial() { check(Looper.myLooper() == handler.looper) { "AirPods media requires main serialization" } }
    override fun start(session: AutomationSession, pair: PairAddress, settings: HostSettings) {
        serial(); stop()
        this.session = session
        environment.pair = pair
        core.start(session, settings)
    }
    override fun updateSettings(settings: HostSettings) { serial(); core.configure(settings) }
    override fun setUiVisible(visible: Boolean) { serial(); core.visible(visible) }
    override fun onEvent(event: Received<AacpEvent>) {
        serial()
        if (event.generation != session?.generation) return
        when (val value = event.value) {
            is AacpEvent.Ear -> core.ear(EarState.valueOf(value.sample.normalized.name))
            is AacpEvent.ConversationActivity -> when (value.rawStatus) {
                1, 2 -> core.conversation(true)
                6, 8, 9 -> core.conversation(false)
            }
            // CA setting is not voice activity. Hardware stem defaults must not be duplicated.
            else -> Unit
        }
    }
    override fun setUserVolume(value: Int): Boolean { serial(); return core.manualVolume(value) }
    override fun gestureContext(): Any? {
        serial()
        return environment.read().filter { it.permitted && it.routedToPair && !it.callActive }.map { it.target }.singleOrNull()
    }
    override fun gesture(action: AirPodsMediaGesture): Boolean { serial(); return core.gesture(action) }
    override fun stop() { serial(); session = null; core.stop(); environment.pair = null }
}
