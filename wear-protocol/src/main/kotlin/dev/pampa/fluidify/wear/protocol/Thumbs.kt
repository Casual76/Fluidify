package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.Serializable

/**
 * Small covers for list rows, sent by the phone over a channel ([WearPaths.THUMBS]).
 *
 * The watch writes one [ThumbRequest] line; the phone answers, for each cover it could get, a
 * [ThumbHeader] line followed by exactly [ThumbHeader.bytes] bytes of WebP, and closes the channel
 * when it is done. A cover it could not get is simply left out. The phone already has these
 * images (its own screens show them) or fetches them on its own connection: the watch never pulls
 * a 640 px cover through its phone's Bluetooth proxy to show it at 40 dp.
 */
@Serializable
data class ThumbRequest(
    val wants: List<ThumbWant>,
    /** The edge, in pixels, the watch wants them at. */
    val sizePx: Int = DEFAULT_SIZE_PX,
) {
    companion object {
        const val DEFAULT_SIZE_PX = 120

        /** Most covers in one request: a screen of rows and the next one. */
        const val MAX_WANTS = 24
    }
}

/** One cover the watch wants: its key, and where the phone can find it. */
@Serializable
data class ThumbWant(val key: String, val url: String?)

@Serializable
data class ThumbHeader(val key: String, val bytes: Int)
