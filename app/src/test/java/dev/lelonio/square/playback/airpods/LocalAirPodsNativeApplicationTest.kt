@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import dev.lelonio.square.data.RemotePlayback
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LocalAirPodsNativeApplicationTest.Host::class, manifest = Config.NONE, sdk = [35])
class LocalAirPodsNativeApplicationTest {
    class Host : Application(), CoreHost { override val qualityIsAutomatic = false }
    private val uri = "spotify:track:one"
    private class Rig {
        var pauses = 0; var plays = 0; var failPause = false
        val player = LibrespotPlayer(RuntimeEnvironment.getApplication(), Looper.getMainLooper(), PlayQueue(),
            onSpeedAndPitch = { _, _ -> }, fadeOutThen = { it() }, fadeIn = {},
            onPlaybackActive = {}, onDuckReverb = {}, loadQueueCommand = { _, _, _, _, _, _, _ -> },
            refreshRemoteState = {}, airPodsPauseCommand = { if (failPause) error("refused"); pauses++ },
            airPodsPlayCommand = { plays++ }).apply { shutdownEngineOnRelease = false }
        init {
            player.restore(listOf(PlayQueue.Track("spotify:track:one", "One", "Artist", durationMs = 60000,
                artworkUri = null)), null, 0, 0, Player.REPEAT_MODE_OFF, null, false, "", handOverNow = true)
            shadowOf(Looper.getMainLooper()).idle()
            emit("playing")
        }
        fun emit(type: String, uri: String = "spotify:track:one") {
            player.onEvent(type, uri, 0); shadowOf(Looper.getMainLooper()).idle()
        }
    }
    @Test fun dispatchAndEffectivePausedStateCorroborateThenOwnPlayUsesLocalBranch() {
        val r = Rig()
        try {
            assertTrue(r.player.applyAirPodsPlayback(41, false)); assertEquals(1, r.pauses)
            assertNull(r.player.airPodsPauseReceipt)
            r.emit("paused"); assertFalse(r.player.playWhenReady)
            assertEquals(41L, r.player.airPodsPauseReceipt)
            assertTrue(r.player.applyAirPodsPlayback(42, true)); assertEquals(1, r.plays)
        } finally { r.player.release() }
    }
    @Test fun applicationSameStatePauseRevokesBeforeFinalMedia3MethodCanNoOp() {
        val r = Rig()
        try {
            assertTrue(r.player.applyAirPodsPlayback(41, false)); r.emit("paused")
            assertNotNull(r.player.airPodsPauseReceipt)
            r.player.recordAirPodsApplicationCommand(); r.player.pause()
            assertNull(r.player.airPodsPauseReceipt)
        } finally { r.player.release() }
    }
    @Test fun failedDispatchOrWrongTrackDoesNotMintReceipt() {
        for (fail in listOf(false, true)) {
            val r = Rig()
            try {
                r.failPause = fail
                assertEquals(!fail, r.player.applyAirPodsPlayback(41, false))
                r.emit("paused", if (fail) uri else "spotify:track:other")
                assertNull(r.player.airPodsPauseReceipt)
            } finally { r.player.release() }
        }
    }
    @Test fun alreadyQueuedCallbackCannotCorroborateNewCommand() {
        val r = Rig()
        try {
            r.player.onEvent("paused", uri, 0) // received before the command, not yet applied
            assertTrue(r.player.applyAirPodsPlayback(41, false))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(r.player.airPodsPauseReceipt)
        } finally { r.player.release() }
    }
    @Test fun interveningSeekAndRemoteStandbyCannotInheritPauseOrTransferAccount() {
        val r = Rig()
        try {
            assertTrue(r.player.applyAirPodsPlayback(41, false)); r.emit("paused")
            r.player.seekTo(1000); assertNull(r.player.airPodsPauseReceipt)
            r.player.showRemote(RemotePlayback("remote", "Remote", uri, "One", "Artist", "", "", "",
                0, 60000, true, false, false, false))
            assertFalse(r.player.applyAirPodsPlayback(42, true))
            r.player.showRemote(null)
            // Directly exercise the pre-existing standby mirror branch without invoking Connect/network.
            LibrespotPlayer::class.java.getDeclaredField("standby").apply { isAccessible = true }.set(r.player,
                RemotePlayback("remote", "Remote", uri, "One", "Artist", "", "", "", 0, 60000,
                    false, false, false, false))
            assertFalse(r.player.airPodsAutomationLocal)
            assertFalse(r.player.applyAirPodsPlayback(43, true))
            assertEquals(0, r.plays)
        } finally { r.player.release() }
    }
}
