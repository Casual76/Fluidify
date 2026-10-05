package dev.lelonio.square.wear

import androidx.annotation.StringRes
import dev.lelonio.square.R

/**
 * The sentence for why a watch update failed, from the word the coordinator keeps.
 *
 * [WatchUpdateCoordinator.State.Failed] carries a short token ("checksum", "wrong-signature"),
 * and sometimes the watch's own, which sends the reason of a decline or a failed install as it
 * has it. Those are for the logs. They used to be shown as they were, on the Watch page and in
 * the notification, after two of them had been given a sentence: everything else read as
 * "Failed: wrong-version", or as the text of whichever exception it was.
 *
 * One function for both places, so the page and the notification cannot disagree about what a
 * failure means. A token this does not know (an exception message, a word added on the watch
 * after this build) gets the generic line and never itself: nothing here echoes the reason.
 */
@StringRes
internal fun watchUpdateFailureRes(reason: String): Int = when (reason) {
    "watch-not-nearby", "no-watch" -> R.string.watch_update_connection_missing
    "offer-send-failed" -> R.string.watch_update_offer_failed
    "network", "download" -> R.string.watch_update_err_network
    "checksum", "missing-checksum" -> R.string.watch_update_err_corrupt
    "not-an-apk", "not-a-watch-apk", "wrong-package", "wrong-version", "cannot-read", "invalid-size" ->
        R.string.watch_update_err_invalid
    "wrong-signature" -> R.string.watch_update_err_signature
    "send-timeout", "send", "transfer-interrupted", "transfer-timeout" -> R.string.watch_update_err_transfer
    "install", "install-permission", "confirmation-unavailable" ->
        R.string.watch_update_err_install
    "busy" -> R.string.watch_update_err_busy
    "older" -> R.string.watch_update_err_older
    // The watch's own words for an offer it never answered and an install the system cut short.
    // Both used to fall to the generic line, which told the person nothing about what to do.
    "offer-expired" -> R.string.watch_update_err_expired
    "install-interrupted" -> R.string.watch_update_err_interrupted
    else -> if (reason.startsWith("receive")) R.string.watch_update_err_transfer else R.string.watch_update_failed
}
