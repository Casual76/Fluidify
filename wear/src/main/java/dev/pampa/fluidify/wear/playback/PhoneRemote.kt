package dev.pampa.fluidify.wear.playback

import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.link.CommandChannel
import dev.pampa.fluidify.wear.link.ReceivedSnapshot
import dev.pampa.fluidify.wear.link.WatchState
import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.RepeatMode
import dev.pampa.fluidify.wear.protocol.logic.PositionExtrapolator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        return PositionExtrapolator.positionAt(r.snapshot, r.receivedAtMs, nowMs)
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

    /** The guess layered over the phone's last word, while a command is in flight. */
    private val optimistic = MutableStateFlow<ReceivedSnapshot?>(null)
    private val inFlight = MutableStateFlow(0)
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    override val nowPlaying: StateFlow<NowPlaying> =
        combine(state.current, optimistic, link.status, inFlight) { real, guess, status, busy ->
            // The guess stands until the phone has spoken after it.
            val shown = if (guess != null && (real == null || real.snapshot.seq <= guess.snapshot.seq)) guess else real
            NowPlaying(shown, status, busy > 0)
        }.stateIn(scope, SharingStarted.Eagerly, NowPlaying(state.current.value, link.status.value))

    override fun togglePlay() {
        val current = nowPlaying.value
        val snapshot = current.snapshot
        val now = System.currentTimeMillis()
        val guess = snapshot?.let {
            val playing = !(it.isPlaying || it.playWhenReady)
            it.copy(
                isPlaying = playing,
                playWhenReady = playing,
                positionMs = current.positionAt(now),
                sampledAtEpochMs = now,
                sentAtEpochMs = now,
            )
        }
        dispatch(Command.TogglePlay, guess)
    }

    override fun next() = dispatch(Command.Next, null)

    override fun previous() = dispatch(Command.Previous, null)

    override fun seekTo(positionMs: Long) {
        val now = System.currentTimeMillis()
        val guess = nowPlaying.value.snapshot?.copy(positionMs = positionMs, sampledAtEpochMs = now, sentAtEpochMs = now)
        dispatch(Command.SeekTo(positionMs), guess)
    }

    override fun setShuffle(enabled: Boolean) =
        dispatch(Command.SetShuffle(enabled), nowPlaying.value.snapshot?.copy(shuffle = enabled))

    override fun setRepeat(mode: RepeatMode) =
        dispatch(Command.SetRepeat(mode), nowPlaying.value.snapshot?.copy(repeat = mode))

    override fun setLiked(liked: Boolean) {
        val uri = nowPlaying.value.snapshot?.track?.uri ?: return
        dispatch(Command.SetLiked(uri, liked), nowPlaying.value.snapshot?.copy(liked = liked))
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
    suspend fun transferAndWait(deviceId: String): Boolean =
        link.send(Command.Transfer(deviceId), TRANSFER_ACK_MS)?.ok == true

    override fun setVolume(level: Float, deviceId: String?) {
        val snapshot = nowPlaying.value.snapshot
        val guess = snapshot?.device?.let { device -> snapshot.copy(device = device.copy(volume = level.coerceIn(0f, 1f))) }
        dispatch(Command.SetVolume(level.coerceIn(0f, 1f), deviceId), guess)
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

    private companion object {
        /** The phone's own waits for a transfer (device listed, queue republished) plus margin. */
        const val TRANSFER_ACK_MS = 9_000L
    }

    private fun dispatch(command: Command, guess: PlaybackSnapshot?) {
        val now = System.currentTimeMillis()
        if (guess != null) optimistic.value = ReceivedSnapshot(guess, now)
        inFlight.value += 1
        scope.launch {
            val ack = link.send(command)
            inFlight.value -= 1
            if (ack == null || !ack.ok) {
                optimistic.value = null
                _errors.tryEmit(ack?.error ?: "unreachable")
            } else if (ack.appliedSeq != null) {
                // The phone's snapshot carrying the change is at least appliedSeq; once it is
                // here the guess is no longer needed. If it is already here, drop it now.
                val real = state.current.value
                if (real != null && real.snapshot.seq >= ack.appliedSeq!!) optimistic.value = null
            }
        }
    }
}
