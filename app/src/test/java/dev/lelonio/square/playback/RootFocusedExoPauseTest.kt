@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.lelonio.square.playback

import android.app.Application
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import dev.lelonio.square.backend.PlaybackHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class RootFocusedExoPauseTest {
    private fun exercise(paused: Boolean) {
        val host = object : PlaybackHost {
            override val context: Context = RuntimeEnvironment.getApplication()
            override val looper = Looper.getMainLooper()
        }
        val player = LocalPlayerFactory.create(host)
        val focus = AirPodsFocusedExoPlayer::class.java.getDeclaredField("focus")
            .apply { isAccessible = true }.get(player) as AudioFocusController
        val request = AudioFocusController::class.java.getDeclaredField("request")
            .apply { isAccessible = true }.get(focus) as AudioFocusRequest
        fun change(value: Int) = requireNotNull(org.robolectric.util.ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(request, "getOnAudioFocusChangeListener")).onAudioFocusChange(value)
        try {
            player.play()
            if (paused) player.pause()
            shadowOf(Looper.getMainLooper()).idle()
            change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertFalse(player.playWhenReady)
            change(AudioManager.AUDIOFOCUS_GAIN)
            shadowOf(Looper.getMainLooper()).idle()
            if (paused) assertFalse(player.playWhenReady) else assertTrue(player.playWhenReady)
        } finally { player.release() }
    }

    @Test fun lateFocusCallbacksCannotRestartALocalOrVideoPlayerTheUserPaused() = exercise(paused = true)
    @Test fun aRealInterruptionCanStillResumeAnActiveLocalOrVideoPlayer() = exercise(paused = false)
}
