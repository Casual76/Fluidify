// PREPARED, NOT COMPILED OR EXECUTED. Uses the proposed three playback source files.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.media.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import dev.lelonio.square.backend.PlaybackHost
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

/**
 * Real ExoPlayer/ListenerSet, production local/shared-video factory, forwarding DefaultAudioSink,
 * AirPodsExoOutput, provider, core and coordinator. PCM silence goes through the real renderer.
 * Only Bluetooth address/connected facts are injected; AudioTrack uses a paced finite-buffer shadow.
 * No callback, isPlaying flag or application receipt is manually supplied by the tests.
 * These tests do not prove Bluetooth routing, physical audio or DASH/DRM video loading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35], shadows = [PacedAudioTrackShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ExoResumeAuthorityRuntimeTest {
    private val pair = PairAddress("AA:BB:CC:DD:EE:FF")
    private data class Sent(val action: MediaAction, val id: Long?)
    private class Barrier(val entered: CountDownLatch, val release: CountDownLatch) : Closeable {
        override fun close() { release.countDown() }
    }
    private inner class Rig(sharedVideoFactory: Boolean = false, caPause: Boolean = false,
        mediaDurationUs: Long = 120_000_000L) {
        val app: Application = RuntimeEnvironment.getApplication()
        val audio: AudioManager = app.getSystemService(AudioManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        val host = object : PlaybackHost {
            override val context: Context = app
            override val looper = Looper.getMainLooper()
        }
        val player: ExoPlayer = if (!sharedVideoFactory) LocalPlayerFactory.create(host) as ExoPlayer else {
            val renderers = LocalPlayerFactory.renderers(host)
            val raw = ExoPlayer.Builder(app, renderers).setLooper(host.looper)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false).build()
            LocalPlayerFactory.trackFocus(raw, app, renderers)
        }
        val output = requireNotNull(AirPodsExoOutputs.get(player))
        val executed = mutableListOf<Sent>()
        val barriers = mutableListOf<Barrier>()
        var account = 1L
        var routeAllowed = true
        var playCallbacks = 0
        val settings = HostSettings(automationsEnabled = true, fluidifyBackgroundEnabled = true,
            earPause = true, earResume = !caPause, conversationPause = caPause, conversationDuck = true)
        val provider: PlaybackAirPodsProvider
        val core: MediaRuntimeCore

        init {
            audio.mode = AudioManager.MODE_NORMAL
            shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
            player.setMediaSource(SilenceMediaSource.Factory().setDurationUs(mediaDurationUs).createMediaSource())
            player.prepare(); player.play()
            await("real renderer and AudioTrack must be playing before admission") {
                player.playbackState == Player.STATE_READY && player.isPlaying && output.read().active
            }
            provider = PlaybackAirPodsProvider(app, player, null, { account }, { false }, {
                output.read().let { actual -> actual.copy(devices = if (routeAllowed)
                    listOf(AirPodsRoutedDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pair.value)) else emptyList()) }
            }, { if (routeAllowed) setOf(7) else emptySet() }, { false })
            player.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (player.playWhenReady && (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                            events.contains(Player.EVENT_IS_PLAYING_CHANGED))) playCallbacks++
                }
            })
            val environment = object : MediaRuntimeEnvironment {
                override fun read() = listOfNotNull(provider.read(pair))
                override fun observe(changed: () -> Unit) = provider.observe(changed)
                override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
                    executed += Sent(action, effectId)
                    return provider.execute(target, action, value, effectId)
                }
                override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = provider.gesture(target, action)
            }
            val queue = object : MediaRuntimeQueue {
                override fun post(block: () -> Unit) { handler.post { block() } }
                override fun later(delayMillis: Long, block: () -> Unit): Closeable {
                    val r = Runnable { block() }; handler.postDelayed(r, delayMillis)
                    return Closeable { handler.removeCallbacks(r) }
                }
            }
            core = MediaRuntimeCore(environment, queue, AutomationClock { SystemClock.elapsedRealtime() })
            core.start(AutomationSession("pair", 7), settings); core.visible(true); idle()
            assertTrue(read().routedToPair)
            core.ear(EarState.BOTH); idle()
        }

        fun idle(ms: Long = 0) {
            if (ms == 0L) { shadowOf(Looper.getMainLooper()).idle(); return }
            var remaining = ms
            while (remaining > 0) {
                val step = minOf(10L, remaining)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
                Thread.sleep(1) // let the real playback queue refill the finite AudioTrack buffer
                remaining -= step
            }
        }
        fun await(label: String, predicate: () -> Boolean) {
            repeat(500) {
                idle(2)
                player.playerError?.let { throw AssertionError("$label; actual renderer error", it) }
                if (predicate()) return
                // idle(2) already yields to the real playback queue; the original 1000 ms virtual bound stays.
            }
            fail(label)
        }
        fun read() = provider.read(pair)!!
        fun audioTrack() = AirPodsExoOutput::class.java.getDeclaredField("track").apply { isAccessible = true }
            .get(output) as android.media.AudioTrack
        fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        fun playId() = executed.last { it.action == MediaAction.PLAY }.id!!
        fun duckAndPause() {
            core.conversation(true); idle(800)
            if (!settings.conversationPause) {
                assertEquals("fixture reached EOS before the requested ear PAUSE", Player.STATE_READY, player.playbackState)
                assertTrue("fixture must still be playing before the ear PAUSE", player.isPlaying)
                assertTrue("fixture must not exhaust the 120-second PCM", player.currentPosition < player.duration)
                core.ear(EarState.NONE); idle()
            }
            await("PAUSE must be acknowledged by the real forwarding sink") {
                !player.playWhenReady && read().observedPauseEffect != null
            }
            assertEquals(4, volume()); assertNotNull(read().observedVolumeEffect)
        }
        fun barrier(): Barrier {
            val barrier = Barrier(CountDownLatch(1), CountDownLatch(1)); barriers += barrier
            player.createMessage { _, _ ->
                barrier.entered.countDown()
                check(barrier.release.await(5, TimeUnit.SECONDS)) { "test playback barrier timed out" }
            }.send()
            assertTrue("playback barrier must precede the controlled resume marker",
                barrier.entered.await(1, TimeUnit.SECONDS))
            return barrier
        }
        fun resumeBeforeReceipt() {
            val callbacks = playCallbacks
            core.ear(EarState.BOTH); idle()
            await("real Media3 onEvents for our PLAY must arrive while the sink remains blocked") {
                playCallbacks > callbacks && player.playWhenReady
            }
            assertFalse(output.read().active)
            assertNull(output.playApplied)
            assertTrue(output.ownsPlay(playId()))
        }
        fun close() {
            barriers.forEach { it.close() }
            core.stop(); provider.close(); player.release()
        }
    }

    @Test fun pacedRendererCannotReachEosBeforeEarPause() {
        val r = Rig()
        try {
            val track = r.audioTrack()
            val paced = Shadow.extract<PacedAudioTrackShadow>(track)
            val before = paced.snapshot()
            r.core.conversation(true); r.idle(800)
            val after = paced.snapshot()
            assertEquals(Player.STATE_READY, r.player.playbackState)
            assertTrue(r.player.isPlaying)
            assertTrue(r.player.currentPosition < r.player.duration)
            assertTrue(after.playedFrames - before.playedFrames <= 44100L * 800 / 1000 + 1)
            assertTrue(after.writtenFrames - after.playedFrames <= after.capacityFrames)
            assertTrue("the real renderer must encounter finite-buffer backpressure", after.fullWrites > 0)
            assertTrue("120-second source must remain unexhausted", after.writtenFrames < 44100L * 120)
            r.core.ear(EarState.NONE); r.idle()
            r.await("PAUSE must be acknowledged by the real forwarding sink") {
                !r.player.playWhenReady && r.read().observedPauseEffect != null
            }
        } finally { r.close() }
    }

    @Test fun naturalEosIsObservableAndNeverMasqueradesAsAnUnacknowledgedEarPause() {
        val r = Rig(mediaDurationUs = 2_000_000L)
        try {
            r.idle(2_500)
            r.await("finite PCM must still reach its real EOS with the paced track") {
                r.player.playbackState == Player.STATE_ENDED
            }
            // Media3 exposes the renderer clock position without clamping it to timeline duration.
            assertTrue("real EOS reached the finite media duration", r.player.currentPosition >= r.player.duration)
            // AudioTrack.stop resets the public playback head; it is not a lifetime drain counter.
            assertEquals(0, r.audioTrack().playbackHeadPosition)
            assertEquals(android.media.AudioTrack.PLAYSTATE_STOPPED, r.audioTrack().playState)
            r.core.ear(EarState.NONE); r.idle()
            assertTrue("EOS prevented a PAUSE request; it did not lose an application receipt",
                r.executed.none { it.action == MediaAction.PAUSE })
            assertNull(r.read().observedPauseEffect)
        } finally { r.close() }
    }

    @Test fun realPwrBeforeSinkPreservesHistoricalRouteAndOwnedDuckThenRestores() {
        for (videoFactory in listOf(false, true)) {
            val r = Rig(sharedVideoFactory = videoFactory)
            try {
                r.duckAndPause()
                val before = r.read()
                val barrier = r.barrier()
                r.resumeBeforeReceipt()
                val pending = r.read()
                assertEquals(before.target, pending.target)
                assertEquals(before.transportRevision, pending.transportRevision)
                assertEquals(RouteEvidence.LOCAL_HISTORICAL_RENDERER, pending.routeEvidence)
                assertEquals(4, r.volume())
                barrier.close()
                r.await("real sink must acknowledge exactly our PLAY") { r.output.playApplied == r.playId() }
                assertEquals(before.transportRevision, r.read().transportRevision)
                r.core.conversation(false); r.idle(800)
                assertEquals(10, r.volume())
                assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY),
                    r.executed.map { it.action }.filter { it != MediaAction.SET_VOLUME })
            } finally { r.close() }
        }
    }

    @Test fun caPauseWithDefaultEarResumeOffRestoresAcrossItsRealPlayCallback() {
        val r = Rig(caPause = true)
        try {
            r.duckAndPause()
            val before = r.read().transportRevision
            val callbacks = r.playCallbacks
            r.core.conversation(false); r.idle()
            r.await("real own PLAY callback and sink receipt") {
                r.playCallbacks > callbacks && r.output.playApplied == r.playId()
            }
            r.idle(800)
            assertEquals(before, r.read().transportRevision)
            assertEquals(10, r.volume())
        } finally { r.close() }
    }

    @Test fun manualIntentAtSameStateAndRevocationsDefeatBothPendingAndAppliedPlay() {
        // 0 MediaSession play while already PWR=true; 1 raw focused-wrapper play at the same
        // state; 2 same-position seek; 3 account; 4 route; 5 consent; 6 same player volume.
        // No callback is fabricated, including for manual commands that leave state unchanged.
        for (beforeReceipt in listOf(true, false)) for (kind in 0..6) {
            val r = Rig()
            try {
                r.duckAndPause(); val barrier = r.barrier(); r.resumeBeforeReceipt()
                if (!beforeReceipt) {
                    barrier.close(); r.await("sink receipt before intervention") { r.output.playApplied == r.playId() }
                }
                val before = r.read()
                when (kind) {
                    0 -> r.provider.sessionPlayer.play()
                    1 -> r.player.play()
                    2 -> r.provider.sessionPlayer.seekTo(r.player.currentPosition)
                    3 -> r.account++
                    4 -> r.routeAllowed = false
                    5 -> r.core.configure(r.settings.copy(automationsEnabled = false))
                    else -> r.provider.sessionPlayer.volume = r.player.volume
                }
                r.idle()
                val after = r.read()
                if (kind != 5) assertTrue("kind=$kind pending=$beforeReceipt",
                    after.transportRevision > before.transportRevision || after.target != before.target || !after.routedToPair)
                barrier.close()
                // A real queue marker after all earlier commands lets them finish without faking PLAYING.
                val settled = CountDownLatch(1)
                r.player.createMessage { _, _ -> settled.countDown() }.send()
                r.await("old marker/PLAY operations must settle and the real AudioTrack must resume") {
                    settled.count == 0L && r.output.read().active
                }
                if (kind != 5) assertNull("late marker must not republish PLAY receipt", r.output.playApplied)
                val sent = r.executed.size
                r.core.conversation(false); r.idle(800)
                assertEquals("kind=$kind pending=$beforeReceipt", 4, r.volume())
                assertTrue(r.executed.drop(sent).none { it.action == MediaAction.SET_VOLUME })
                if (kind != 5) assertNull("later sink callbacks must not reacquire authority", r.output.playApplied)
            } finally { r.close() }
        }
    }

    @Test fun timedOutOriginCannotBeAcknowledgedByLateRealSinkPlay() {
        val r = Rig()
        try {
            r.duckAndPause(); val barrier = r.barrier(); r.resumeBeforeReceipt()
            val before = r.read().transportRevision
            r.idle(2_100)
            assertFalse(r.output.ownsPlay(r.playId()))
            assertTrue(r.read().transportRevision > before)
            barrier.close()
            val settled = CountDownLatch(1)
            r.player.createMessage { _, _ -> settled.countDown() }.send()
            r.await("late playback operations must settle and the real AudioTrack must resume") {
                settled.count == 0L && r.output.read().active
            }
            assertNull(r.output.playApplied)
            r.core.conversation(false); r.idle(800)
            assertEquals(4, r.volume())
        } finally { r.close() }
    }
}
