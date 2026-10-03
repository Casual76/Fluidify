package dev.pampa.fluidify.wear.protocol.logic

import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot

/** What the person chose for Fluidify's entry on the watch face. */
enum class NowBarMode {
    /** Only when the system cannot show the phone's playback itself. The default. */
    AUTO,

    /** Always, next to whatever the system shows. */
    ALWAYS,

    /** Never. */
    NEVER,
}

/** Which entry, if any, the watch puts on the watch face for phone playback. */
enum class NowBarEntry {
    NONE,

    /** The silent ongoing activity: an icon and a line of status, a tap opens the player. */
    ONGOING,

    /** A media notification over the watch's mirror of the phone's session (the experiment). */
    MIRROR,
}

/**
 * Decides the watch's own entry on the watch face, so there is exactly one Fluidify there.
 *
 * Wear OS shows phone playback by itself: the phone's media notification becomes the system's media
 * controls on the watch, and in the Galaxy Watch's Now bar. Wear's own guidance is not to post a
 * media notification on the watch for music that plays only on the phone, and an ongoing activity
 * beside the system's entry is the duplicate the tests found. So, by default, the watch adds its
 * entry only when the phone says its notification is *not* up — the phone driving a computer with
 * nothing in its shade, or its notifications turned off — which is when the system shows nothing
 * and the watch app is the only thing that knows something is playing.
 *
 * The watch's own playback is never this policy's business: the watch's media session posts its own
 * notification, and that is the entry.
 */
object NowBarPolicy {

    fun entry(mode: NowBarMode, snapshot: PlaybackSnapshot?, watchPlaying: Boolean, mirror: Boolean): NowBarEntry {
        if (watchPlaying || mode == NowBarMode.NEVER) return NowBarEntry.NONE
        if (snapshot?.track == null) return NowBarEntry.NONE
        if (mirror) return NowBarEntry.MIRROR
        return when (mode) {
            NowBarMode.ALWAYS -> NowBarEntry.ONGOING
            // Unknown (an older phone) counts as "the system shows it": a missing entry is a
            // smaller fault than a duplicate.
            NowBarMode.AUTO -> if (snapshot.systemMediaControls == false) NowBarEntry.ONGOING else NowBarEntry.NONE
            NowBarMode.NEVER -> NowBarEntry.NONE
        }
    }
}
