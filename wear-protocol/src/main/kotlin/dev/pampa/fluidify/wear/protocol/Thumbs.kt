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

/**
 * Where the phone is willing to fetch a cover from on a watch's say-so.
 *
 * A [ThumbWant.url] is the watch's word, and the phone fetches it with its own connection and its
 * own image cache: left open, that is a way to make the phone request any address on any network
 * it is on (a router's admin page, a service on the local network) and send back what it found
 * as "a cover". Only Spotify's own image hosts, over https, are answered; anything else needs no
 * fetching, because every cover the apps really use comes from them.
 */
object ImageHosts {
    private val SUFFIXES = listOf(".scdn.co", ".spotifycdn.com")

    fun isAllowed(url: String): Boolean {
        val uri = try {
            java.net.URI(url)
        } catch (_: java.net.URISyntaxException) {
            return false
        }
        if (uri.scheme != "https") return false
        // No credentials in the address, and the ordinary port: nothing a Spotify image URL has.
        if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return false
        val host = uri.host?.lowercase() ?: return false
        return SUFFIXES.any { suffix -> host.endsWith(suffix) && host.length > suffix.length }
    }
}

/** One cover the watch wants: its key, and where the phone can find it. */
@Serializable
data class ThumbWant(val key: String, val url: String?)

@Serializable
data class ThumbHeader(val key: String, val bytes: Int)
