// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Read-only seam: notifications invalidate a presentation, never authorize a command. */
internal class TileAvailabilitySource(
    val changes: Flow<Unit>,
    val available: () -> Boolean,
) {
    companion object {
        fun from(owner: AirPodsAndroidController) = TileAvailabilitySource(
            combine(owner.state, owner.runtime) { _, _ -> Unit },
            { owner.tileUnavailableReason() == null },
        )
    }
}

/** Main-thread confined lifecycle. Neither resolving an existing source nor collecting acquires
 * an owner. The presentation callback returns false when the platform tile is not attached. */
internal class TileAvailabilityObservation(
    private val resolve: () -> TileAvailabilitySource?,
    private val present: (Boolean) -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val presentationIdentity: () -> Any? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var collector: Job? = null
    private var source: TileAvailabilitySource? = null
    private var listening = false
    private var destroyed = false
    private var epoch = 0L
    private var lastPresentation: Boolean? = null
    private var lastIdentity: Any? = null

    fun start() {
        if (destroyed || listening) return
        listening = true
        source = resolve()
        val currentEpoch = ++epoch
        refresh()
        val currentSource = source ?: return
        collector = scope.launch {
            currentSource.changes.collect {
                if (listening && !destroyed && epoch == currentEpoch) refresh()
            }
        }
    }

    /** Click can request a fresh read without starting another subscription. */
    fun refresh() {
        if (!listening || destroyed) return
        val available = source?.available?.invoke() == true
        val identity = presentationIdentity()
        if ((lastPresentation != available || lastIdentity !== identity) && present(available)) {
            lastPresentation = available; lastIdentity = identity
        }
    }

    fun stop() {
        listening = false
        epoch++
        collector?.cancel()
        collector = null
        source = null
        lastPresentation = null
        lastIdentity = null
    }

    fun destroy() {
        destroyed = true
        stop()
        scope.cancel()
    }
}
