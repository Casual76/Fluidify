package dev.pampa.fluidify.wear.playback

import android.util.Log
import dev.pampa.fluidify.wear.protocol.RepeatMode
import dev.pampa.fluidify.wear.standalone.EngineStatus
import dev.pampa.fluidify.wear.standalone.LocalControls
import dev.pampa.fluidify.wear.standalone.LocalOutput
import dev.pampa.fluidify.wear.standalone.Standalone
import dev.lelonio.square.nativecore.NativeBridge
import dev.pampa.fluidify.wear.protocol.AckErrors
import dev.pampa.fluidify.wear.protocol.PlaybackSource
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
    private val localFactory: () -> LocalControls,
    private val standalone: () -> Standalone,
    /** Whether the watch keeps [uri] on its own disk. */
    private val keptOnWatch: (String) -> Boolean = { false },
) : PlaybackControls {
    private val local: LocalControls by lazy(localFactory)

    private val _mode = MutableStateFlow(PlaybackMode.PHONE)
    val mode: StateFlow<PlaybackMode> = _mode.asStateFlow()

    private val front: PlaybackControls get() = if (_mode.value == PlaybackMode.WATCH) local else remote

    override val nowPlaying: StateFlow<NowPlaying> = _mode
        .flatMapLatest { if (it == PlaybackMode.WATCH) local.nowPlaying else remote.nowPlaying }
        .stateIn(scope, SharingStarted.Eagerly, remote.nowPlaying.value)

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        scope.launch {
            _mode.flatMapLatest { mode ->
                if (mode == PlaybackMode.WATCH) merge(remote.errors, local.errors) else remote.errors
            }.collect(_errors::tryEmit)
        }
        scope.launch {
            remote.nowPlaying.collect { phone ->
                if (_mode.value != PlaybackMode.WATCH) return@collect
                if (moveJob?.isActive == true) {
                    // Snapshots received during the explicit handoff belong to that move.
                    watchSelectionSeq = maxOf(watchSelectionSeq, phone.snapshot?.seq ?: Long.MIN_VALUE)
                    return@collect
                }
                if (PhonePlaybackPriority.shouldFollow(phone, standalone().prefs.deviceId, watchSelectionSeq, System.currentTimeMillis())) {
                    // The phone started playing: stop the local session now, including its radio lease.
                    _mode.value = PlaybackMode.PHONE
                    local.stop()
                }
            }
        }
    }

    private val _moving = MutableStateFlow(false)

    /** True while the music is being moved onto the watch: the player says so instead of nothing. */
    val moving: StateFlow<Boolean> = _moving.asStateFlow()

    private var moveJob: Job? = null
    private var watchSelectionSeq = Long.MIN_VALUE

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
    fun moveToWatch(output: LocalOutput?, fromPhone: Boolean = false, handoff: Boolean = true) {
        val parts = standalone()
        if (output == null) {
            // No headphones, and the speaker is off in the settings: nowhere to play. Said, rather
            // than started and left to the system's default, which is the speaker.
            _errors.tryEmit(NO_OUTPUT)
            return
        }
        parts.router.chosen.value = output
        // Already the watch: picking headphones or the speaker in the output list only moves the
        // sound, which the service follows by itself. A request from the phone still moves its music.
        if (_mode.value == PlaybackMode.WATCH && !fromPhone) return
        watchSelectionSeq = remote.nowPlaying.value.snapshot?.seq ?: Long.MIN_VALUE
        _mode.value = PlaybackMode.WATCH
        local.connect()
        // A start that failed before (the phone away, no network) is tried again now.
        parts.engine.requestRetry()
        val phone = remote.nowPlaying.value
        val wasPlaying = if (handoff) phone.snapshot?.takeIf { it.track != null && (it.isPlaying || it.playWhenReady) } else null
        moveJob?.cancel()
        moveJob = scope.launch {
            val wait = if (parts.engine.firstSignIn) FIRST_SIGN_IN_WAIT_MS else ENGINE_WAIT_MS
            val status = withTimeoutOrNull(wait) {
                parts.engine.status.first { it != EngineStatus.OFF && it != EngineStatus.STARTING }
            }
            if (status != EngineStatus.RUNNING) {
                Log.w(TAG, "watch engine not up: $status")
                _errors.tryEmit(engineError(status))
                backToPhone()
                return@launch
            }
            if (wasPlaying == null) return@launch
            _moving.value = true
            try {
                if (!parts.engine.awaitConnected(CONNECT_WAIT_MS)) Log.w(TAG, "watch not in Connect yet; trying anyway")
                val watchId = parts.prefs.deviceId
                if (remote.transferAndWait(watchId) && playingHere()) return@launch
                // A transfer that landed late counts too: every step first looks.
                if (playingNow()) return@launch
                Log.i(TAG, "the phone's transfer did not start the watch; pulling")
                try {
                    withContext(Dispatchers.IO) { NativeBridge.pullPlayback() }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (refused: Exception) {
                    Log.i(TAG, "pull refused: ${refused.message}")
                }
                if (playingHere()) return@launch
                Log.i(TAG, "pull did not start the watch; starting the same song here")
                if (startHere(wasPlaying, resumePosition(phone)) && playingHere()) {
                    // The phone's own player, and only if it still plays: a pause, not a toggle,
                    // and never sent while the phone shows a Connect device — that device may now
                    // be this watch, and "pause" would stop the music that just arrived.
                    val after = remote.nowPlaying.value.snapshot
                    if (after?.source == PlaybackSource.PHONE && (after.isPlaying || after.playWhenReady)) remote.pause()
                    return@launch
                }
                _errors.tryEmit(AckErrors.TRANSFER)
                // Nothing moved: the phone still has the music, and the player shows it again.
                backToPhone()
            } finally {
                _moving.value = false
            }
        }
    }

    /** A move that did not happen: the phone is the player again, and the watch's lets go. */
    private fun backToPhone() {
        if (_mode.value != PlaybackMode.WATCH) return
        _mode.value = PlaybackMode.PHONE
        local.disconnect()
    }

    private fun playingNow(): Boolean =
        local.nowPlaying.value.snapshot?.let { it.track != null && (it.isPlaying || it.playWhenReady) } == true

    /** Waits a little for the watch's own player to be playing something. */
    private suspend fun playingHere(): Boolean = withTimeoutOrNull(START_WAIT_MS) {
        local.nowPlaying.first { now -> now.snapshot?.let { it.track != null && (it.isPlaying || it.playWhenReady) } == true }
        true
    } ?: false

    /**
     * Where the phone's song is now, for starting it here by hand: 0 near its end (a snapshot
     * older than the rest of the song says the position ran out, and seeking there would skip it).
     */
    private fun resumePosition(phone: NowPlaying): Long {
        val duration = phone.snapshot?.track?.durationMs ?: 0
        val position = phone.positionAt(System.currentTimeMillis())
        return if (duration > 0 && position >= duration - RESUME_END_MARGIN_MS) 0 else position
    }

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
     * Hands the music back to the phone (or to [deviceId], a Connect device the phone can reach).
     *
     * The player switches once the phone has taken it, not before: a transfer that failed (the
     * phone away, music the account cannot see because it plays from the watch's downloads) used
     * to leave the watch playing in the background while the player showed and drove the phone.
     */
    fun moveToPhone(deviceId: String) {
        moveJob?.cancel()
        _moving.value = false
        moveJob = scope.launch {
            if (!remote.transferAndWait(deviceId)) {
                _errors.tryEmit(AckErrors.TRANSFER)
                return@launch
            }
            _mode.value = PlaybackMode.PHONE
            // Long enough for Connect to have moved the music off the watch; the service then
            // stops itself once it has been idle, and the engine with it.
            delay(HANDBACK_MS)
            if (_mode.value == PlaybackMode.PHONE) local.stop()
        }
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
    private val _visualSeek = MutableStateFlow(0L)
    val visualSeek: StateFlow<Long> = _visualSeek.asStateFlow()
    override fun seekTo(positionMs: Long) { _visualSeek.value++; front.seekTo(positionMs) }
    override fun setShuffle(enabled: Boolean) = front.setShuffle(enabled)
    override fun setRepeat(mode: RepeatMode) = front.setRepeat(mode)
    override fun setLiked(liked: Boolean) = front.setLiked(liked)
    override fun playContext(contextUri: String, startTrackUri: String?, shuffle: Boolean, label: String) {
        // No phone to play it on, but the watch has it: play it here, which is the point
        // of keeping it.
        // Known to be away, not merely not heard from yet: a cold start says UNKNOWN for a moment.
        val phoneAway = remote.nowPlaying.value.link in PHONE_AWAY
        if (_mode.value == PlaybackMode.PHONE && phoneAway && keptOnWatch(contextUri)) {
            // What was picked, not a handoff: the phone's last word (stale, or music still going
            // at home) must not be pulled over the playlist the listener just chose.
            moveToWatch(standalone().router.best, handoff = false)
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

    companion object {
        private const val TAG = "ActivePlayback"

        /** Wi-Fi coming up plus a handshake with a credential the watch already has. */
        private const val ENGINE_WAIT_MS = 30_000L

        /** A first sign-in: Wi-Fi (12 s), the phone's token (up to 20 s) and the handshake. */
        private const val FIRST_SIGN_IN_WAIT_MS = 50_000L

        /** Closer than this to the end of the phone's song, starting it here begins from the top. */
        private const val RESUME_END_MARGIN_MS = 5_000L

        /** A phone snapshot younger than this is news, not the last word of a phone that went away. */

        private val PHONE_AWAY = setOf(
            dev.pampa.fluidify.wear.link.LinkStatus.UNREACHABLE,
            dev.pampa.fluidify.wear.link.LinkStatus.NOT_FOUND,
            dev.pampa.fluidify.wear.link.LinkStatus.NO_ANSWER,
        )

        /** Watch-side reasons a move did not happen; see ErrorMessages. */
        const val NO_OUTPUT = "no-output"
        const val ENGINE_NEEDS_PHONE = "engine-needs-phone"
        const val ENGINE_SIGNED_OUT = "engine-signed-out"
        const val ENGINE_PREMIUM = "engine-premium"
        const val ENGINE_FAILED = "engine-failed"

        fun engineError(status: EngineStatus?): String = when (status) {
            EngineStatus.NEEDS_PHONE -> ENGINE_NEEDS_PHONE
            EngineStatus.SIGNED_OUT -> ENGINE_SIGNED_OUT
            EngineStatus.PREMIUM_REQUIRED -> ENGINE_PREMIUM
            else -> ENGINE_FAILED
        }
        private const val HANDBACK_MS = 6_000L

        /** From "engine started" to "the account can see the watch". */
        private const val CONNECT_WAIT_MS = 20_000L

        /** How long a transfer gets to make the watch's player start. */
        private const val START_WAIT_MS = 6_000L

        /** How long a sign-out waits for the engine to stop before deleting what it signed in with. */
        private const val SIGN_OUT_WAIT_MS = 5_000L

        /** Below this, starting from the top is as good as resuming. */
        private const val RESUME_MIN_MS = 3_000L
    }
}
