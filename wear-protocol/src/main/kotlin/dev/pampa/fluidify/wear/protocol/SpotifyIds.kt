package dev.pampa.fluidify.wear.protocol

import java.math.BigInteger

/**
 * Spotify's ids in the two spellings the apps meet.
 *
 * A URI carries the id in base 62 (`spotify:track:4cOdK2wGLETKBW3PvgPWqT`); the
 * native download store files a track under the same 128 bits in base 16, two
 * characters of shard directory and thirty of name (native/src/downloads.rs).
 * The watch reads that store from Kotlin to know what it has, so it needs the
 * conversion too.
 */
object SpotifyIds {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val BASE = BigInteger.valueOf(62)

    /** The 32 hex digits of a track URI's id, or null when it is not a track. */
    fun trackHex(uri: String): String? {
        if (!uri.startsWith(TRACK_PREFIX)) return null
        return base62ToHex(uri.removePrefix(TRACK_PREFIX))
    }

    fun base62ToHex(id: String): String? {
        if (id.length != 22) return null
        var value = BigInteger.ZERO
        for (char in id) {
            val digit = ALPHABET.indexOf(char)
            if (digit < 0) return null
            value = value.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        if (value.bitLength() > 128) return null
        return value.toString(16).padStart(32, '0')
    }

    private const val TRACK_PREFIX = "spotify:track:"
}
