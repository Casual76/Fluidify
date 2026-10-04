package dev.pampa.fluidify.wear.ui.common

/** Repeated detents cannot restart the same error toast or vibrate repeatedly. */
class NoticeWindow(private val durationMs: Long) {
    private var message: String? = null
    private var until = 0L
    fun accept(next: String, nowMs: Long): Boolean {
        if (next == message && nowMs < until) return false
        message = next
        until = nowMs + durationMs
        return true
    }
}
