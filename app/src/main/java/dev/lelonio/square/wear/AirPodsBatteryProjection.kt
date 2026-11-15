package dev.lelonio.square.wear

import dev.lelonio.square.airpods.AacpStage
import dev.lelonio.square.airpods.AirPodsState
import dev.lelonio.square.airpods.BatteryValue
import dev.lelonio.square.airpods.Received
import dev.pampa.fluidify.wear.protocol.AIRPODS_BATTERY_LIFETIME_MS
import dev.pampa.fluidify.wear.protocol.AirPodsBatteryLevel
import dev.pampa.fluidify.wear.protocol.AirPodsBatterySnapshot
import java.util.UUID
import kotlin.math.abs

/** Project BEFORE distinct/collect: head, ear, pending commands and diagnostics are not Wear news. */
internal data class AirPodsBatteryObservation(
    val generation: Long,
    val connected: Boolean,
    val batteries: Map<Int, Received<BatteryValue>>,
) {
    companion object {
        fun from(state: AirPodsState) = AirPodsBatteryObservation(
            state.generation,
            state.stage == AacpStage.SUBSCRIBED,
            state.batteries.filterKeys { it == 4 || it == 2 || it == 8 },
        )
    }
}

/** Pure, process-local projector. Neither persisted telemetry nor a lifecycle/control dependency. */
internal class AirPodsBatteryProjection(private val newSession: () -> String = { UUID.randomUUID().toString() }) {
    private var initializedAccount = false
    private var account: String? = null
    private var afterMs = Long.MIN_VALUE
    private var generation: Long? = null
    private var sourceSession = newSession()
    private var wallAnchor: Long? = null
    private var elapsedAnchor = 0L
    private var wasAvailable = false

    @Synchronized fun accountChanged(session: String?, elapsedMs: Long) {
        if (initializedAccount && account != session) {
            afterMs = elapsedMs
            sourceSession = newSession()
        }
        initializedAccount = true
        account = session
    }

    @Synchronized fun snapshot(
        observation: AirPodsBatteryObservation,
        available: Boolean,
        accountSession: String?,
        wallMs: Long,
        elapsedMs: Long,
    ): AirPodsBatterySnapshot? {
        accountChanged(accountSession, elapsedMs)
        val anchor = wallAnchor
        if (anchor == null) {
            wallAnchor = wallMs
            elapsedAnchor = elapsedMs
        } else if (elapsedMs < elapsedAnchor || abs((wallMs - anchor) - (elapsedMs - elapsedAnchor)) > CLOCK_STEP_MS) {
            // A wall-clock step cannot make an older RX young. Only a later RX can restore data.
            wallAnchor = wallMs
            elapsedAnchor = elapsedMs
            afterMs = elapsedMs
            sourceSession = newSession()
            return null
        }
        val eligible = available && observation.connected && !accountSession.isNullOrBlank()
        if (wasAvailable && !eligible) {
            afterMs = elapsedMs
            sourceSession = newSession()
        }
        wasAvailable = eligible
        if (!eligible) return null
        if (generation != observation.generation) {
            generation = observation.generation
            sourceSession = newSession()
        }
        fun level(component: Int): AirPodsBatteryLevel? {
            val rx = observation.batteries[component] ?: return null
            val percent = rx.value.percent ?: return null
            if (rx.generation != observation.generation || rx.atMs <= afterMs ||
                rx.value.status !in setOf(1, 2, 5) || percent !in 0..100 ||
                elapsedMs < 0 || rx.atMs < 0 || rx.atMs > elapsedMs ||
                elapsedMs - rx.atMs >= AIRPODS_BATTERY_LIFETIME_MS) return null
            val observedAt = requireNotNull(wallAnchor) + (rx.atMs - elapsedAnchor)
            return AirPodsBatteryLevel(percent, observedAt).takeIf { observedAt > 0 }
        }
        val result = AirPodsBatterySnapshot(requireNotNull(accountSession), sourceSession, level(4), level(2), level(8))
        return result.takeIf { it.left != null || it.right != null || it.case != null }
    }

    private companion object { const val CLOCK_STEP_MS = 2_000L }
}
