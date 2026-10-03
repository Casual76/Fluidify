package dev.pampa.fluidify.wear.playback

import android.util.Log
import dev.pampa.fluidify.wear.protocol.RepeatMode
import dev.pampa.fluidify.wear.standalone.EngineStatus
import dev.pampa.fluidify.wear.standalone.LocalControls
import dev.pampa.fluidify.wear.standalone.LocalOutput
import dev.pampa.fluidify.wear.standalone.Standalone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the music the watch is showing comes from. */
enum class PlaybackMode {
    /** The phone (or a Connect device the phone controls). */
    PHONE,

    /** The watch itself. */
    WATCH,
}

/**
 * The one set of controls the screens use, pointed at whichever player is in
 * front: the phone, or the watch's own.
 *
 * Moving between them is Spotify Connect's job, which already knows how to
 * carry a queue and a position from one device to another. Moving here starts
 * the watch's engine (so the watch is a Connect device) and asks the phone to
 * transfer to it; moving back asks the phone to take over again. Nothing about
 * the queue has to be re-sent by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivePlayback(
    private val scope: CoroutineScope,
    private val remote: PhoneRemote,
    private val local: LocalControls,
    private val standalone: () -> Standalone,
    /** Whether the watch keeps [uri] on its own disk. */
    private val keptOnWatch: (String) -> Boolean = { false },
) : PlaybackControls {

    private val _mode = MutableStateFlow(PlaybackMode.PHONE)
    val mode: StateFlow<PlaybackMode> = _mode.asStateFlow()

    private val front: PlaybackControls get() = if (_mode.value == PlaybackMode.WATCH) local else remote

    override val nowPlaying: StateFlow<NowPlaying> = _mode
        .flatMapLatest { if (it == PlaybackMode.WATCH) local.nowPlaying else remote.nowPlaying }
        .stateIn(scope, SharingStarted.Eagerly, remote.nowPlaying.value)

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        scope.launch { merge(remote.errors, local.errors).collect(_errors::tryEmit) }
    }

    /**
     * Plays on the watch, through [output]. Whatever the phone was playing
     * follows, through Spotify Connect, once the watch's engine is up.
     */
    fun moveToWatch(output: LocalOutput?) {
        val parts = standalone()
        parts.router.chosen.value = output
        _mode.value = PlaybackMode.WATCH
        local.connect()
        val phoneWasPlaying = remote.nowPlaying.value.snapshot?.let { it.track != null && (it.isPlaying || it.playWhenReady) } == true
        scope.launch {
            val status = withTimeoutOrNull(ENGINE_WAIT_MS) {
                parts.engine.status.first { it != EngineStatus.OFF && it != EngineStatus.STARTING }
            }
            if (status != EngineStatus.RUNNING) {
                Log.w(TAG, "watch engine not up: $status")
                return@launch
            }
            if (phoneWasPlaying) {
                // The watch's own Connect id; the phone moves its queue and position over.
                remote.transfer(parts.prefs.deviceId)
            }
        }
    }

    /** Hands the music back to the phone (or to [deviceId], a Connect device the phone can reach). */
    fun moveToPhone(deviceId: String) {
        remote.transfer(deviceId)
        _mode.value = PlaybackMode.PHONE
        scope.launch {
            // Long enough for Connect to have moved the music off the watch; the service then
            // stops itself once it has been idle, and the engine with it.
            delay(HANDBACK_MS)
            if (_mode.value == PlaybackMode.PHONE) local.disconnect()
        }
    }

    override fun transfer(deviceId: String) {
        when {
            deviceId == LocalControls.WATCH_DEVICE_ID -> moveToWatch(standalone().router.best)
            _mode.value == PlaybackMode.WATCH -> moveToPhone(deviceId)
            else -> remote.transfer(deviceId)
        }
    }

    override fun togglePlay() = front.togglePlay()
    override fun next() = front.next()
    override fun previous() = front.previous()
    override fun seekTo(positionMs: Long) = front.seekTo(positionMs)
    override fun setShuffle(enabled: Boolean) = front.setShuffle(enabled)
    override fun setRepeat(mode: RepeatMode) = front.setRepeat(mode)
    override fun setLiked(liked: Boolean) = front.setLiked(liked)
    override fun playContext(contextUri: String, startTrackUri: String?, shuffle: Boolean, label: String) {
        // No phone to play it on, but the watch has it: play it here, which is the point
        // of keeping it.
        val phoneAway = remote.nowPlaying.value.link != dev.pampa.fluidify.wear.link.LinkStatus.CONNECTED
        if (_mode.value == PlaybackMode.PHONE && phoneAway && keptOnWatch(contextUri)) {
            moveToWatch(standalone().router.best)
            local.playContext(contextUri, startTrackUri, shuffle, label)
            return
        }
        front.playContext(contextUri, startTrackUri, shuffle, label)
    }
    override fun playQueueIndex(index: Int, uri: String) = front.playQueueIndex(index, uri)
    override fun addToQueue(uri: String) = front.addToQueue(uri)
    override fun startRadio() = front.startRadio()
    override fun setVolume(level: Float, deviceId: String?) = front.setVolume(level, deviceId)
    override fun sleep(minutes: Int?, atTrackEnd: Boolean, cancel: Boolean) = front.sleep(minutes, atTrackEnd, cancel)

    private companion object {
        const val TAG = "ActivePlayback"

        /** Wi-Fi coming up plus a first handshake. */
        const val ENGINE_WAIT_MS = 30_000L
        const val HANDBACK_MS = 6_000L
    }
}
