package dev.pampa.fluidify.wear.update

import android.content.Context
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.pampa.fluidify.wear.link.PhoneLink
import dev.pampa.fluidify.wear.protocol.UpdateChannels

/**
 * The release line the watch follows, which is the one the phone follows.
 *
 * There is one setting, on the phone. The watch has no control for it and keeps only the last
 * thing it was told: every hello from the phone carries it (see Hello.updateChannel) and is
 * written here, so that the watch's own updater, the one that runs when the phone has been away
 * for a week, looks at the same channel the phone would have. A phone from before the field says
 * nothing, which reads as stable.
 */
internal object UpdateChannelPrefs {
    /** The key, in the same preferences as when the phone was last seen. */
    const val KEY = "update_channel"

    /** The channel as the protocol spells it; stable until a phone has said otherwise. */
    fun word(context: Context): String =
        UpdateChannels.parse(context.getSharedPreferences(PhoneLink.PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    /** Writes down what the phone just said it follows. */
    fun remember(context: Context, word: String) {
        context.getSharedPreferences(PhoneLink.PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, UpdateChannels.parse(word)).apply()
    }

    /** The engine's own value for [word]. */
    fun engine(word: String): UpdateChannel =
        if (UpdateChannels.parse(word) == UpdateChannels.BETA) UpdateChannel.BETA else UpdateChannel.STABLE
}
