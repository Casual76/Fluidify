package dev.pampa.fluidify.wear.playback

import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.link.CommandChannel
import dev.pampa.fluidify.wear.link.I12CommandRequest
import dev.pampa.fluidify.wear.link.I12CommandTimeouts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import dev.pampa.fluidify.wear.link.ReceivedSnapshot
import dev.pampa.fluidify.wear.link.WatchState
import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.RepeatMode
import dev.pampa.fluidify.wear.protocol.logic.PositionExtrapolator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the player screen draws. */
data class NowPlaying(
    val received: ReceivedSnapshot?,
    val link: LinkStatus,
    /** A command is on its way and its result is not known yet. */
    val busy: Boolean = false,
) {
    val snapshot: PlaybackSnapshot? get() = received?.snapshot

    /** Where the song is at [nowMs], on this watch's clock. */
    fun positionAt(nowMs: Long): Long {
        val r = received ?: return 0
        return PositionExtrapolator.positionAt(r.snapshot, r.receivedAtMs, nowMs, r.clockOffsetMs)
    }
}

/**
 * What the watch's controls talk to.
 *
 * One interface so the screens never know where the music is: the phone today
 * ([PhoneRemote]), the watch's own engine later. Commands return at once; the
 * screen reads the result from [nowPlaying].
 */
interface PlaybackControls {
    val nowPlaying: StateFlow<NowPlaying>
    val errors: SharedFlow<String>
    fun togglePlay()
    /** Absolute transport intent. Implementations must not invert their current player state. */
    fun setPlaying(playing: Boolean)
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun setShuffle(enabled: Boolean)
    fun setRepeat(mode: RepeatMode)
    fun setLiked(liked: Boolean)
    fun playContext(contextUri: String, startTrackUri: String? = null, shuffle: Boolean = false, label: String = "")
    fun playQueueIndex(index: Int, uri: String)
    fun addToQueue(uri: String)
    fun startRadio()
    fun transfer(deviceId: String)
    fun setVolume(level: Float, deviceId: String? = null)
    fun sleep(minutes: Int? = null, atTrackEnd: Boolean = false, cancel: Boolean = false)

    /**
     * Adds [trackUri] to the end of [playlistUri], and says whether it went in. Suspends, unlike
     * the rest: the screen that asks waits for the answer before saying "added". A failure is
     * also reported on [errors].
     */
    suspend fun addToPlaylist(playlistUri: String, trackUri: String): Boolean
}

/**
 * The phone as a player.
 *
 * Optimistic where the outcome is certain: play/pause, shuffle, repeat, the
 * heart and a seek change the screen the moment they are pressed, because the
 * phone will do exactly that. The phone's acknowledgement then either confirms
 * the guess (the next snapshot carries it) or takes it back, and a guess the
 * phone never answers is taken back after the ack timeout. A skip is not
 * guessed: what comes next is the phone's to say, and it says it in a few
 * hundred milliseconds.
 */
class PhoneRemote(
    private val scope: CoroutineScope,
    private val state: WatchState,
    private val link: CommandChannel,
) : PlaybackControls {

    /**
     * A guess layered over the phone's last word, while a command is in flight: the state it
     * should lead to, and how to tell a snapshot that shows it.
     */
    private class Guess(val shown: ReceivedSnapshot, val confirmedBy: (PlaybackSnapshot) -> Boolean, val request: I12CommandRequest)

    private val optimistic = MutableStateFlow<Guess?>(null)
    private val inFlight = MutableStateFlow(0)
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Bumped when the snapshot held turns stale, so the state is worked out again without a new one. */
    private val staleTick = MutableStateFlow(0)

    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    override val nowPlaying: StateFlow<NowPlaying> =
        combine(state.current, optimistic, link.status, inFlight, staleTick) { real, guess, status, busy, _ ->
            // The guess stands until the phone has shown it — not merely spoken after it: the
            // snapshot sent with the ack is often taken before the player has moved, and dropping
            // the guess on it made the play button flick back and forth on every press.
            val shown = if (guess != null && guess.request.ifCurrent {} && (real == null || real.snapshot.seq <= guess.shown.snapshot.seq || !guess.confirmedBy(real.snapshot))) {
                guess.shown
            } else {
                real
            }
            NowPlaying(shown?.let(::believable), status, busy > 0)
        }.stateIn(scope, SharingStarted.Eagerly, NowPlaying(state.current.value?.let(::believable), link.status.value))

    init {
        // A snapshot that says "playing" turns stale when its song would have ended long ago;
        // worked out again then, without waiting for news that may not come.
        scope.launch {
            state.current.collectLatest { received ->
                received ?: return@collectLatest
                val end = PositionExtrapolator.endsAtLocal(received.snapshot, received.receivedAtMs, received.clockOffsetMs) ?: return@collectLatest
                val wait = end + PositionExtrapolator.STALE_GRACE_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait + STALE_CHECK_SLACK_MS)
                staleTick.value++
            }
        }
    }

    /**
     * [received] as it can be believed now: a "playing" whose song ended long ago, with nothing
     * heard since, is shown paused at its end — the phone was killed or went out of range.
     */
    private fun believable(received: ReceivedSnapshot): ReceivedSnapshot {
        val snapshot = received.snapshot
        if (!PositionExtrapolator.isStale(snapshot, received.receivedAtMs, System.currentTimeMillis(), received.clockOffsetMs)) return received
        return received.copy(
            snapshot = snapshot.copy(
                isPlaying = false,
                playWhenReady = false,
                positionMs = snapshot.track?.durationMs ?: snapshot.positionMs,
            ),
        )
    }

    override fun togglePlay() = setPlaying(nowPlaying.value.snapshot?.let { !it.isPlaying && !it.playWhenReady } ?: true)

    override fun setPlaying(playing: Boolean) {
        val now = System.currentTimeMillis()
        val current = nowPlaying.value
        val guess = current.snapshot?.copy(isPlaying = playing, playWhenReady = playing,
            positionMs = current.positionAt(now), sampledAtEpochMs = now, sentAtEpochMs = now)
        dispatch(if (playing) Command.Play else Command.Pause, guess) { real ->
            (real.isPlaying || real.playWhenReady) == playing
        }
    }

    /** Pauses the phone's player; nothing when it is already paused. */
    fun pause() = setPlaying(false)

    /**
     * Skips ahead, and shows the next song at once when the phone said which it is: the title and
     * the cover change on the press (the cover was sent ahead), not half a second later.
     */
    override fun next() {
        val snapshot = nowPlaying.value.snapshot
        val upcoming = snapshot?.nextTrack
        val now = System.currentTimeMillis()
        val guess = if (snapshot != null && upcoming != null) {
            snapshot.copy(
                track = upcoming,
                positionMs = 0,
                sampledAtEpochMs = now,
                sentAtEpochMs = now,
                liked = null,
                nextTrack = null,
                nextArtKey = null,
            )
        } else {
            null
        }
        dispatch(Command.Next, guess) { real -> real.track?.uri == upcoming?.uri }
    }

    override fun previous() = dispatch(Command.Previous, null)

    override fun seekTo(positionMs: Long) {
        val now = System.currentTimeMillis()
        val guess = nowPlaying.value.snapshot?.copy(positionMs = positionMs, sampledAtEpochMs = now, sentAtEpochMs = now)
        dispatch(Command.SeekTo(positionMs), guess)
    }

    override fun setShuffle(enabled: Boolean) =
        dispatch(Command.SetShuffle(enabled), nowPlaying.value.snapshot?.copy(shuffle = enabled)) { it.shuffle == enabled }

    override fun setRepeat(mode: RepeatMode) =
        dispatch(Command.SetRepeat(mode), nowPlaying.value.snapshot?.copy(repeat = mode)) { it.repeat == mode }

    override fun setLiked(liked: Boolean) {
        val uri = nowPlaying.value.snapshot?.track?.uri ?: return
        dispatch(Command.SetLiked(uri, liked), nowPlaying.value.snapshot?.copy(liked = liked)) { it.track?.uri != uri || it.liked == liked }
    }

    override fun playContext(contextUri: String, startTrackUri: String?, shuffle: Boolean, label: String) =
        dispatch(Command.PlayContext(contextUri, startTrackUri, shuffle, label), null)

    override fun playQueueIndex(index: Int, uri: String) = dispatch(Command.PlayQueueIndex(index, uri), null)

    override fun addToQueue(uri: String) = dispatch(Command.AddToQueue(uri), null)

    override fun startRadio() {
        val uri = nowPlaying.value.snapshot?.track?.uri ?: return
        dispatch(Command.StartRadio(uri), null)
    }

    override fun transfer(deviceId: String) = dispatch(Command.Transfer(deviceId), null)

    /**
     * Asks the phone to move the account's playback to [deviceId] and waits for the answer — the
     * phone first waits for the device to be listed, then republishes its queue, so this takes
     * longer than a button press.
     */
    internal fun prepareTransfer(deviceId: String): I12CommandRequest =
        link.prepare(Command.Transfer(deviceId), I12CommandTimeouts.TRANSFER_ACK_MS)

    suspend fun transferAndWait(deviceId: String): Boolean = awaitTransfer(prepareTransfer(deviceId))

    internal suspend fun awaitTransfer(request: I12CommandRequest, onConfirmed: () -> Unit = {}): Boolean {
        val ack = request.await()
        currentCoroutineContext().ensureActive()
        return ack?.ok == true && request.ifCurrent(onConfirmed)
    }

    override fun setVolume(level: Float, deviceId: String?) {
        val snapshot = nowPlaying.value.snapshot
        val guess = snapshot?.device?.let { device -> snapshot.copy(device = device.copy(volume = level.coerceIn(0f, 1f))) }
        dispatch(Command.SetVolume(level.coerceIn(0f, 1f), deviceId), guess) { real ->
            val volume = real.device?.volume
            volume == null || kotlin.math.abs(volume - level.coerceIn(0f, 1f)) < VOLUME_MATCH
        }
    }

    override fun sleep(minutes: Int?, atTrackEnd: Boolean, cancel: Boolean) {
        val snapshot = nowPlaying.value.snapshot
        val now = System.currentTimeMillis()
        val guess = snapshot?.copy(
            sleep = when {
                cancel -> null
                atTrackEnd -> dev.pampa.fluidify.wear.protocol.SleepInfo(atTrackEnd = true)
                minutes != null -> dev.pampa.fluidify.wear.protocol.SleepInfo(endsAtEpochMs = now + minutes * 60_000L)
                else -> snapshot.sleep
            },
        )
        dispatch(Command.SleepTimer(minutes, atTrackEnd, cancel), guess)
    }

    override suspend fun addToPlaylist(playlistUri: String, trackUri: String): Boolean {
        val request = link.prepare(Command.AddToPlaylist(playlistUri, trackUri), I12CommandTimeouts.WRITE_ACK_MS)
        val ack = request.await()
        val current = request.ifCurrent {
            if (ack?.ok != true) _errors.tryEmit(ack?.error ?: dev.pampa.fluidify.wear.protocol.AckErrors.UNREACHABLE)
        }
        return current && ack?.ok == true
    }

    private companion object {
        /** How long a confirmed command's guess may stand while the phone's state catches up. */
        const val GUESS_HOLD_MS = 2_000L
        const val VOLUME_MATCH = 0.02f
        const val STALE_CHECK_SLACK_MS = 100L
    }

    /**
     * Sends [command], showing [guess] until a snapshot [confirms] it (or, once the phone has said
     * yes, for [GUESS_HOLD_MS] at most). A command the phone refused, or never answered, takes the
     * guess back at once and says why.
     */
    private fun dispatch(command: Command, guess: PlaybackSnapshot?, confirms: (PlaybackSnapshot) -> Boolean = { true }) {
        val now = System.currentTimeMillis()
        val request = link.prepare(command, I12CommandTimeouts.forCommand(command))
        val batteryReceipt = nowPlaying.value.received?.airPodsBatteryReceipt
            ?.takeIf { it.battery == guess?.airPodsBattery }
        val placed = guess?.let { Guess(ReceivedSnapshot(it, now, airPodsBatteryReceipt = batteryReceipt), confirms, request) }
        if (placed != null) optimistic.value = placed
        inFlight.update { it + 1 }
        scope.launch {
            val ack = try {
                request.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (optimistic.value === placed) optimistic.value = null
                throw cancelled
            } finally { inFlight.update { it - 1 } }
            val current = request.ifCurrent {
                if (ack == null || !ack.ok) {
                    if (optimistic.value === placed) optimistic.value = null
                    _errors.tryEmit(ack?.error ?: dev.pampa.fluidify.wear.protocol.AckErrors.UNREACHABLE)
                }
            }
            if (!current) {
                if (optimistic.value === placed) optimistic.value = null
            } else if (ack?.ok == true && placed != null) {
                delay(GUESS_HOLD_MS)
                if (optimistic.value === placed) optimistic.value = null
            }
        }
    }
}
