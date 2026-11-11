package dev.lelonio.square.playback

import android.os.Looper
import dev.lelonio.square.data.SpotifyNetworkPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ManualOfflineNativeCallbacksTest.Host::class)
class ManualOfflineNativeCallbacksTest {
    class Host : android.app.Application(), CoreHost { override val qualityIsAutomatic = false }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }
    @Test fun nativeSessionRevisionSurvivesHandlerPostingAndRapidToggleDoesNotPublishOldOutcome() {
        SpotifyNetworkPolicy.setManual(false)
        val queue = PlayQueue()
        val p = LibrespotPlayer(RuntimeEnvironment.getApplication(), Looper.getMainLooper(), queue,
            onSpeedAndPitch = { _, _ -> }, fadeOutThen = { it() }, fadeIn = {},
            onPlaybackActive = {}, onDuckReverb = {}, loadQueueCommand = { _, _, _, _, _, _, _ -> },
            refreshRemoteState = {})
        p.shutdownEngineOnRelease = false
        var sessions = 0; p.onSession = { sessions++ }
        try {
            val old = SpotifyNetworkPolicy.revision
            p.onLeasedEvent("session", "", 1, old)
            SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, sessions)
            assertEquals(0, queue.items.size)
            p.onLeasedEvent("session", "", 1, SpotifyNetworkPolicy.revision)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, sessions)
        } finally { p.release() }
    }
}
