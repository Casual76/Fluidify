// SPDX-License-Identifier: GPL-3.0-or-later
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.airpods.runtime.media

import android.Manifest
import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import dev.lelonio.square.playback.AirPodsOutputRoute
import dev.lelonio.square.playback.AirPodsRoutedDevice
import dev.lelonio.square.playback.CoreHost
import dev.lelonio.square.playback.LibrespotPlayer
import dev.lelonio.square.playback.PlayQueue
import dev.lelonio.square.playback.PlaybackAirPodsProvider
import java.io.Closeable
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Corrective-7, prepared and NOT executed in the source-only turn. Real LibrespotPlayer with the
 * native play/pause commands injected, real PlaybackAirPodsProvider, real MediaRuntimeCore and
 * coordinator. The environment mirrors only the LOCAL branch of AndroidMediaEnvironment. Engine
 * events are delivered exactly as LibrespotPlayer receives them. Not hardware, JNI or Binder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LocalResumeAuthorityRuntimeTest.Host::class, manifest = Config.NONE, sdk = [35])
class LocalResumeAuthorityRuntimeTest {
    class Host : Application(), CoreHost { override val qualityIsAutomatic = false }

    private val pair = PairAddress("AA:BB:CC:DD:EE:FF")
    private val uri = "spotify:track:one"

    private class LocalEnvironment(app: Application, val provider: PlaybackAirPodsProvider,
        val pair: PairAddress) : MediaRuntimeEnvironment {
        val audio: AudioManager = app.getSystemService(AudioManager::class.java)
        val executed = mutableListOf<MediaAction>()
        override fun readPhoneVolume(): PhoneStreamVolume? =
            PhoneStreamVolume(pair.value, MediaVolume(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
        override fun writePhoneVolume(expected: PhoneStreamVolume, value: Int): Boolean {
            if (readPhoneVolume() != expected) return false
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); return true
        }
        override fun read(): List<MediaCandidate> {
            val call = audio.mode != AudioManager.MODE_NORMAL
            return listOfNotNull(provider.read(pair)?.let { it.copy(local = true, callActive = it.callActive || call) })
        }
        override fun observe(changed: () -> Unit): Closeable = provider.observe(changed)
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            val current = read().singleOrNull { it.target == target } ?: return false
            if (!current.permitted || !current.routedToPair || current.callActive) return false
            if (action == MediaAction.SET_VOLUME && effectId != null && current.foreignDuckActive) return false
            executed += action
            return provider.execute(target, action, value, effectId)
        }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = provider.gesture(target, action)
    }

    private inner class Rig(val settings: HostSettings) {
        val app: Application = RuntimeEnvironment.getApplication()
        val audio: AudioManager = app.getSystemService(AudioManager::class.java)
        var pauses = 0
        var plays = 0
        val player = LibrespotPlayer(app, Looper.getMainLooper(), PlayQueue(),
            onSpeedAndPitch = { _, _ -> }, fadeOutThen = { it() }, fadeIn = {},
            onPlaybackActive = {}, onDuckReverb = {}, loadQueueCommand = { _, _, _, _, _, _, _ -> },
            refreshRemoteState = {}, airPodsPauseCommand = { pauses++ },
            airPodsPlayCommand = { plays++ }).apply { shutdownEngineOnRelease = false }
        var route = AirPodsOutputRoute(1, listOf(AirPodsRoutedDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pair.value)), true)
        val provider: PlaybackAirPodsProvider
        val env: LocalEnvironment
        val handler = Handler(Looper.getMainLooper())
        val queue = object : MediaRuntimeQueue {
            override fun post(block: () -> Unit) { handler.post { block() } }
            override fun later(delayMillis: Long, block: () -> Unit): Closeable {
                val r = Runnable { block() }; handler.postDelayed(r, delayMillis)
                return Closeable { handler.removeCallbacks(r) }
            }
        }
        val core: MediaRuntimeCore
        init {
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            audio.mode = AudioManager.MODE_NORMAL
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
            player.restore(listOf(PlayQueue.Track(uri, "One", "Artist", durationMs = 60000,
                artworkUri = null)), null, 0, 0, Player.REPEAT_MODE_OFF, null, false, "", handOverNow = true)
            idle(); emit("playing")
            provider = PlaybackAirPodsProvider(app, player, null, { 1L }, { false }, { route }, { setOf(7) }, { false })
            env = LocalEnvironment(app, provider, pair)
            core = MediaRuntimeCore(env, queue, AutomationClock { SystemClock.elapsedRealtime() })
            core.start(AutomationSession("pair", 7), settings)
            core.visible(true)
            idle()
        }
        fun idle(ms: Long = 0) {
            if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
            else shadowOf(Looper.getMainLooper()).idle()
        }
        fun emit(type: String, track: String = uri, positionMs: Long = 0) { player.onEvent(type, track, positionMs); idle() }
        fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        fun conversation(active: Boolean) { core.conversation(active); idle() }
        fun ear(value: EarState) { core.ear(value); idle() }
        fun transportSent() = env.executed.filter { it != MediaAction.SET_VOLUME }
        fun routingCallback() {
            PlaybackAirPodsProvider::class.java.getDeclaredMethod("postRouteChange").apply {
                isAccessible = true
            }.invoke(provider)
            idle()
        }
        /** CA pause+duck owned and confirmed natively, then CA end dispatches our PLAY. */
        fun ownedPauseAndDuckThenResume() {
            conversation(true); idle(50)
            assertEquals(1, pauses); emit("paused"); idle(800)
            assertEquals(4, volume())
            conversation(false); idle(10)
            assertEquals(1, plays)
        }
        fun close() { core.stop(); provider.close(); player.release() }
    }


    @Test fun nativePausePositionAcknowledgementRetainsItsOwnedEarResume() {
        val r = Rig(defaults.copy(earPause = true, earResume = true))
        val events = mutableListOf<String>()
        r.player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo, reason: Int) {
                events += "position:$reason:${oldPosition.positionMs}->${newPosition.positionMs}"
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                events += "timeline:$reason"
            }
        })
        try {
            r.emit("position", positionMs = 42_000)
            r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.idle(50)
            assertEquals(1, r.pauses)
            r.route = r.route.copy(active = false, devices = emptyList())
            r.routingCallback()
            events.clear()
            // Media3 1.11.1 reports an INTERNAL discontinuity at >= 1000 ms. The physical
            // trace corrected 1010 ms; the old +500 ms probe never exercised that callback.
            r.emit("paused", positionMs = 43_500)
            assertTrue("RIG: no INTERNAL correction: $events", events.any {
                it.startsWith("position:${Player.DISCONTINUITY_REASON_INTERNAL}:") })
            val observed = r.provider.read(pair)!!
            assertNotNull("native=${r.player.airPodsPauseReceipt}; route=${observed.routeEvidence}; " +
                "revision=${observed.transportRevision}; events=$events", observed.observedPauseEffect)
            r.ear(EarState.BOTH); r.idle(50)
            assertEquals("events=$events", 1, r.plays)
        } finally { r.close() }
    }

    @Test fun nativePauseAtTheLastObservedPositionKeepsItsReceiptControl() {
        val r = Rig(defaults.copy(earPause = true, earResume = true))
        try {
            r.emit("position", positionMs = 42_000)
            r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.idle(50)
            assertEquals(1, r.pauses)
            r.route = r.route.copy(active = false, devices = emptyList())
            r.routingCallback(); r.emit("paused", positionMs = 42_000)
            assertNotNull(r.provider.read(pair)!!.observedPauseEffect)
            r.ear(EarState.BOTH); r.idle(50)
            assertEquals(1, r.plays)
        } finally { r.close() }
    }

    @Test fun nativePlayPositionAcknowledgementRetainsConversationRestoration() {
        val r = Rig(defaults.copy(conversationDuck = true, conversationPause = true, earResume = false))
        try {
            r.ownedPauseAndDuckThenResume()
            r.route = r.route.copy(active = false, devices = emptyList())
            r.emit("playing", positionMs = 1_000)
            r.route = r.route.copy(active = true, devices = listOf(
                AirPodsRoutedDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pair.value)))
            r.routingCallback(); r.idle(800)
            assertEquals("native PLAY receipt=${r.player.airPodsPlayReceipt}", 10, r.volume())
        } finally { r.close() }
    }

    @Test fun manualSeekStillRevokesAnOwnedPauseControl() {
        val r = Rig(defaults.copy(earPause = true, earResume = true))
        try {
            r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.idle(50); r.emit("paused")
            assertNotNull(r.provider.read(pair)!!.observedPauseEffect)
            r.provider.sessionPlayer.seekTo(20_000); r.idle()
            assertNull(r.provider.read(pair)!!.observedPauseEffect)
            r.ear(EarState.BOTH); r.idle(50)
            assertEquals(0, r.plays)
        } finally { r.close() }
    }

    @Test fun unrelatedNativePositionCorrectionStillRevokesAnOwnedPause() {
        val r = Rig(defaults.copy(earPause = true, earResume = true))
        try {
            r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.idle(50); r.emit("paused")
            assertNotNull(r.provider.read(pair)!!.observedPauseEffect)
            r.emit("position", positionMs = 20_000)
            assertNull(r.provider.read(pair)!!.observedPauseEffect)
            r.ear(EarState.BOTH); r.idle(50)
            assertEquals(0, r.plays)
        } finally { r.close() }
    }

    @Test fun aForeignPositionInTheAcknowledgementBatchCannotBorrowItsOrigin() {
        val r = Rig(defaults.copy(earPause = true, earResume = true))
        try {
            r.emit("position", positionMs = 42_000)
            r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.idle(50)
            assertEquals(1, r.pauses)
            // Both native callbacks are queued before Media3's batched onEvents.
            r.player.onEvent("paused", uri, 43_500)
            r.player.onEvent("position", uri, 50_000)
            r.idle()
            assertNull(r.provider.read(pair)!!.observedPauseEffect)
            r.ear(EarState.BOTH); r.idle(50)
            assertEquals(0, r.plays)
        } finally { r.close() }
    }

    private val defaults = HostSettings(automationsEnabled = true, fluidifyBackgroundEnabled = true)

    @Test fun ownEarPauseSurvivesTheRenderersPauseRoutingCallback() {
        val r = Rig(defaults.copy(earPause = true, earResume = true))
        try {
            r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.idle(50)
            assertEquals(1, r.pauses)
            r.route = r.route.copy(active = false, devices = emptyList())
            r.routingCallback()
            r.emit("paused")
            val paused = r.provider.read(pair)!!
            assertNotNull("native=${r.player.airPodsPauseReceipt}; route=${paused.routeEvidence}; " +
                "routed=${paused.routedToPair}; playing=${r.player.isPlaying}; " +
                "requested=${r.player.playWhenReady}; revision=${paused.transportRevision}", paused.observedPauseEffect)
            r.ear(EarState.BOTH); r.idle(50)
            assertEquals(1, r.plays)
            r.route = r.route.copy(active = true, devices = listOf(
                AirPodsRoutedDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pair.value)))
            r.routingCallback(); r.emit("playing")
            assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), r.transportSent())
        } finally { r.close() }
    }

    @Test fun conversationVolumeRestoresWhenNativePlayingPrecedesRendererStart() {
        val r = Rig(defaults.copy(conversationDuck = true, conversationPause = true))
        try {
            r.conversation(true); r.idle(50); r.emit("paused"); r.idle(800)
            assertEquals(4, r.volume())
            r.route = r.route.copy(active = false)
            r.conversation(false); r.idle(150)
            assertEquals(1, r.plays)
            r.emit("playing"); r.routingCallback()
            assertTrue(r.provider.read(pair)!!.routedToPair)
            r.route = r.route.copy(active = true)
            r.routingCallback(); r.idle(800)
            assertEquals(10, r.volume())
        } finally { r.close() }
    }

    @Test fun actualRouteReplacementStillRevokesOwnedEarResume() {
        for (newGeneration in listOf(false, true)) {
            val r = Rig(defaults.copy(earPause = true, earResume = true))
            try {
                r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.idle(50); r.emit("paused")
                r.route = if (newGeneration) r.route.copy(generation = 2, active = false)
                    else r.route.copy(devices = listOf(AirPodsRoutedDevice(8,
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null)), active = false)
                r.routingCallback(); r.ear(EarState.BOTH); r.idle(50)
                assertEquals(0, r.plays)
                assertNull(r.provider.read(pair)!!.observedPauseEffect)
            } finally { r.close() }
        }
    }

    @Test fun aStoppedSinkWithoutTheNativePauseReceiptCannotGrantResume() {
        val r = Rig(defaults.copy(earPause = true, earResume = true))
        try {
            r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.idle(50)
            r.route = r.route.copy(active = false, devices = emptyList())
            r.routingCallback(); r.idle(2_100)
            r.ear(EarState.BOTH); r.idle(50)
            assertEquals(0, r.plays)
            assertNull(r.provider.read(pair)!!.observedPauseEffect)
        } finally { r.close() }
    }

    @Test fun nativePlayingCannotRetainAnInactiveRendererPastTheResumeDeadline() {
        val r = Rig(defaults.copy(conversationPause = true))
        try {
            r.conversation(true); r.idle(50); r.emit("paused")
            r.route = r.route.copy(active = false, devices = emptyList())
            r.conversation(false); r.idle(100); r.emit("playing")
            assertEquals(1, r.plays)
            assertTrue(r.provider.read(pair)!!.routedToPair)
            r.idle(2_100)
            assertFalse(r.provider.read(pair)!!.routedToPair)
        } finally { r.close() }
    }

    @Test fun conversationPauseResumesWithDefaultEarPreferences() {
        val r = Rig(defaults.copy(conversationPause = true))
        try {
            r.conversation(true); r.idle(50)
            assertEquals(1, r.pauses); r.emit("paused"); r.idle(100)
            r.conversation(false); r.idle(100)
            assertEquals(1, r.plays)
            r.emit("playing"); r.idle(100)
            assertNotNull(r.player.airPodsPlayReceipt)
            assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), r.env.executed)
        } finally { r.close() }
    }

    private enum class Confirmation { NONE, IMMEDIATE, MID_RAMP, AFTER_RAMP, SAME_TRACK_RELOAD, ACCOUNT_ECHOES }

    @Test fun ownedDuckRestoresAfterOwnPlayForEveryConfirmationAndEarResumeSetting() {
        for (earResume in listOf(false, true)) for (confirmation in Confirmation.values()) {
            val label = "earResume=$earResume confirmation=$confirmation"
            val r = Rig(defaults.copy(conversationDuck = true, conversationPause = true, earResume = earResume))
            try {
                r.conversation(true); r.idle(50)
                assertEquals(label, 1, r.pauses); r.emit("paused")
                if (confirmation == Confirmation.ACCOUNT_ECHOES) r.emit("cluster", "")
                r.idle(800)
                assertEquals(label, 4, r.volume())
                r.conversation(false); r.idle(10)
                assertEquals(label, 1, r.plays)
                when (confirmation) {
                    Confirmation.NONE -> Unit
                    Confirmation.IMMEDIATE -> r.emit("playing")
                    Confirmation.MID_RAMP -> { r.idle(150); r.emit("playing") }
                    Confirmation.AFTER_RAMP -> { r.idle(800); r.emit("playing") }
                    Confirmation.SAME_TRACK_RELOAD -> { r.emit("loading"); r.idle(100); r.emit("playing") }
                    Confirmation.ACCOUNT_ECHOES -> { r.emit("cluster", ""); r.emit("playing"); r.emit("cluster", "") }
                }
                r.idle(800)
                assertEquals(label, 10, r.volume())
                assertEquals(label, listOf(MediaAction.PAUSE, MediaAction.PLAY), r.transportSent())
                if (confirmation != Confirmation.NONE) assertNotNull(label, r.player.airPodsPlayReceipt)
            } finally { r.close() }
        }
    }

    @Test fun manualInterventionAroundOwnPlayStillRevokesRestore() {
        // 0 notification/MediaSession pause, 1 seek, 2 app command, 3 foreign track playing,
        // 4 consent revoked, 5 user pause before the engine confirmed our PLAY, 6 manual volume.
        for (kind in 0..6) {
            val r = Rig(defaults.copy(conversationDuck = true, conversationPause = true))
            try {
                r.ownedPauseAndDuckThenResume()
                if (kind != 5) r.emit("playing")
                val sent = r.env.executed.size
                when (kind) {
                    0 -> r.provider.sessionPlayer.pause()
                    1 -> r.provider.sessionPlayer.seekTo(1_000)
                    2 -> r.player.recordAirPodsApplicationCommand()
                    3 -> r.emit("playing", "spotify:track:other")
                    4 -> r.core.configure(r.settings.copy(automationsEnabled = false))
                    5 -> { r.provider.sessionPlayer.pause(); r.idle(); r.emit("playing") }
                    else -> assertTrue(r.core.manualVolume(6))
                }
                r.idle(800)
                assertTrue("kind=$kind", r.env.executed.drop(sent).none { it == MediaAction.SET_VOLUME })
                assertEquals("kind=$kind", if (kind == 6) 6 else 4, r.volume())
            } finally { r.close() }
        }
    }

    @Test fun earPauseWithDefaultResumeOffDoesNotResumeOnReinsertion() {
        val r = Rig(defaults.copy(earPause = true))
        try {
            r.ear(EarState.BOTH); r.ear(EarState.NONE); r.idle(50)
            assertEquals(1, r.pauses); r.emit("paused"); r.idle(100)
            r.ear(EarState.BOTH); r.idle(100)
            assertEquals(0, r.plays)
        } finally { r.close() }
    }

    @Test fun conversationAndEarOverlapWithDefaultEarResume() {
        for (earLast in listOf(false, true)) {
            val r = Rig(defaults.copy(earPause = true, conversationPause = true))
            try {
                r.ear(EarState.BOTH); r.conversation(true); r.idle(50)
                assertEquals(1, r.pauses); r.emit("paused"); r.idle(100)
                r.ear(EarState.LEFT)
                if (earLast) { r.conversation(false); r.ear(EarState.BOTH) }
                else { r.ear(EarState.BOTH); r.conversation(false) }
                r.idle(100)
                assertEquals("earLast=$earLast", if (earLast) 0 else 1, r.plays)
                assertEquals("earLast=$earLast", 1, r.pauses)
            } finally { r.close() }
        }
    }

    @Test fun earResumeWhileConversationDuckIsOwnedKeepsTheDuckThroughItsConfirmation() {
        // Extension of the reported mechanism: an ear-resume PLAY during an owned CA duck.
        val r = Rig(defaults.copy(earPause = true, earResume = true, conversationDuck = true))
        try {
            r.ear(EarState.BOTH); r.conversation(true); r.idle(800)
            assertEquals(4, r.volume())
            r.ear(EarState.NONE); r.idle(50)
            assertEquals(1, r.pauses); r.emit("paused"); r.idle(100)
            r.ear(EarState.BOTH); r.idle(10)
            assertEquals(1, r.plays); r.emit("playing"); r.idle(100)
            assertEquals(4, r.volume()) // still ducked: the conversation is ongoing
            r.conversation(false); r.idle(800)
            assertEquals(10, r.volume())
        } finally { r.close() }
    }
}
