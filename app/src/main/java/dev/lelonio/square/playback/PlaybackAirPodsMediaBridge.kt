@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.media.LocalAirPodsMediaBridge
import dev.lelonio.square.airpods.runtime.media.MediaCandidate
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import java.io.Closeable
import java.util.UUID

/** Process-local only. Reading this object never constructs, starts or binds a service. */
object PlaybackAirPodsMediaBridge : LocalAirPodsMediaBridge {
    private var provider: LocalAirPodsMediaBridge? = null
    private var subscription: Closeable? = null
    private var generation = 0L
    private val observers = linkedSetOf<() -> Unit>()

    internal fun attach(value: LocalAirPodsMediaBridge): Closeable {
        val mine = ++generation
        subscription?.close()
        (provider as? Closeable)?.close()
        provider = value
        subscription = value.observe { if (generation == mine) changed() }
        changed()
        return Closeable {
            if (generation == mine) {
                ++generation
                subscription?.close(); subscription = null
                provider = null
                (value as? Closeable)?.close()
                changed()
            }
        }
    }
    internal fun changed() { observers.toList().forEach { runCatching { it() } } }
    override fun read(pair: PairAddress): MediaCandidate? = runCatching { provider?.read(pair) }.getOrNull()
    override fun observe(changed: () -> Unit): Closeable {
        observers.add(changed)
        return Closeable { observers.remove(changed) }
    }
    override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?) =
        runCatching { provider?.execute(target, action, value, effectId) ?: false }.getOrDefault(false)
    override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) =
        runCatching { provider?.gesture(target, action) ?: false }.getOrDefault(false)
}

internal data class AirPodsRoutedDevice(val id: Int, val type: Int, val address: String?)
internal data class AirPodsOutputRoute(val generation: Long, val devices: List<AirPodsRoutedDevice>, val active: Boolean)

/** Retention is historical renderer evidence, never substituted for a new current route. */
internal class AirPodsRouteEvidence {
    private var retained: Pair<Long, AirPodsRoutedDevice>? = null
    var historical = false
        private set
    fun invalidate() { retained = null }
    fun read(route: AirPodsOutputRoute, pair: PairAddress, playing: Boolean,
             connected: Set<Int>): String? {
        historical = !playing
        val sole = route.devices.singleOrNull()
        if (sole != null && sole.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
            sole.address?.equals(pair.value, ignoreCase = true) == true && sole.id in connected) {
            if (playing && route.active) retained = route.generation to sole
            else if (playing || retained != (route.generation to sole)) { invalidate(); return null }
            return "audiotrack:${route.generation}:${sole.id}"
        }
        if (route.devices.isNotEmpty() || playing) { invalidate(); return null }
        val old = retained ?: return null
        if (old.first != route.generation || old.second.id !in connected ||
            old.second.address?.equals(pair.value, ignoreCase = true) != true) {
            invalidate(); return null
        }
        return "audiotrack:${old.first}:${old.second.id}"
    }
}

/** Main-looper provider tied to exactly one raw player. No native engine calls or second duck. */
internal class PlaybackAirPodsProvider(
    context: Context,
    private val raw: Player,
    private val output: AudioOutput?,
    private val accountEpoch: () -> Long,
    private val attenuationActive: () -> Boolean,
    private val routeSnapshot: () -> AirPodsOutputRoute? = { output?.readAirPodsRoute() },
    private val connectedDeviceIds: (() -> Set<Int>)? = null,
    private val remotePlayback: () -> Boolean = { (raw as? LibrespotPlayer)?.airPodsAutomationLocal == false },
) : LocalAirPodsMediaBridge, Closeable {
    private val applicationId = context.packageName
    private val manager = context.getSystemService(AudioManager::class.java)
    private val resolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val sessionId = UUID.randomUUID().toString()
    private val sessionGeneration = nextGeneration++
    private val evidence = AirPodsRouteEvidence()
    private val exoOutput = AirPodsExoOutputs.get(raw)
    private var pauseEffect: Long? = null
    private var volumeEffect: Long? = null
    // Our dispatched PLAY, with a bounded Exo wait followed by an origin-bound application receipt.
    private var resumeEffect: Long? = null
    private var resumeDeadline = 0L
    private val resumeTimeout = Runnable {
        if (resumeEffect != null && raw !is LibrespotPlayer && !ownResumeConfirmed()) {
            revokeTransport(); signal()
        }
    }
    private var ownTransport = false
    private var ownVolume = false
    private var pauseAwaiting = false
    private val observers = linkedSetOf<() -> Unit>()
    private var closed = false
    private var pair: PairAddress? = null
    private var transportRevision = 0L
    private var volumeRevision = 0L
    private var lastEpoch = accountEpoch()
    private var lastVolume: MediaVolume? = null
    private var lastRouteId: String? = null
    private var lastAttenuation = false
    private fun signal(invalidateRoute: Boolean = false) {
        if (closed) return
        if (invalidateRoute) { evidence.invalidate(); revokeTransport(); revokeVolume() }
        observers.toList().forEach { runCatching { it() } }
    }
    private fun postRouteChange() { handler.post { signal(true) } }
    private fun revokeTransport() {
        handler.removeCallbacks(resumeTimeout); resumeDeadline = 0L
        pauseEffect = null; pauseAwaiting = false; resumeEffect = null; exoOutput?.revoke()
        transportRevision++
    }
    /** Receipt for exactly our PLAY, while still requested; a coincident state is not one. */
    private fun ownResumeConfirmed(): Boolean {
        val id = resumeEffect ?: return false
        return raw.playWhenReady && (if (raw is LibrespotPlayer) raw.airPodsPlayReceipt == id
            else exoOutput?.let { it.ownsPlay(id) && it.playApplied == id } == true)
    }
    /** Preserve only existing authority while this exact Exo command awaits its sink receipt. */
    private fun ownResumePending(): Boolean {
        val id = resumeEffect ?: return false
        return raw !is LibrespotPlayer && raw.playWhenReady &&
            android.os.SystemClock.elapsedRealtime() < resumeDeadline && exoOutput?.ownsPlay(id) == true
    }
    private fun revokeVolume() { volumeEffect = null; volumeRevision++ }
    private fun streamVolume(): MediaVolume? = runCatching {
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (maximum > 0) MediaVolume(current.coerceIn(0, maximum), maximum) else null
    }.getOrNull()
    private fun observeStreamVolume(): MediaVolume? = streamVolume().also { current ->
        // Revoke immediately on an observed difference, even if a later read returns
        // to our level. A Settings callback alone has no writer or volume identity.
        if (current != lastVolume) revokeVolume()
        lastVolume = current
    }
    private fun manualTransport() { revokeTransport(); revokeVolume(); signal() }
    val sessionPlayer: Player = object : ForwardingPlayer(raw) {
        override fun play() { manualTransport(); super.play() }
        override fun pause() { manualTransport(); super.pause() }
        override fun setPlayWhenReady(value: Boolean) { manualTransport(); super.setPlayWhenReady(value) }
        override fun stop() { manualTransport(); super.stop() }
        override fun setVolume(value: Float) { revokeVolume(); signal(); super.setVolume(value) }
        override fun seekTo(positionMs: Long) { manualTransport(); super.seekTo(positionMs) }
        override fun seekTo(index: Int, positionMs: Long) { manualTransport(); super.seekTo(index, positionMs) }
        override fun seekBack() { manualTransport(); super.seekBack() }
        override fun seekForward() { manualTransport(); super.seekForward() }
        override fun seekToDefaultPosition() { manualTransport(); super.seekToDefaultPosition() }
        override fun seekToDefaultPosition(index: Int) { manualTransport(); super.seekToDefaultPosition(index) }
        override fun seekToNext() { manualTransport(); super.seekToNext() }
        override fun seekToPrevious() { manualTransport(); super.seekToPrevious() }
        override fun seekToNextMediaItem() { manualTransport(); super.seekToNextMediaItem() }
        override fun seekToPreviousMediaItem() { manualTransport(); super.seekToPreviousMediaItem() }
    }
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) signal(true)
        }
        override fun onEvents(player: Player, events: Player.Events) {
            if (closed) return
            // Controlled pause/resume may consume only their own matching transitions.
            // Position ticks, metadata and generic callbacks never create receipts.
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) || events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (!ownTransport && !(pauseAwaiting && !raw.playWhenReady) &&
                    !ownResumeConfirmed() && !ownResumePending()) revokeTransport()
            }
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_TIMELINE_CHANGED)) revokeTransport()
            if (events.contains(Player.EVENT_VOLUME_CHANGED)) revokeVolume()
            signal()
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = signal(true)
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = signal(true)
    }
    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // Notifications also arrive asynchronously after our own setStreamVolume,
            // or for unrelated Settings keys. They never create ownership. Preserve an
            // existing controlled write/readback only while the observed domain matches.
            if (!ownVolume) observeStreamVolume()
            signal()
        }
    }
    private val routeObserver = output?.observeAirPodsRouting(::postRouteChange)
    private val exoRouteObserver = exoOutput?.observeRoute(::postRouteChange)
    private val exoCommandObserver = exoOutput?.observeCommand { revokeTransport(); revokeVolume(); signal() }
    private val exoObserver = exoOutput?.observe { handler.post { signal() } }
    private val commandObserver = (raw as? LibrespotPlayer)?.observeAirPodsCommands {
        if (!ownTransport) manualTransport()
    }
    private val focusObserver = (raw as? LibrespotPlayer)?.observeAirPodsFocus {
        transportRevision++; volumeRevision++; signal(true)
    }
    init {
        raw.addListener(listener)
        manager.registerAudioDeviceCallback(deviceCallback, handler)
        resolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }
    override fun observe(changed: () -> Unit): Closeable {
        observers.add(changed)
        return Closeable { observers.remove(changed) }
    }
    override fun read(pair: PairAddress): MediaCandidate? {
        if (closed) return null
        if (this.pair != pair) { evidence.invalidate(); revokeTransport(); revokeVolume(); this.pair = pair }
        val epoch = accountEpoch()
        if (epoch != lastEpoch) {
            lastEpoch = epoch; revokeTransport(); revokeVolume(); evidence.invalidate()
        }
        val remote = remotePlayback()
        val playing = raw.isPlaying
        val route = routeSnapshot() ?: exoOutput?.read()
        val devices = runCatching { connectedDeviceIds?.invoke() ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet() }
            .getOrDefault(emptySet())
        // Exo may publish PLAYING before its existing AudioTrack resumes. Preserve only the
        // same historical renderer proof during our origin-scoped, bounded pending PLAY.
        val rendererPlaying = playing && !(ownResumePending() && route?.active == false)
        val routeId = if (!remote && route != null) evidence.read(route, pair, rendererPlaying, devices) else null
        if (lastRouteId != routeId) { revokeTransport(); revokeVolume(); lastRouteId = routeId }
        if (remote) { evidence.invalidate(); revokeTransport(); revokeVolume() }
        val volume = observeStreamVolume()
        val foreignAttenuation = attenuationActive() ||
            (raw as? LibrespotPlayer)?.airPodsForeignDuckActive == true ||
            (exoOutput != null && (exoOutput.focusBlocked || exoOutput.focusMultiplier < raw.volume)) || raw.volume < 1f
        if (foreignAttenuation && !lastAttenuation) { revokeTransport(); revokeVolume() }
        lastAttenuation = foreignAttenuation
        return MediaCandidate(
            MediaTarget(applicationId, sessionId, sessionGeneration, routeId ?: "unavailable", epoch.toString()),
            if (playing) Playback.PLAYING else if (!raw.playWhenReady && raw.mediaItemCount > 0) Playback.PAUSED else Playback.UNKNOWN,
            volume, local = true, permitted = true, routedToPair = routeId != null,
            callActive = manager.mode != AudioManager.MODE_NORMAL,
            foreignDuckActive = foreignAttenuation,
            observedPauseEffect = pauseEffect?.takeIf { id ->
                !raw.playWhenReady && (if (raw is LibrespotPlayer) raw.airPodsPauseReceipt == id
                    else exoOutput?.pauseApplied == id)
            },
            observedVolumeEffect = volumeEffect,
            routeEvidence = if (routeId == null) RouteEvidence.UNAVAILABLE
                else if (evidence.historical) RouteEvidence.LOCAL_HISTORICAL_RENDERER else RouteEvidence.LOCAL_RENDERER,
            transportRevision = transportRevision, volumeRevision = volumeRevision,
            // A controller's UNKNOWN is otherwise ambiguous. Only this local producer knows
            // that no item exists and no PLAY is pending; remote and buffering stay contenders.
            knownEmptyLocal = !remote && !playing && !raw.playWhenReady && raw.mediaItemCount == 0,
            unavailableReason = when {
                remote -> "REMOTE_PLAYBACK"
                output == null && exoOutput == null -> "RENDERER_ROUTE_UNOBSERVABLE"
                routeId == null -> "LOCAL_RENDERER_ROUTE_UNAVAILABLE"
                attenuationActive() -> "LOCAL_ATTENUATION_ACTIVE"
                else -> null
            },
        )
    }
    private fun valid(target: MediaTarget): Boolean {
        val current = pair?.let(::read) ?: return false
        return current.target == target && current.routedToPair && !current.callActive
    }
    override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
        if (!valid(target)) return false
        return runCatching {
            when (action) {
                MediaAction.PAUSE -> {
                    if (!raw.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return false
                    if (effectId == null) { manualTransport(); raw.pause() }
                    else {
                        if (!raw.isPlaying) return false
                        pauseEffect = effectId; pauseAwaiting = true; resumeEffect = null
                        ownTransport = true
                        exoOutput?.controlledCommand = true
                        try {
                            if (raw is LibrespotPlayer) {
                                if (!raw.applyAirPodsPlayback(effectId, false)) { revokeTransport(); return false }
                            } else {
                                val sink = exoOutput ?: run { revokeTransport(); return false }
                                sink.armPause(effectId)
                                raw.pause()
                            }
                        } finally { ownTransport = false; exoOutput?.controlledCommand = false }
                    }
                }
                MediaAction.PLAY -> {
                    if (!raw.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return false
                    if (effectId != null) {
                        if (read(pair ?: return false)?.observedPauseEffect == null) return false
                        resumeEffect = effectId
                        ownTransport = true
                        exoOutput?.controlledCommand = true
                        try {
                            if (raw is LibrespotPlayer) {
                                if (!raw.applyAirPodsPlayback(effectId, true)) { revokeTransport(); return false }
                            } else {
                                val sink = exoOutput ?: run { revokeTransport(); return false }
                                sink.armPlay(effectId)
                                resumeDeadline = android.os.SystemClock.elapsedRealtime() + 2_000L
                                handler.removeCallbacks(resumeTimeout)
                                handler.postDelayed(resumeTimeout, 2_000L)
                                raw.play()
                                if (!raw.playWhenReady) { revokeTransport(); return false }
                            }
                        } catch (failure: Exception) {
                            revokeTransport(); throw failure
                        } finally { ownTransport = false; exoOutput?.controlledCommand = false }
                        pauseEffect = null; pauseAwaiting = false
                    } else { manualTransport(); raw.play() }
                }
                MediaAction.SET_VOLUME -> {
                    val level = value ?: return false
                    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    if (level !in 0..max || manager.isVolumeFixed) return false
                    if (effectId != null && read(pair ?: return false)?.foreignDuckActive != false) return false
                    if (effectId == null) revokeVolume()
                    volumeEffect = null
                    ownVolume = true
                    try { manager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
                    finally { ownVolume = false }
                    val applied = streamVolume()
                    lastVolume = applied
                    if (applied?.current == level && applied.maximum == max) volumeEffect = effectId
                    else { revokeVolume(); return false }
                }
            }
            signal(); true
        }.getOrDefault(false)
    }
    override fun gesture(target: MediaTarget, action: AirPodsMediaGesture): Boolean {
        if (!valid(target)) return false
        manualTransport()
        return runCatching {
            when (action) {
                AirPodsMediaGesture.NEXT -> {
                    if (!raw.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) return false
                    raw.seekToNext()
                }
                AirPodsMediaGesture.PREVIOUS -> {
                    if (!raw.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) return false
                    raw.seekToPrevious()
                }
                AirPodsMediaGesture.PLAY -> return execute(target, MediaAction.PLAY, null, null)
                AirPodsMediaGesture.PAUSE -> return execute(target, MediaAction.PAUSE, null, null)
                AirPodsMediaGesture.TOGGLE_PLAYBACK -> return execute(target,
                    if (raw.playWhenReady) MediaAction.PAUSE else MediaAction.PLAY, null, null)
            }
            true
        }.getOrDefault(false)
    }
    override fun close() {
        if (closed) return
        closed = true
        runCatching { raw.removeListener(listener) }
        runCatching { routeObserver?.close() }
        runCatching { focusObserver?.close() }
        runCatching { commandObserver?.close() }
        runCatching { exoObserver?.close() }
        runCatching { exoRouteObserver?.close() }
        runCatching { exoCommandObserver?.close() }
        exoOutput?.revoke()
        runCatching { manager.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { resolver.unregisterContentObserver(volumeObserver) }
        handler.removeCallbacksAndMessages(null)
        evidence.invalidate(); observers.clear()
    }
    companion object { private var nextGeneration = 1L }
}
