package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.Serializable

/**
 * How the watch wants the phone's notifications to behave, published by the watch on
 * [WearPaths.WATCH].
 *
 * Exists for one experiment that can only be judged on a real watch: whether marking the phone's
 * media notification local-only takes the system's phone media controls off the watch, so that the
 * watch's own media session is the only Fluidify on the watch face (exactly what Spotify's watch app
 * looks like). Off unless the person turned it on in the watch's developer options.
 */
@Serializable
data class WatchSurfaces(
    /** Keep the phone's media notification on the phone. */
    val phoneMediaLocalOnly: Boolean = false,
    val changedAtEpochMs: Long = 0,
)
