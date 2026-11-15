package dev.lelonio.square.playback

/** Fresh service facts, sampled on main. A notification alone does not own the native engine. */
internal data class PlaybackIdleFacts(
    val visible: Boolean = false,
    val controllerConnected: Boolean = false,
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val remoteActive: Boolean = false,
    val downloading: Boolean = false,
    val nativeWork: Boolean = false,
    val watchWork: Boolean = false,
    val airPodsAutomation: Boolean = false,
) {
    // Buffering alone can be a paused prepare() with no operation behind it. Active loading is
    // retained by playWhenReady/nativeWork; a stale status must not own the engine indefinitely.
    val keepsAlive: Boolean get() = visible || controllerConnected || playing || playWhenReady ||
        remoteActive || downloading || nativeWork || watchWork || airPodsAutomation
}

/** Uses the owner's existing maintenance tick; owns no coroutine, timer, service, or binding. */
internal class PlaybackIdleShutdown(
    private val clock: () -> Long,
    private val facts: () -> PlaybackIdleFacts,
    private val onIdle: () -> Unit,
    private val timeoutMillis: Long = 5 * 60_000L,
) {
    init { require(timeoutMillis > 0) }
    private var deadline: Long? = null
    private var requested = false

    /** A short PLAY/client/native operation between maintenance ticks starts a new idle period. */
    fun activity() { deadline = null; requested = false }

    fun sample() {
        if (requested) return
        if (facts().keepsAlive) { activity(); return }
        val now = clock()
        val due = deadline ?: (now + timeoutMillis).also { deadline = it }
        if (now < due) return
        // The stopping callback must run without suspending after this final authority read.
        if (facts().keepsAlive) { activity(); return }
        requested = true
        onIdle()
    }
}
