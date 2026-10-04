package dev.pampa.fluidify.wear.protocol.logic

/** Radio interruptions and expired sessions must not permanently skip a track. */
object DownloadFailure {
    fun unavailable(message: String?): Boolean = message != null && (
        message == "track-unavailable" || message.contains("not available on this account") ||
        message.contains("no playable file") || message.contains("no alternative of this track can be played")
    )
}
