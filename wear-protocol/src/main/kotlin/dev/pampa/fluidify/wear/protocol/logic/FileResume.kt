package dev.pampa.fluidify.wear.protocol.logic

/** Never join the prefix of one Spotify encoding to the suffix of another. */
object FileResume {
    fun offset(requested: Long, total: Long, expectedId: String?, actualId: String?): Long =
        requested.takeIf { it in 1 until total && !expectedId.isNullOrBlank() && expectedId == actualId } ?: 0L
}
