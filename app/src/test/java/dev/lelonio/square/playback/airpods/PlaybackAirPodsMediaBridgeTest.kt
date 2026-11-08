@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.Player
import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.media.LocalAirPodsMediaBridge
import dev.lelonio.square.airpods.runtime.media.MediaCandidate
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import java.io.Closeable
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class PlaybackAirPodsMediaBridgeTest {
    private val pair = PairAddress("AA:BB:CC:DD:EE:FF")
    private val device = AirPodsRoutedDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pair.value)
    private fun route(active: Boolean = true, generation: Long = 1) = AirPodsOutputRoute(generation, listOf(device), active)

    @Test fun exactAddressAndSingleActualOutputRequired() {
        val evidence = AirPodsRouteEvidence()
        assertNull(evidence.read(route(), PairAddress("00:11:22:33:44:55"), true, setOf(7)))
        assertNull(evidence.read(route().copy(devices = listOf(device, device.copy(id = 8))), pair, true, setOf(7, 8)))
        assertNull(evidence.read(route().copy(devices = listOf(device.copy(address = null))), pair, true, setOf(7)))
        assertNull(evidence.read(route(), pair, true, emptySet()))
        assertNotNull(evidence.read(route(), pair, true, setOf(7)))
    }
    @Test fun pausedRouteRequiresPriorActiveEvidenceAndInvalidates() {
        val evidence = AirPodsRouteEvidence()
        val noCurrentRoute = route(false).copy(devices = emptyList())
        assertNull(evidence.read(route(false), pair, false, setOf(7)))
        val initial = evidence.read(route(), pair, true, setOf(7))
        assertNotNull(initial)
        assertEquals(initial, evidence.read(noCurrentRoute, pair, false, setOf(7)))
        evidence.invalidate() // same invalidation used by device, routing, focus and account callbacks
        assertNull(evidence.read(noCurrentRoute, pair, false, setOf(7)))
        evidence.read(route(), pair, true, setOf(7))
        assertNull(evidence.read(noCurrentRoute.copy(generation = 2), pair, false, setOf(7)))
        evidence.read(route(), pair, true, setOf(7))
        assertNull(evidence.read(noCurrentRoute, pair, false, emptySet()))
    }
    @Test fun contradictoryRouteCannotRetainOldEvidence() {
        val evidence = AirPodsRouteEvidence()
        evidence.read(route(), pair, true, setOf(7))
        assertNull(evidence.read(route(false).copy(devices = listOf(device.copy(type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))), pair, false, setOf(7)))
        assertNull(evidence.read(route(false).copy(devices = emptyList()), pair, false, setOf(7)))
    }
    private class Raw {
        var playing = true
        var pauses = 0
        var plays = 0
        var listeners = 0
        var paused: () -> Unit = {}
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getApplicationLooper" -> Looper.getMainLooper()
                "isPlaying", "getPlayWhenReady" -> playing
                "getMediaItemCount" -> 1
                "isCommandAvailable" -> true
                "getPlaybackState" -> Player.STATE_READY
                "getVolume" -> 1f
                "pause" -> { pauses++; playing = false; paused(); null }
                "play" -> { plays++; playing = true; null }
                "setPlayWhenReady" -> { playing = args!![0] as Boolean; null }
                "addListener" -> { listeners++; null }
                "removeListener" -> { listeners--; null }
                "equals" -> false
                "hashCode" -> 17
                "toString" -> "test player"
                else -> null
            }
        } as Player
    }
    private class Fixture {
        val raw = Raw()
        var epoch = 1L
        var remote = false
        var route: AirPodsOutputRoute? = null
        val context = RuntimeEnvironment.getApplication()
        val sink = AirPodsExoOutput()
        val track = android.media.AudioTrack.Builder()
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(44100)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(4096).build()
        init {
            track.play(); sink.trackCreated(track)
            AirPodsExoOutputs.bind(raw.player, sink)
            raw.paused = { track.pause(); sink.paused() }
        }
        val provider = PlaybackAirPodsProvider(context, raw.player, null, { epoch }, { false },
            { route }, { setOf(7) }, { remote })
    }
    @Test fun accountSwitchInFlightRemoteAndUnknownRouteRejectEffects() {
        val f = Fixture()
        try {
            assertFalse(f.provider.read(pair)!!.routedToPair)
            f.route = route()
            val old = f.provider.read(pair)!!.target
            f.epoch++
            assertFalse(f.provider.execute(old, MediaAction.PAUSE, null, 1))
            assertEquals(0, f.raw.pauses)
            val fresh = f.provider.read(pair)!!.target
            f.remote = true
            assertFalse(f.provider.execute(fresh, MediaAction.PAUSE, null, 1))
            assertFalse(f.provider.read(pair)!!.routedToPair)
        } finally { f.provider.close() }
    }
    @Test fun appliedSinkPauseOwnsReceiptAndAllowsOwnResume() {
        val f = Fixture()
        try {
            f.route = route()
            val target = f.provider.read(pair)!!.target
            assertTrue(f.provider.execute(target, MediaAction.PAUSE, null, 42))
            assertEquals(42L, f.provider.read(pair)!!.observedPauseEffect)
            assertEquals(RouteEvidence.LOCAL_HISTORICAL_RENDERER, f.provider.read(pair)!!.routeEvidence)
            assertTrue(f.provider.execute(target, MediaAction.PLAY, null, 43))
            assertEquals(1, f.raw.plays)
        } finally { f.provider.close() }
    }
    @Test fun matchingPlayerStateWithoutSinkApplicationCannotResume() {
        val f = Fixture()
        try {
            f.route = route(); f.raw.paused = {}
            val target = f.provider.read(pair)!!.target
            assertTrue(f.provider.execute(target, MediaAction.PAUSE, null, 42))
            assertNull(f.provider.read(pair)!!.observedPauseEffect)
            assertFalse(f.provider.execute(target, MediaAction.PLAY, null, 43))
        } finally { f.provider.close() }
    }
    @Test fun manualSameStatePauseRevokesAppliedReceipt() {
        val f = Fixture()
        try {
            f.route = route(); val target = f.provider.read(pair)!!.target
            f.provider.execute(target, MediaAction.PAUSE, null, 42)
            assertNotNull(f.provider.read(pair)!!.observedPauseEffect)
            f.provider.sessionPlayer.pause()
            assertNull(f.provider.read(pair)!!.observedPauseEffect)
            assertFalse(f.provider.execute(target, MediaAction.PLAY, null, 43))
        } finally { f.provider.close() }
    }
    @Test fun delegatedSeekIntentsRevokePauseAndVolumeEvenWithoutStateCallback() {
        val commands: List<(Player) -> Unit> = listOf(
            { it.seekBack() }, { it.seekForward() },
            { it.seekToDefaultPosition() }, { it.seekToDefaultPosition(0) },
        )
        val f = Fixture(); f.route = route()
        try {
            commands.forEachIndexed { index, command ->
                f.raw.playing = true; f.track.play()
                val target = f.provider.read(pair)!!.target
                assertTrue(f.provider.execute(target, MediaAction.SET_VOLUME, 1, 100L + index))
                assertTrue(f.provider.execute(target, MediaAction.PAUSE, null, 200L + index))
                val owned = f.provider.read(pair)!!
                assertNotNull(owned.observedPauseEffect)
                assertNotNull(owned.observedVolumeEffect)
                // This fake has no seek state callbacks: the explicit application
                // command must still revoke, including a same-position seek.
                command(f.provider.sessionPlayer)
                val after = f.provider.read(pair)!!
                assertTrue(after.transportRevision > owned.transportRevision)
                assertNull(after.observedPauseEffect)
                assertNull(after.observedVolumeEffect)
                assertFalse(f.provider.execute(target, MediaAction.PLAY, null, 300L + index))
            }
            assertEquals(0, f.raw.plays)
        } finally { f.provider.close(); f.track.release() }
    }
    @Test fun manualPauseAlreadyPausedHasRevisionAndCleanupNeverPlays() {
        val f = Fixture()
        f.route = route()
        val before = f.provider.read(pair)!!.transportRevision
        f.raw.playing = false
        f.provider.sessionPlayer.pause()
        assertTrue(f.provider.read(pair)!!.transportRevision > before)
        f.provider.close(); f.provider.close()
        assertEquals(0, f.raw.plays)
        assertEquals(0, f.raw.listeners)
        assertNull(f.provider.read(pair))
    }
    @Test fun streamMusicAppliedWriteHasReceiptAndManualWriteRevokesIt() {
        val f = Fixture()
        try {
            f.route = route()
            val c = f.provider.read(pair)!!
            assertFalse(c.foreignDuckActive)
            assertTrue(f.provider.execute(c.target, MediaAction.SET_VOLUME, 1, 66))
            assertEquals(66L, f.provider.read(pair)!!.observedVolumeEffect)
            val manager = f.context.getSystemService(AudioManager::class.java)
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val wanted = minOf(2, max)
            assertTrue(f.provider.execute(c.target, MediaAction.SET_VOLUME, wanted, null))
            assertEquals(manager.getStreamVolume(AudioManager.STREAM_MUSIC), f.provider.read(pair)!!.volume!!.current)
            assertNull(f.provider.read(pair)!!.observedVolumeEffect)
            assertFalse(f.provider.execute(c.target, MediaAction.SET_VOLUME, max + 1, null))
        } finally { f.provider.close() }
    }
    @Test fun earRemovalThroughCoordinatorAndAppliedSinkResumesOnReinsertion() {
        val f = Fixture(); f.route = route()
        val session = AutomationSession("pair", 1)
        val effects = mutableListOf<MediaEffect>()
        val coordinator = AirPodsAutomationCoordinator(object : MediaHost {
            override fun submit(effect: MediaEffect): Boolean { effects.add(effect); return true }
            override fun cancel(lease: Long) {}
            override fun cancelEffect(effect: MediaEffect) {}
        }, AutomationClock { 0 })
        var sequence = 0L
        fun observe() {
            val c = f.provider.read(pair)!!
            coordinator.onMedia(MediaSnapshot(session, ++sequence, c.target, c.playback, c.volume,
                true, true, c.routedToPair, observedPauseEffect = c.observedPauseEffect,
                foreignDuckActive = c.foreignDuckActive))
        }
        try {
            coordinator.configure(AutomationConfig("pair", enabled = true, earEnabled = true), 1)
            observe(); coordinator.onEar(session, EarState.BOTH); coordinator.onEar(session, EarState.NONE)
            val pause = effects.single()
            assertTrue(f.provider.execute(pause.target, pause.action, pause.volume, pause.id))
            observe(); assertTrue(coordinator.status().ownsPause)
            coordinator.onEar(session, EarState.BOTH)
            val play = effects.last(); assertEquals(MediaAction.PLAY, play.action)
            assertTrue(f.provider.execute(play.target, play.action, play.volume, play.id))
            assertEquals(1, f.raw.plays)
        } finally { f.provider.close() }
    }
    @Test fun caDuckReadbackSurvivesOwnSettingsNotificationAndRestoresOneStreamMusicDomain() {
        val f = Fixture(); f.route = route()
        val session = AutomationSession("pair", 1)
        val effects = mutableListOf<MediaEffect>()
        val coordinator = AirPodsAutomationCoordinator(object : MediaHost {
            override fun submit(effect: MediaEffect): Boolean { effects.add(effect); return true }
            override fun cancel(lease: Long) {}
            override fun cancelEffect(effect: MediaEffect) {}
        }, AutomationClock { 0 })
        var sequence = 0L
        fun observe() {
            val c = f.provider.read(pair)!!
            coordinator.onMedia(MediaSnapshot(session, ++sequence, c.target, c.playback, c.volume,
                true, true, true, observedVolumeEffect = c.observedVolumeEffect, foreignDuckActive = c.foreignDuckActive))
        }
        fun applyLast() {
            val effect = effects.last()
            assertTrue(f.provider.execute(effect.target, effect.action, effect.volume, effect.id)); observe()
        }
        try {
            val manager = f.context.getSystemService(AudioManager::class.java)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            val initial = f.provider.read(pair)!!.volume!!.current
            coordinator.configure(AutomationConfig("pair", enabled = true, conversationEnabled = true,
                conversationDuck = true, volumePercent = 40, rampMillis = 0), 1)
            observe(); coordinator.onConversation(session, false); coordinator.onConversation(session, true)
            applyLast(); assertTrue(f.provider.read(pair)!!.volume!!.current < initial)
            // Android may persist and notify after setStreamVolume has returned.
            f.context.contentResolver.notifyChange(android.provider.Settings.System.getUriFor("volume_music"), null)
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            observe()
            coordinator.onConversation(session, false); applyLast()
            assertEquals(initial, f.provider.read(pair)!!.volume!!.current)
            assertEquals(0, f.raw.plays)
        } finally { f.provider.close() }
    }
    @Test fun settingsNotificationWithoutControlledApplicationCannotCreateVolumeReceipt() {
        val f = Fixture(); f.route = route()
        try {
            assertNull(f.provider.read(pair)!!.observedVolumeEffect)
            f.context.contentResolver.notifyChange(android.provider.Settings.System.CONTENT_URI, null)
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNull(f.provider.read(pair)!!.observedVolumeEffect)
        } finally { f.provider.close() }
    }
    @Test fun observedManualVolumeDivergencePermanentlyRevokesEvenBeforeSettingsCallback() {
        val f = Fixture(); f.route = route()
        try {
            val target = f.provider.read(pair)!!.target
            assertTrue(f.provider.execute(target, MediaAction.SET_VOLUME, 1, 67))
            assertEquals(67L, f.provider.read(pair)!!.observedVolumeEffect)
            val manager = f.context.getSystemService(AudioManager::class.java)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
            assertNull(f.provider.read(pair)!!.observedVolumeEffect)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
            assertNull(f.provider.read(pair)!!.observedVolumeEffect)
            f.context.contentResolver.notifyChange(android.provider.Settings.System.CONTENT_URI, null)
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNull(f.provider.read(pair)!!.observedVolumeEffect)
        } finally { f.provider.close() }
    }
    private class FakeBridge : LocalAirPodsMediaBridge, Closeable {
        var callback: (() -> Unit)? = null
        var closed = 0
        override fun read(pair: PairAddress): MediaCandidate? = null
        override fun observe(changed: () -> Unit): Closeable {
            callback = changed
            return Closeable { callback = null }
        }
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?) = false
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = false
        override fun close() { closed++ }
    }
    @Test fun noServiceAndStaleProviderDestroyNeverDetachReplacement() {
        assertNull(PlaybackAirPodsMediaBridge.read(pair))
        val first = FakeBridge()
        val oldLease = PlaybackAirPodsMediaBridge.attach(first)
        val staleCallback = first.callback!!
        val second = FakeBridge()
        val lease = PlaybackAirPodsMediaBridge.attach(second)
        var changes = 0
        val observer = PlaybackAirPodsMediaBridge.observe { changes++ }
        try {
            staleCallback(); oldLease.close()
            assertEquals(0, changes)
            assertEquals(1, first.closed)
            assertEquals(0, second.closed)
            second.callback!!()
            assertEquals(1, changes)
        } finally { observer.close(); lease.close() }
        assertEquals(1, second.closed)
        assertNull(PlaybackAirPodsMediaBridge.read(pair))
    }
}
