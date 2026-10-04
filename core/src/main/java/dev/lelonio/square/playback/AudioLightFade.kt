package dev.lelonio.square.playback

/** Fade duration is independent of polling rate and dropped display frames. */
class AudioLightFade(initial: Float = 0f) {
    private var strength = initial.coerceIn(0f, 1f)
    private var fadingAt = -1L
    private var fadingFrom = 0f
    fun update(available: Boolean, nowMs: Long): Float {
        if (available) {
            fadingAt = -1L
            strength += (1f - strength) * .36f
        } else if (strength > 0f) {
            if (fadingAt < 0) { fadingAt = nowMs; fadingFrom = strength }
            strength = fadingFrom * (1f - (nowMs - fadingAt).coerceAtLeast(0) / 250f).coerceIn(0f, 1f)
        }
        return strength
    }
}
