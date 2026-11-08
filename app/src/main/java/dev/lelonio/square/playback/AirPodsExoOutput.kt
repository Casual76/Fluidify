@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import android.media.AudioTrack
import android.os.Build
import androidx.media3.common.Player
import java.util.WeakHashMap

/** One observation surface per existing sink. Never creates a decoder or changes its defaults. */
internal class AirPodsExoOutput {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var generation = 0L
    @Volatile var pauseApplied: Long? = null
        private set
    @Volatile var pendingPause: Long? = null
    private val commandEpoch = java.util.concurrent.atomic.AtomicLong()
    var enqueuePauseOrigin: ((Long, Long) -> Unit)? = null
    fun armPause(effect: Long) {
        val epoch = commandEpoch.get()
        enqueuePauseOrigin?.invoke(effect, epoch) ?: run { pendingPause = effect }
    }
    fun applyPauseOrigin(effect: Long, epoch: Long) {
        if (commandEpoch.get() == epoch) pendingPause = effect
    }
    @Volatile var controlledCommand = false
    @Volatile var focusBlocked = false
    @Volatile var focusMultiplier = 1f
    private val commandObservers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val routeObservers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun foreignFocus() { routeObservers.forEach { runCatching { it() } } }
    fun command() { if (!controlledCommand) { revoke(); commandObservers.forEach { it() } } }
    fun observeCommand(callback: () -> Unit): java.io.Closeable {
        commandObservers.add(callback); return java.io.Closeable { commandObservers.remove(callback) }
    }
    fun observeRoute(callback: () -> Unit): java.io.Closeable {
        routeObservers.add(callback); return java.io.Closeable { routeObservers.remove(callback) }
    }
    private val observers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val routeListener = android.media.AudioRouting.OnRoutingChangedListener { routeObservers.forEach { it() } }
    private fun changed() { observers.forEach { runCatching { it() } } }
    @Synchronized fun trackCreated(value: AudioTrack) {
        track?.let { runCatching { it.removeOnRoutingChangedListener(routeListener) } }
        track = value; generation++; pauseApplied = null
        value.addOnRoutingChangedListener(routeListener, android.os.Handler(android.os.Looper.getMainLooper()))
        changed()
    }
    @Synchronized fun reset() {
        track?.let { runCatching { it.removeOnRoutingChangedListener(routeListener) } }
        track = null; generation++; revoke(); changed()
    }
    fun revoke() { commandEpoch.incrementAndGet(); pendingPause = null; pauseApplied = null }
    fun paused() {
        // Called only after ForwardingAudioSink.pause has applied to the owned AudioTrack.
        pauseApplied = pendingPause.takeIf { track?.playState == AudioTrack.PLAYSTATE_PAUSED }
        pendingPause = null
        changed()
    }
    fun observe(callback: () -> Unit): java.io.Closeable {
        observers.add(callback)
        return java.io.Closeable { observers.remove(callback) }
    }
    @Synchronized fun read(): AirPodsOutputRoute {
        val output = track
        val devices = runCatching {
            if (output == null || output.state != AudioTrack.STATE_INITIALIZED) emptyList()
            else if (Build.VERSION.SDK_INT >= 36) output.routedDevices else listOfNotNull(output.routedDevice)
        }.getOrDefault(emptyList())
        return AirPodsOutputRoute(generation, devices.map {
            AirPodsRoutedDevice(it.id, it.type, if (Build.VERSION.SDK_INT >= 28) it.address else null)
        }, output?.playState == AudioTrack.PLAYSTATE_PLAYING)
    }
}

internal object AirPodsExoOutputs {
    private val outputs = WeakHashMap<Player, AirPodsExoOutput>()
    fun bind(player: Player, output: AirPodsExoOutput) { outputs[player] = output }
    fun get(player: Player): AirPodsExoOutput? = outputs[player]
}

/** Keeps ExoPlayer's public interface, with one observable focus owner for local files and video. */
internal class AirPodsFocusedExoPlayer(
    private val delegate: androidx.media3.exoplayer.ExoPlayer,
    context: android.content.Context,
    private val output: AirPodsExoOutput,
) : androidx.media3.exoplayer.ExoPlayer by delegate {
    init {
        // Origin is ordered on Exo's playback queue ahead of our pause, after older messages.
        output.enqueuePauseOrigin = { effect, epoch ->
            delegate.createMessage { _, _ -> output.applyPauseOrigin(effect, epoch) }.send()
        }
    }
    private var duckBaseline: Float? = null
    private val focus = AudioFocusController(context,
        onPause = { output.command(); delegate.pause() },
        onResume = { output.command(); if (focusAllowed()) delegate.play() },
        onDuck = { ducked ->
            output.command()
            if (ducked && duckBaseline == null) {
                duckBaseline = delegate.volume
                delegate.volume *= 0.2f
            } else if (!ducked) {
                duckBaseline?.let { delegate.volume = it }; duckBaseline = null
            }
        })
    private val focusObservation = focus.observeAirPodsFocus {
        output.focusBlocked = focus.foreignAttenuationActive
        output.foreignFocus()
        output.command()
    }
    private fun focusAllowed(): Boolean {
        val allowed = focus.requestFocus()
        output.focusBlocked = focus.foreignAttenuationActive || !allowed
        return allowed
    }
    override fun play() { output.command(); if (focusAllowed()) delegate.play() }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        output.command()
        if (!playWhenReady || focusAllowed()) delegate.playWhenReady = playWhenReady
        if (!playWhenReady) focus.abandonFocus()
    }
    override fun pause() { output.command(); delegate.pause(); focus.abandonFocus() }
    override fun stop() { output.command(); delegate.stop(); focus.abandonFocus() }
    override fun seekTo(positionMs: Long) { output.command(); delegate.seekTo(positionMs) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) { output.command(); delegate.seekTo(mediaItemIndex, positionMs) }
    override fun seekBack() { output.command(); delegate.seekBack() }
    override fun seekForward() { output.command(); delegate.seekForward() }
    override fun seekToDefaultPosition() { output.command(); delegate.seekToDefaultPosition() }
    override fun seekToDefaultPosition(mediaItemIndex: Int) { output.command(); delegate.seekToDefaultPosition(mediaItemIndex) }
    override fun seekToNext() { output.command(); delegate.seekToNext() }
    override fun seekToPrevious() { output.command(); delegate.seekToPrevious() }
    override fun seekToNextMediaItem() { output.command(); delegate.seekToNextMediaItem() }
    override fun seekToPreviousMediaItem() { output.command(); delegate.seekToPreviousMediaItem() }
    override fun setVolume(volume: Float) { output.command(); delegate.volume = volume }
    override fun release() { output.command(); focusObservation.close(); focus.release(); delegate.release(); output.reset() }
}
