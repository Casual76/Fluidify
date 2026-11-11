// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.automation.MediaTarget
import dev.lelonio.square.airpods.runtime.automation.Playback

/** Selection alone grants no permission, route evidence or effect ownership. */
internal fun selectMediaCandidate(candidates: List<MediaCandidate>, retainedTarget: MediaTarget?): MediaCandidate? {
    // Count every PLAYING contender, even remote, unauthorized or on another route.
    val playing = candidates.filter { it.playback == Playback.PLAYING }
    if (playing.isNotEmpty()) return playing.singleOrNull()
    // An empty local renderer is a known nonparticipant, even though its playback enum is
    // UNKNOWN. The producer must attest this explicitly; never infer it from a missing route
    // or a reason string, and never exempt an external UNKNOWN (or any PLAYING contender).
    val contenders = candidates.filterNot { it.local && it.knownEmptyLocal && it.playback == Playback.UNKNOWN }
    if (contenders.size == 1) return contenders.single()
    // Only known, idle bystanders can coexist with a previously identified target.
    // UNKNOWN is ambiguity; never choose a new target among multiple paused sessions.
    if (contenders.any { it.playback != Playback.PAUSED }) return null
    return contenders.singleOrNull { it.target == retainedTarget }
}
