// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.content.Context
import android.os.SystemClock
import dev.lelonio.square.airpods.runtime.android.*
import dev.lelonio.square.airpods.runtime.lifecycle.GateResult
import dev.lelonio.square.airpods.runtime.media.AndroidAirPodsMediaRuntime
import dev.lelonio.square.airpods.runtime.storage.*
import dev.lelonio.square.playback.PlaybackAirPodsMediaBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Debug-only process session. Created ONLY by the visible Start button, never Intent/restore.
 * Scratch bytes, consent and pair choice disappear at disposal/process death. */
internal class AirPodsFunctionalHarness private constructor(context: Context, val visible: MutableStateFlow<Boolean>) {
    private val permit = AirPodsFunctionalTestPermit.issue(SystemClock::elapsedRealtime)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scratch = AirPodsRuntimeStore(object : RuntimePersistence {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes.clone(); return true }
    })
    val controller = AirPodsAndroidController(context.applicationContext, visible, { false },
        { AndroidAirPodsMediaRuntime(context.applicationContext, local = PlaybackAirPodsMediaBridge) }, permit, scratch, onTestStopped = { requestClose() })
    private var expiry: Job? = null
    @Volatile private var closed = false
    val backgroundArmed get() = permit.backgroundArmed()
    private fun scheduleExpiry() {
        expiry?.cancel()
        expiry = scope.launch {
            delay((permit.deadline() - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            close()
        }
    }
    fun armBackground(minutes: Int): Boolean {
        if (closed || !controller.runtime.value.setupVerified || !controller.runtime.value.active) return false
        val duration = minutes * 60_000L
        if (backgroundArmed || duration !in AirPodsFunctionalTestPermit.MIN_WINDOW..AirPodsFunctionalTestPermit.MAX_WINDOW || !visible.value) return false
        // Same background resources as production: no diagnostic wake lock. The
        // monotonic permit expires even if deep sleep delays the cleanup coroutine.
        // A scheduled cleanup is not proof of an exact OS wakeup/FD-close deadline.
        if (!permit.armBackground(duration, visible.value)) return false
        scheduleExpiry()
        return true
    }
    fun leftVisibleUi() {
        visible.value = false
        if (!backgroundArmed) scope.launch { close() }
    }
    fun requestClose() { scope.launch { close() } }
    suspend fun close() = withContext(NonCancellable) {
        val first = synchronized(this@AirPodsFunctionalHarness) { if (closed) false else { closed = true; true } }
        if (!first) return@withContext
        permit.revoke()
        try { controller.disposeFunctionalTest() }
        finally {
            synchronized(Companion) { if (active === this@AirPodsFunctionalHarness) active = null }
            scope.cancel()
        }
    }
    companion object {
        @Volatile private var active: AirPodsFunctionalHarness? = null
        fun existing(): AirPodsFunctionalHarness? = active
        suspend fun start(context: Context, pair: PairAddress, visible: MutableStateFlow<Boolean>): Pair<AirPodsFunctionalHarness?, GateResult> {
            val session = synchronized(this) {
                check(visible.value) { "Visible test action required" }
                check(active == null) { "Stop the previous test first" }
                AirPodsFunctionalHarness(context, visible).also { active = it }
            }
            return try {
                session.controller.selectPair(pair).getOrThrow()
                val result = session.controller.beginFunctionalTest()
                if (result is GateResult.Started) {
                    session.scheduleExpiry()
                    session to result
                } else { session.close(); null to result }
            } catch (error: Throwable) { session.close(); throw error }
        }
    }
}
