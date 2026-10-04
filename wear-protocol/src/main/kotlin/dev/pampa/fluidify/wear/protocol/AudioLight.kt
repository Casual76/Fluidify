package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.Serializable

@Serializable
data class AudioLightSubscription(val id: String, val enabled: Boolean)
@Serializable
data class AudioLightTelemetry(val id: String, val seq: Long, val track: String, val generation: Long,
    val positionMs: Long, val energy: Float, val bass: Float, val mids: Float, val treble: Float,
    val sampledAtEpochMs: Long = 0)

/** Ephemeral leases only; never persisted and never allowed for a cloud-routed node. */
class AudioLightLeases {
    private data class Lease(val id: String, val until: Long)
    private val leases = mutableMapOf<String, Lease>()
    fun request(node: String, request: AudioLightSubscription, nearby: Boolean, nowMs: Long) {
        if (request.id.length !in 1..64) return
        if (!request.enabled) { if (leases[node]?.id == request.id) leases.remove(node); return }
        if (nearby) leases[node] = Lease(request.id, nowMs + 5000)
    }
    fun recipients(nowMs: Long, nearby: Set<String>): Map<String, String> {
        leases.entries.removeAll { it.value.until <= nowMs || it.key !in nearby }
        return leases.mapValues { it.value.id }
    }
    fun remove(node: String) { leases.remove(node) }
    fun clear() = leases.clear()
    fun isEmpty() = leases.isEmpty()
}

class AudioLightReceiver {
    private var generation = Long.MIN_VALUE
    private var seq = Long.MIN_VALUE
    fun reset() { generation = Long.MIN_VALUE; seq = Long.MIN_VALUE }
    @Synchronized fun accept(frame: AudioLightTelemetry, id: String, track: String, positionMs: Long, nowMs: Long? = null, clockOffsetMs: Long = 0): Boolean {
        if (frame.id != id || frame.track != track || frame.seq <= seq || frame.generation < generation ||
            frame.positionMs < 0 || positionMs < 0 || frame.generation < 0 || frame.seq < 0 ||
            (nowMs != null && (nowMs - frame.sampledAtEpochMs - clockOffsetMs !in -200L..1000L)) ||
            kotlin.math.abs(frame.positionMs - positionMs) > 1500 ||
            listOf(frame.energy, frame.bass, frame.mids, frame.treble).any { !it.isFinite() || it !in 0f..1f }) return false
        generation = frame.generation; seq = frame.seq; return true
    }
}
