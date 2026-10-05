package dev.pampa.fluidify.wear.ui.common

import androidx.compose.runtime.staticCompositionLocalOf

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

/**
 * Where a screen says something short to the listener, wherever they are: the one toast of the
 * app, held by the root (see WatchRoot). A screen that drew its own, or showed a platform Toast,
 * had a timer and a look of its own, and the failures of commands said themselves in yet another.
 */
fun interface NoticeSink {
    /** Shows [message] for a moment; [failure] also gives the buzz that says something went wrong. */
    fun show(message: String, failure: Boolean)
}

/** The app's notice; says nothing in a preview or a test that does not provide one. */
val LocalNotice = staticCompositionLocalOf { NoticeSink { _, _ -> } }
