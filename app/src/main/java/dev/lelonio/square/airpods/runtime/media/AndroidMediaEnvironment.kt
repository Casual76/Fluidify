// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.database.ContentObserver
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import dev.lelonio.square.playback.PlaybackService
import java.io.Closeable

/** Android discovery with conservative per-target routing. No privileged API or media-key fallback. */
class AndroidMediaEnvironment(context: Context, private val local: LocalAirPodsMediaBridge? = null) : MediaRuntimeEnvironment {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(app, AirPodsMediaNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    internal var pair: PairAddress? = null
    private val generations = mutableMapOf<MediaSession.Token, Long>()
    private var nextGeneration = 0L
    private val external = ExternalBestEffortProvider(AutomationClock { SystemClock.elapsedRealtime() })
    private var externalEnabled = false
    private var a2dp: BluetoothA2dp? = null
    private var requestExternalProfile: (() -> Unit)? = null
    private var changed: (() -> Unit)? = null
    override fun configureExternal(enabled: Boolean) {
        externalEnabled = enabled
        external.configure(enabled)
        // Visibility can enable C6 after observe() was installed. Acquire its profile
        // through that same observer, without resetting sensor facts or media leases.
        if (enabled) requestExternalProfile?.invoke()
    }

    private fun playback(c: MediaController) = when (c.playbackState?.state) {
        PlaybackState.STATE_PLAYING -> Playback.PLAYING
        PlaybackState.STATE_PAUSED -> Playback.PAUSED
        else -> Playback.UNKNOWN
    }

    /** Only BUFFERING/CONNECTING may be an app executing our own PLAY; stop/error/skip/seek/none intervene. */
    private fun interruption(state: PlaybackState?) = when (state?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> false
        else -> true
    }

    @Suppress("DEPRECATION")
    private fun facts(selected: PairAddress, call: Boolean): ExternalMediaFacts = try {
        val connected = a2dp?.connectedDevices.orEmpty()
        val exact = connected.singleOrNull()?.takeIf { it.address.equals(selected.value, true) }
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outputs.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val coherent = audio.isBluetoothA2dpOn && bt.size == 1 &&
            (Build.VERSION.SDK_INT < 28 || bt.single().address.equals(selected.value, true))
        // API26/27 lacks AudioDeviceInfo.address: the exact singleton profile device supplies
        // identity; the available output and system A2DP flag remain only system inference.
        val key = "a2dp-${selected.hashCode()}:" + outputs.sortedBy { it.id }.joinToString { "${it.id}/${it.type}" }
        ExternalMediaFacts(nlsGranted() && bluetoothGranted(), exact != null,
            exact != null && a2dp?.isA2dpPlaying(exact) == true, coherent, key, call)
    } catch (_: SecurityException) {
        // Revocation during either profile read invalidates all C6 routing/streaming evidence.
        ExternalMediaFacts(false, false, false, false, "unavailable", call)
    } catch (_: Throwable) {
        ExternalMediaFacts(false, false, false, false, "unavailable", call)
    }

    private fun nlsGranted(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 27)
            app.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
        else app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app)
    }.getOrDefault(false)

    private fun bluetoothGranted() = Build.VERSION.SDK_INT < 31 ||
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun controllers(): List<MediaController> = if (!nlsGranted()) emptyList() else
        runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList())

    override fun readPhoneVolume(): PhoneStreamVolume? {
        val selected = pair ?: return null
        if (!bluetoothGranted()) return null
        return runCatching {
            if (audio.mode != AudioManager.MODE_NORMAL || audio.isVolumeFixed) return null
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (maximum <= 0 || current !in 0..maximum) return null
            PhoneStreamVolume(selected.value, MediaVolume(current, maximum))
        }.getOrNull()
    }

    override fun writePhoneVolume(expected: PhoneStreamVolume, value: Int): Boolean {
        val now = readPhoneVolume() ?: return false
        if (now.domain != expected.domain || now.volume.maximum != expected.volume.maximum ||
            value !in 0..now.volume.maximum) return false
        // Deliberately no target/provider.requested/receipt, transport, focus or service acquisition.
        return runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); true }
            .getOrDefault(false)
    }

    override fun read(): List<MediaCandidate> {
        val selected = pair ?: return emptyList()
        val call = runCatching { audio.mode != AudioManager.MODE_NORMAL }.getOrDefault(true)
        val localCandidate = runCatching { local?.read(selected) }.getOrNull()?.let { c ->
            c.copy(local = true, permitted = c.permitted && bluetoothGranted() &&
                c.target.accountEpoch == PlaybackService.mediaAccountEpoch.toString(),
                callActive = c.callActive || call)
        }
        val active = controllers()
        val liveTokens = active.map { it.sessionToken }.toSet()
        generations.keys.retainAll(liveTokens)
        val external = active.filter { it.packageName != app.packageName }.map { controller ->
            val generation = generations.getOrPut(controller.sessionToken) { ++nextGeneration }
            val info = controller.playbackInfo
            val remote = info?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
            val target = MediaTarget(controller.packageName, "android-session-$generation", generation,
                if (remote) "remote" else "unverified-local-output", "external-$generation")
            val state = playback(controller)
            // Public PlaybackInfo describes local/remote and volume, not the actual output address.
            // Available devices / anticipated USAGE_MEDIA route cannot prove this session's route.
            val volume = runCatching {
                MediaVolume(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            }.getOrNull()
            MediaCandidate(target, state, volume, false, nlsGranted() && bluetoothGranted(), false, call,
                unavailableReason = when {
                    remote -> "REMOTE_PLAYBACK"
                    info?.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL -> "UNKNOWN_PLAYBACK_TYPE"
                    else -> "SESSION_ACTIVE_ROUTE_UNOBSERVABLE"
                })
        }
        return this.external.candidates(listOfNotNull(localCandidate) + external,
            facts(selected, call).copy(foreignDuckActive = localCandidate?.foreignDuckActive == true))
    }

    override fun observe(changed: () -> Unit): Closeable {
        this.changed = changed
        var closed = false
        val registered = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
        fun signal() { if (!closed) changed() }
        fun reconcile() {
            if (closed) return
            val current = controllers()
            val tokens = current.map { it.sessionToken }.toSet()
            registered.keys.filter { it !in tokens }.toList().forEach { key ->
                registered.remove(key)?.let { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
            }
            current.forEach { c -> if (c.sessionToken !in registered) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (closed || !registered.containsKey(c.sessionToken)) return
                        // Recheck permissions, identity and topology before accepting correlation.
                        read()
                        external.currentTarget()?.takeIf {
                            it.sessionGeneration == generations[c.sessionToken] && it.applicationId == c.packageName
                        }?.let { external.playbackObserved(it, when (state?.state) {
                            PlaybackState.STATE_PLAYING -> Playback.PLAYING
                            PlaybackState.STATE_PAUSED -> Playback.PAUSED
                            else -> Playback.UNKNOWN
                        }, state?.lastPositionUpdateTime, interruption = interruption(state)) }
                        signal()
                    }
                    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {
                        if (closed || !registered.containsKey(c.sessionToken)) return
                        read()
                        external.currentTarget()?.takeIf {
                            it.sessionGeneration == generations[c.sessionToken] && it.applicationId == c.packageName
                        }?.let { external.volumeObserved(it, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
                        signal()
                    }
                    override fun onSessionDestroyed() = signal()
                }
                runCatching { c.registerCallback(callback, handler) }.onSuccess {
                    registered[c.sessionToken] = c to callback
                }
            } }
            signal()
        }
        val active = MediaSessionManager.OnActiveSessionsChangedListener { reconcile() }
        var listening = false
        fun attach() {
            if (!listening && nlsGranted()) listening = runCatching {
                sessions.addOnActiveSessionsChangedListener(active, listener, handler); true
            }.getOrDefault(false)
            reconcile()
        }
        val access = AirPodsMediaNotificationListener.observe { handler.post { if (!closed) attach() } }
        val localObserver = local?.observe { signal() }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        var acquiredProxy: BluetoothProfile? = null
        val profile = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(kind: Int, proxy: BluetoothProfile) {
                if (kind != BluetoothProfile.A2DP) return
                if (closed) { runCatching { adapter?.closeProfileProxy(kind, proxy) }; return }
                acquiredProxy = proxy
                a2dp = proxy as? BluetoothA2dp; signal()
            }
            override fun onServiceDisconnected(kind: Int) {
                if (!closed && kind == BluetoothProfile.A2DP) { a2dp = null; external.invalidate(); signal() }
            }
        }
        var proxyRequested = false
        val requestProfile: () -> Unit = {
            if (!closed && !proxyRequested && externalEnabled && bluetoothGranted()) {
                proxyRequested = runCatching {
                    adapter?.getProfileProxy(app, profile, BluetoothProfile.A2DP) == true
                }.getOrDefault(false)
            }
        }
        requestExternalProfile = requestProfile
        requestProfile()
        val bluetooth = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (closed) return
                // Streaming stop is expected after own PAUSE. Connection/topology changes are not.
                if (intent?.action != BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED) external.invalidate()
                signal()
            }
        }
        ContextCompat.registerReceiver(app, bluetooth, IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        fun observeVolume() {
            if (closed) return
            val candidates = read()
            external.currentTarget()?.let { t -> candidates.singleOrNull { it.target == t }?.volume?.let {
                external.volumeObserved(t, it.current)
            } }
            signal()
        }
        val volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = observeVolume()
        }
        app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        val modeListener = if (Build.VERSION.SDK_INT >= 31) AudioManager.OnModeChangedListener {
            if (!closed) { external.invalidate(); signal() }
        } else null
        if (Build.VERSION.SDK_INT >= 31 && modeListener != null)
            audio.addOnModeChangedListener(java.util.concurrent.Executor { handler.post(it) }, modeListener)
        val device = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { external.invalidate(); signal() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { external.invalidate(); signal() }
        }
        audio.registerAudioDeviceCallback(device, handler)
        attach()
        return Closeable {
            closed = true
            if (this.changed === changed) this.changed = null
            if (requestExternalProfile === requestProfile) requestExternalProfile = null
            access.close(); localObserver?.close()
            runCatching { app.unregisterReceiver(bluetooth) }
            runCatching { app.contentResolver.unregisterContentObserver(volumeObserver) }
            if (Build.VERSION.SDK_INT >= 31 && modeListener != null)
                runCatching { audio.removeOnModeChangedListener(modeListener) }
            acquiredProxy?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
            acquiredProxy = null; a2dp = null
            external.invalidate()
            runCatching { audio.unregisterAudioDeviceCallback(device) }
            if (listening) runCatching { sessions.removeOnActiveSessionsChangedListener(active) }
            registered.values.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
            registered.clear()
        }
    }

    override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
        val current = read().singleOrNull { it.target == target } ?: return false
        if (!current.permitted || !current.routedToPair || current.callActive) return false
        if (action == MediaAction.SET_VOLUME && effectId != null && current.foreignDuckActive) return false
        if (current.local) return runCatching { local?.execute(target, action, value, effectId) == true }.getOrDefault(false)
        if (!externalEnabled || current.evidence != MediaEvidence.EXTERNAL_BEST_EFFORT) return false
        val controller = controllers().singleOrNull { generations[it.sessionToken] == target.sessionGeneration &&
            it.packageName == target.applicationId } ?: return false
        external.requested(current, action, value, effectId)
        return runCatching {
            when (action) {
                MediaAction.PAUSE -> controller.transportControls.pause()
                MediaAction.PLAY -> controller.transportControls.play()
                MediaAction.SET_VOLUME -> {
                    require(value != null && current.volume != null && value in 0..current.volume.maximum)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                    // Observe the real stream after dispatch; this is only C6 correlation.
                    val captured = external.currentEpoch()
                    handler.post {
                        if (externalEnabled && external.currentEpoch() == captured && external.currentTarget() == target) {
                            read()
                            if (external.currentEpoch() == captured) external.volumeObserved(target,
                                audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                            changed?.invoke()
                        }
                    }
                }
            }
            true
        }.getOrElse { external.invalidate(); false }
    }

    override fun gesture(target: MediaTarget, action: AirPodsMediaGesture): Boolean {
        val current = read().singleOrNull { it.target == target } ?: return false
        if (!current.permitted || !current.routedToPair || current.callActive) return false
        if (current.local) return runCatching { local?.gesture(target, action) == true }.getOrDefault(false)
        if (!externalEnabled || current.evidence != MediaEvidence.EXTERNAL_BEST_EFFORT) return false
        val controller = controllers().singleOrNull { generations[it.sessionToken] == target.sessionGeneration &&
            it.packageName == target.applicationId } ?: return false
        external.invalidate() // explicit transport forfeits all automatic ownership
        return runCatching {
            when (action) {
                AirPodsMediaGesture.NEXT -> controller.transportControls.skipToNext()
                AirPodsMediaGesture.PREVIOUS -> controller.transportControls.skipToPrevious()
                AirPodsMediaGesture.PAUSE -> controller.transportControls.pause()
                AirPodsMediaGesture.PLAY -> controller.transportControls.play()
                AirPodsMediaGesture.TOGGLE_PLAYBACK -> if (current.playback == Playback.PLAYING)
                    controller.transportControls.pause() else controller.transportControls.play()
            }; true
        }.getOrDefault(false)
    }
}
