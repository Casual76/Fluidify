package dev.lelonio.square.update

import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.pampa.fluidify.wear.protocol.UpdateChannels

/**
 * The release line as a word, for what is stored and what is sent to the watch.
 *
 * The engine's [UpdateChannel] is an enum, which is right for the code that asks the manifest
 * but wrong for a preference or a message: a value stored by its name breaks when the enum is
 * reordered or trimmed, and the protocol never carries an enum an older peer might not know
 * (see [UpdateChannels]). So the setting and the hello speak in words, and these two functions
 * are the only places that translate.
 */
val UpdateChannel.word: String
    get() = if (this == UpdateChannel.BETA) UpdateChannels.BETA else UpdateChannels.STABLE

/** The channel a stored or received [word] stands for; anything unknown or missing is stable. */
fun updateChannelOf(word: String?): UpdateChannel =
    if (UpdateChannels.parse(word) == UpdateChannels.BETA) UpdateChannel.BETA else UpdateChannel.STABLE
