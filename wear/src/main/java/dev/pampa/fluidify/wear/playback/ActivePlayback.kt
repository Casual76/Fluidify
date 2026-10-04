package dev.pampa.fluidify.wear.playback

import android.util.Log
import dev.pampa.fluidify.wear.protocol.RepeatMode
import dev.pampa.fluidify.wear.standalone.EngineStatus
import dev.pampa.fluidify.wear.standalone.LocalControls
import dev.pampa.fluidify.wear.standalone.LocalOutput
import dev.pampa.fluidify.wear.standalone.Standalone
import dev.lelonio.square.nativecore.NativeBridge
import dev.pampa.fluidify.wear.protocol.AckErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext
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

    private val _moving = MutableStateFlow(false)

    /** True while the music is being moved onto the watch: the player says so instead of nothing. */
    val moving: StateFlow<Boolean> = _moving.asStateFlow()

    private var moveJob: Job? = null

    /**
     * Plays on the watch, through [output]. Whatever the phone was playing follows, once the
     * watch's engine is a Connect device the account can see.
     *
     * Three ways to get the music here, tried in order until the watch is actually playing:
     *
     *  1. the phone sends it, with Spotify's transfer call, after republishing its queue as
     *     something the watch can resolve — the same road the phone's own device picker takes;
     *  2. the watch pulls it, with the transfer librespot makes when a device asks for the music
     *     itself;
     *  3. the watch starts the same playlist at the same song and position by itself, and the phone
     *     is paused — a handoff by hand, for the case where Spotify will not move it at all.
     *
     * A failure of all three is said on the watch (see [errors]) rather than leaving the player
     * waiting on "pick something to play", which is what the first version did.
     */
    fun moveToWatch(output: LocalOutput?) {
        val parts = standalone()
        parts.router.chosen.value = output
        _mode.value = PlaybackMode.WATCH
        local.connect()
        val phone = remote.nowPlaying.value
        val wasPlaying = phone.snapshot?.takeIf { it.track != null && (it.isPlaying || it.playWhenReady) }
        moveJob?.cancel()
        moveJob = scope.launch {
            val status = withTimeoutOrNull(ENGINE_WAIT_MS) {
                parts.engine.status.first { it != EngineStatus.OFF && it != EngineStatus.STARTING }
            }
            if (status != EngineStatus.RUNNING) {
                Log.w(TAG, "watch engine not up: $status")
                return@launch
            }
            if (wasPlaying == null) return@launch
            _moving.value = true
            try {
                if (!parts.engine.awaitConnected(CONNECT_WAIT_MS)) Log.w(TAG, "watch not in Connect yet; trying anyway")
                val watchId = parts.prefs.deviceId
                if (remote.transferAndWait(watchId) && playingHere()) return@launch
                Log.i(TAG, "the phone's transfer did not start the watch; pulling")
                runCatching { withContext(Dispatchers.IO) { NativeBridge.pullPlayback() } }
                    .onFailure { Log.i(TAG, "pull refused: ${it.message}") }
                if (playingHere()) return@launch
                Log.i(TAG, "pull did not start the watch; starting the same song here")
                if (startHere(wasPlaying, phone.positionAt(System.currentTimeMillis())) && playingHere()) {
                    if (remote.nowPlaying.value.snapshot?.isPlaying == true) remote.togglePlay()
                    return@launch
                }
                _errors.tryEmit(AckErrors.TRANSFER)
            } finally {
                _moving.value = false
            }
        }
    }

    /** Waits a little for the watch's own player to be playing something. */
    private suspend fun playingHere(): Boolean = withTimeoutOrNull(START_WAIT_MS) {
        local.nowPlaying.first { now -> now.snapshot?.let { it.track != null && (it.isPlaying || it.playWhenReady) } == true }
        true
    } ?: false

    /** The handoff by hand: the phone's playlist at the phone's song, from where it was. */
    private fun startHere(snapshot: dev.pampa.fluidify.wear.protocol.PlaybackSnapshot, positionMs: Long): Boolean {
        val track = snapshot.track ?: return false
        val context = snapshot.context?.uri ?: track.uri
        local.playContext(context, startTrackUri = track.uri, shuffle = snapshot.shuffle, label = snapshot.context?.label.orEmpty())
        scope.launch {
            if (playingHere() && positionMs > RESUME_MIN_MS) local.seekTo(positionMs)
        }
        return true
    }

    /**
     * The phone signed out, so the watch does too: its own playback stops, the engine goes, and
     * only then is the credential deleted — under a running engine it could be written back.
     */
    suspend fun signedOut() {
        val parts = standalone()
        if (_mode.value == PlaybackMode.WATCH || local.isConnected) {
            moveJob?.cancel()
            _moving.value = false
            local.stop()
            _mode.value = PlaybackMode.PHONE
            withTimeoutOrNull(SIGN_OUT_WAIT_MS) { parts.engine.status.first { it == EngineStatus.OFF } }
        }
        withContext(Dispatchers.IO) { parts.auth.signOut() }
    }

    /** Hands the music back to the phone (or to [deviceId], a Connect device the phone can reach). */
    fun moveToPhone(deviceId: String) {
        moveJob?.cancel()
        _moving.value = false
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
    override suspend fun addToPlaylist(playlistUri: String, trackUri: String): Boolean = front.addToPlaylist(playlistUri, trackUri)

    private companion object {
        const val TAG = "ActivePlayback"

        /** Wi-Fi coming up plus a first handshake. */
        const val ENGINE_WAIT_MS = 30_000L
        const val HANDBACK_MS = 6_000L

        /** From "engine started" to "the account can see the watch". */
        const val CONNECT_WAIT_MS = 20_000L

        /** How long a transfer gets to make the watch's player start. */
        const val START_WAIT_MS = 6_000L

        /** How long a sign-out waits for the engine to stop before deleting what it signed in with. */
        const val SIGN_OUT_WAIT_MS = 5_000L

        /** Below this, starting from the top is as good as resuming. */
        const val RESUME_MIN_MS = 3_000L
    }
}
