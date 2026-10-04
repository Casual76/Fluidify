package dev.pampa.fluidify.wear.ui.common

import androidx.annotation.StringRes
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.playback.ActivePlayback
import dev.pampa.fluidify.wear.protocol.AckErrors

/**
 * The sentence for a command that failed, from the reason the phone (or the watch's own player)
 * gave. Every reason says something, an unknown one included: a failure nobody sees is how the
 * radio button seemed to do nothing at all.
 */
object ErrorMessages {

    @StringRes
    fun textFor(code: String): Int = when (code) {
        AckErrors.UNREACHABLE -> R.string.error_unreachable
        AckErrors.PHONE_UNAVAILABLE -> R.string.error_phone_unavailable
        AckErrors.TRANSFER -> R.string.error_transfer
        AckErrors.NOTHING_TO_SKIP -> R.string.error_nothing_to_skip
        AckErrors.RADIO -> R.string.error_radio
        AckErrors.LIKE -> R.string.error_like
        AckErrors.PLAYLIST -> R.string.error_playlist
        ActivePlayback.NO_OUTPUT -> R.string.error_no_output
        ActivePlayback.ENGINE_NEEDS_PHONE -> R.string.engine_needs_phone
        ActivePlayback.ENGINE_SIGNED_OUT -> R.string.engine_signed_out
        ActivePlayback.ENGINE_PREMIUM -> R.string.engine_premium
        ActivePlayback.ENGINE_FAILED -> R.string.engine_failed
        AckErrors.CONTEXT, AckErrors.EMPTY -> R.string.error_context
        AckErrors.NOT_FOUND -> R.string.error_not_found
        AckErrors.NOT_IN_QUEUE -> R.string.error_not_in_queue
        else -> R.string.error_generic
    }
}
