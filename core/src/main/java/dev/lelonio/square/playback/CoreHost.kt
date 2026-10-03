package dev.lelonio.square.playback

/**
 * What the shared player needs to know about the app it runs in.
 *
 * Implemented by the phone's Application and the watch's. The player used to
 * cast its context to the phone's Application to read one setting; in :core it
 * asks this instead, so the same player can run on a watch.
 */
interface CoreHost {

    /**
     * Whether the listener left the streaming quality on automatic. Only then
     * may the player step the bitrate down and up with the connection
     * ([BandwidthWatch]); a fixed choice is the listener's to make.
     */
    val qualityIsAutomatic: Boolean
}

/** The three bitrates Spotify offers this client, in kbps. */
object BitrateSteps {
    const val HIGH = 320
    const val MEDIUM = 160
    const val LOW = 96
}
