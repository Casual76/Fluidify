// SPDX-License-Identifier: GPL-3.0-only
// Selectively adapted from Lelonio/Square 8598e13347b9c20153898f2481083c3ffcd24fb2.
package dev.lelonio.square.data

import java.util.UUID

data class HistoryAccount(val id: String, val service: String = "spotify") {
    init { require(id.isNotBlank() && service == "spotify") }
}
data class MeasuredInterval(val from: Long, val until: Long)
data class ListeningEvent(
    val id: String,
    val track: CatalogTrack,
    val startedAt: Long,
    val endedAt: Long = startedAt,
    val listenedMs: Long? = null,
    val qualified: Boolean = false,
    val spotifyAt: Long? = null,
    val intervals: List<MeasuredInterval> = emptyList(),
) {
    val provenance: String get() = when {
        listenedMs == null -> "spotify_observation"
        spotifyAt != null -> "local_measurement_and_spotify_observation"
        else -> "local_measurement"
    }
}

/** Monotonic audible time only. Position is deliberately not an input. */
class ListeningSampler {
    fun restore(checkpoint: ListeningEvent) {
        require(checkpoint.listenedMs != null)
        event = checkpoint.copy(intervals = emptyList())
        lastTick = null
        audible = false
    }
    private var event: ListeningEvent? = null
    private var lastTick: Long? = null
    private var audible = false

    fun sample(track: CatalogTrack?, playing: Boolean, now: Long, tick: Long, occurrenceId: String? = null): ListeningEvent? {
        if (track == null || event?.track?.uri?.let { it != track.uri } == true) {
            val previous = finish(now, tick)
            if (track != null) sample(track, playing, now, tick, occurrenceId)
            return previous
        }
        if (event == null && playing) event = ListeningEvent(
            occurrenceId ?: "local:${UUID.randomUUID()}", track, now, listenedMs = 0,
        )
        // Discard unobserved scheduling gaps rather than assume the audio kept playing.
        val elapsed = tick - (lastTick ?: tick)
        val delta = if (audible && playing && elapsed in 0..2_000) elapsed else 0
        event = event?.let {
            val ms = (it.listenedMs ?: 0) + delta
            it.copy(track = track, endedAt = now, listenedMs = ms,
                qualified = ms >= threshold(track.durationMs),
                intervals = if (delta > 0) it.intervals + MeasuredInterval(now - delta, now) else it.intervals)
        }
        lastTick = tick
        audible = playing
        return event
    }

    /** Called for every occurrence boundary, including repeat-one with the same URI. */
    fun finish(now: Long, tick: Long): ListeningEvent? {
        val previous = event
        val elapsed = tick - (lastTick ?: tick)
        val delta = if (audible && elapsed in 0..2_000) elapsed else 0
        val result = previous?.let {
            val ms = (it.listenedMs ?: 0) + delta
            it.copy(endedAt = now, listenedMs = ms, qualified = ms >= threshold(it.track.durationMs),
                intervals = if (delta > 0) it.intervals + MeasuredInterval(now - delta, now) else it.intervals)
        }
        event = null; lastTick = null; audible = false
        return result
    }

    /** A seek changes no occurrence and contributes no position jump. */
    fun discontinuity(tick: Long) { lastTick = tick; audible = false }

    companion object {
        fun threshold(durationMs: Long): Long = if (durationMs > 0) minOf(durationMs / 2, 240_000) else 30_000
    }
}
