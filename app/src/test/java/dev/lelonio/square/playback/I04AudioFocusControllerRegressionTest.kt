package dev.lelonio.square.playback

import android.app.Application
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import org.junit.Assert.*
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
class I04AudioFocusControllerRegressionTest {
    private class Rig(initiallyPlaying: Boolean) {
        var intended = initiallyPlaying
        var pauses = 0
        var resumes = 0
        val ducks = mutableListOf<Boolean>()
        val controller = AudioFocusController(RuntimeEnvironment.getApplication(),
            onPause = { pauses++; intended = false },
            onResume = { resumes++; intended = true },
            onDuck = { ducks += it }, isPlaybackIntended = { intended })
        private val request = AudioFocusController::class.java.getDeclaredField("request")
            .apply { isAccessible = true }.get(controller) as AudioFocusRequest
        fun change(value: Int) = requireNotNull(org.robolectric.util.ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(request, "getOnAudioFocusChangeListener")).onAudioFocusChange(value)
        fun held() = AudioFocusController::class.java.getDeclaredField("holdsFocus")
            .apply { isAccessible = true }.getBoolean(controller)
    }

    @Test fun lossAndGainWhileAlreadyPausedNeverResume() {
        val r = Rig(false)
        try {
            assertTrue(r.controller.requestFocus())
            r.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            r.change(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0, r.resumes)
            assertFalse(r.intended)
        } finally { r.controller.release() }
    }

    @Test fun activePlaybackResumesOnceEvenAfterAcknowledgementAndRepeatedLoss() {
        val r = Rig(true)
        try {
            assertTrue(r.controller.requestFocus())
            r.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            r.controller.onPlaybackPaused()
            assertTrue(r.held())
            assertTrue(r.controller.isPlaybackSuppressed)
            r.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            r.controller.onPlaybackPaused()
            r.change(AudioManager.AUDIOFOCUS_GAIN)
            r.change(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(1, r.resumes)
            assertTrue(r.intended)
            assertFalse(r.controller.isPlaybackSuppressed)
        } finally { r.controller.release() }
    }

    @Test fun nativeConnectPauseReleasesFocusAndCannotCreateAFutureResume() {
        val r = Rig(true)
        try {
            assertTrue(r.controller.requestFocus())
            r.intended = false
            r.controller.onPlaybackPaused()
            assertFalse(r.held())
            r.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            r.change(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0, r.resumes)
        } finally { r.controller.release() }
    }

    @Test fun explicitPauseDuringInterruptionRevokesResumeEvenWithoutHeldFocus() {
        val r = Rig(true)
        try {
            // Also exercises late callbacks after focus has already been released.
            r.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            r.controller.abandonFocus()
            r.change(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0, r.resumes)
            assertFalse(r.intended)
        } finally { r.controller.release() }
    }

    @Test fun permanentLossRevokesATransientResume() {
        val r = Rig(true)
        try {
            assertTrue(r.controller.requestFocus())
            r.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            r.change(AudioManager.AUDIOFOCUS_LOSS)
            assertFalse(r.held())
            r.change(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0, r.resumes)
        } finally { r.controller.release() }
    }

    @Test fun noisyDisconnectDuringInterruptionRevokesResume() {
        val r = Rig(true)
        try {
            assertTrue(r.controller.requestFocus())
            r.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            RuntimeEnvironment.getApplication().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            shadowOf(Looper.getMainLooper()).idle()
            r.change(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0, r.resumes)
            assertFalse(r.intended)
        } finally { r.controller.release() }
    }

    @Test fun duckingRestoresTheLevelWithoutPausingOrResuming() {
        val r = Rig(true)
        try {
            assertTrue(r.controller.requestFocus())
            r.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertFalse(r.controller.isPlaybackSuppressed)
            r.change(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(listOf(true, false), r.ducks)
            assertEquals(0, r.pauses)
            assertEquals(0, r.resumes)
        } finally { r.controller.release() }
    }
}
