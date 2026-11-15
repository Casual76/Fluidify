package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.Serializable

/** Observed phone telemetry only. No pair address, name, serial or account identity travels here. */
@Serializable
data class AirPodsBatterySnapshot(
    /** The phone's existing, ephemeral account correlation, never a Spotify credential. */
    val accountSession: String,
    /** Random, process-local correlation for one admitted AirPods connection generation. */
    val sourceSession: String,
    val left: AirPodsBatteryLevel? = null,
    val right: AirPodsBatteryLevel? = null,
    val case: AirPodsBatteryLevel? = null,
)

@Serializable
data class AirPodsBatteryLevel(
    val percent: Int,
    /** Original RX time translated from the phone's monotonic clock, never the publish time. */
    val observedAtEpochMs: Long,
)

/** Same strict lifetime as AirPodsState.liveBattery; absent components are unknown. */
const val AIRPODS_BATTERY_LIFETIME_MS = 300_000L
