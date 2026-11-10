@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import android.app.Application
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.lelonio.square.backend.PlaybackHost
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsFocusAndPinnedSinkTest {
    @Test fun pinned151ProvidesPublicDefaultTrackFactoryNoReflectionContract() {
        assertEquals("1.5.1", MediaLibraryInfo.VERSION)
        assertNotNull(DefaultAudioSink.Builder::class.java.getMethod("setAudioTrackProvider",
            DefaultAudioSink.AudioTrackProvider::class.java))
        assertNotNull(DefaultAudioSink.AudioTrackProvider.DEFAULT)
    }
    @Test fun localAndVideoUseSameFactorySeamWithoutConstructingAnotherPlayer() {
        val host = object : PlaybackHost {
            override val context: Context = RuntimeEnvironment.getApplication()
            override val looper = Looper.getMainLooper()
        }
        val local = LocalPlayerFactory.renderers(host)
        val video = LocalPlayerFactory.renderers(host)
        assertNotSame(local.airPodsOutput, video.airPodsOutput)
        assertFalse(local.airPodsOutput.read().active)
        assertTrue(video.airPodsOutput.read().devices.isEmpty())
        // No decoder, playback restart, or second renderer is constructed by a route read/reset.
        local.airPodsOutput.reset()
        assertTrue(local.airPodsOutput.read().devices.isEmpty())
    }
    @Test fun actualLocalAndVideoFactoryBindExistingPlayerAndRouteReadsDoNotResetDecoder() {
        val host = object : PlaybackHost {
            override val context: Context = RuntimeEnvironment.getApplication()
            override val looper = Looper.getMainLooper()
        }
        val local = LocalPlayerFactory.create(host)
        val videoRenderers = LocalPlayerFactory.renderers(host)
        val videoRaw = androidx.media3.exoplayer.ExoPlayer.Builder(host.context, videoRenderers)
            .setLooper(host.looper).build()
        val video = LocalPlayerFactory.trackFocus(videoRaw, host.context, videoRenderers)
        try {
            assertNotNull(AirPodsExoOutputs.get(local))
            assertSame(videoRenderers.airPodsOutput, AirPodsExoOutputs.get(video))
            val state = video.playbackState
            repeat(3) { AirPodsExoOutputs.get(video)!!.read() }
            assertEquals(state, video.playbackState)
            assertEquals(0, video.mediaItemCount)
        } finally { local.release(); video.release() }
    }
    @Test fun realFocusedExoPublishesEveryDelegatedSeekIntentWithoutMedia() {
        val host = object : PlaybackHost {
            override val context: Context = RuntimeEnvironment.getApplication()
            override val looper = Looper.getMainLooper()
        }
        val player = LocalPlayerFactory.create(host)
        val output = requireNotNull(AirPodsExoOutputs.get(player))
        var commands = 0
        val observation = output.observeCommand { commands++ }
        try {
            player.seekBack(); player.seekForward()
            player.seekToDefaultPosition(); player.seekToDefaultPosition(0)
            assertEquals(4, commands)
            assertEquals(0, player.mediaItemCount)
        } finally { observation.close(); player.release() }
    }
    @Test fun normalAbandonDoesNotEmitForeignLossButDuckHasOneObservableOwner() {
        val context = RuntimeEnvironment.getApplication()
        var pauses = 0; var resumes = 0; var signals = 0
        val ducks = mutableListOf<Boolean>()
        val focus = AudioFocusController(context, { pauses++ }, { resumes++ }, { ducks.add(it) })
        val observation = focus.observeAirPodsFocus { signals++ }
        val request = AudioFocusController::class.java.getDeclaredField("request").apply { isAccessible = true }
            .get(focus) as AudioFocusRequest
        assertTrue(request.willPauseWhenDucked()) // platform sends CAN_DUCK instead of automatic duck
        val change = AudioFocusController::class.java.getDeclaredMethod("onFocusChange", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        try {
            focus.requestFocus(); val before = signals
            focus.abandonFocus(); assertEquals(before, signals)
            change.invoke(focus, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertTrue(focus.foreignAttenuationActive); assertEquals(listOf(true), ducks)
            assertEquals(0, pauses)
            change.invoke(focus, AudioManager.AUDIOFOCUS_GAIN)
            assertFalse(focus.foreignAttenuationActive); assertEquals(listOf(true, false), ducks)
            change.invoke(focus, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertEquals(1, pauses)
            change.invoke(focus, AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(1, resumes)
        } finally { observation.close(); focus.release() }
    }
}
