package dev.pampa.fluidify.wear.system

/** Background phone updates must not overwrite the watch's last displayed state. */
class SurfaceSignatures(var phone: String? = null, var watch: String? = null) {
    fun changed(signature: String, watchSource: Boolean): Boolean {
        val previous = if (watchSource) watch else phone
        if (watchSource) watch = signature else phone = signature
        return signature != previous
    }
}
