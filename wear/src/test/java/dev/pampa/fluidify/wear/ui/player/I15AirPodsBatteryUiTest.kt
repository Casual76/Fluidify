package dev.pampa.fluidify.wear.ui.player

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.link.*
import dev.pampa.fluidify.wear.playback.NowPlaying
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.*
import dev.pampa.fluidify.wear.screenshots.WatchFrame
import dev.pampa.fluidify.wear.ui.sheets.EssentialsScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "en-w240dp-h240dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
class I15AirPodsBatteryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)

    @Test fun realScreenShowsOnlyObservedRightAndReactsToRevokedOptimisticReceipt() {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val account = AccountState(true, changedAtEpochMs = wall, localDataSession = "i15-ui-a", localDataNamespace = "synthetic")
        context.getSharedPreferences("phone-account-session", Context.MODE_PRIVATE).edit().clear().commit()
        PhoneAccountSession.forContext(context).accept(account)
        val receiver = AirPodsBatteryReceiver().apply {
            beginContact("phone", wall, elapsed)
            observeClock(wall, wall, elapsed)
            confirmContact("phone", wall, wall, elapsed)
            observeAccount(account)
        }
        val snapshot = PlaybackSnapshot(1, wall, source = PlaybackSource.PHONE,
            airPodsBattery = AirPodsBatterySnapshot("i15-ui-a", "synthetic-connection", right = AirPodsBatteryLevel(42, wall - 10_000)))
        val receipt = receiver.accept(snapshot, wall, elapsed)!!
        val controls = ReadOnlyControls(NowPlaying(ReceivedSnapshot(snapshot, wall, 0, receipt), LinkStatus.CONNECTED))
        compose.setContent { WatchFrame { AirPodsBatteryScreen(controls) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.wf_i15_right)))
        compose.onNodeWithText(context.getString(R.string.wf_i15_percent, 42)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.wf_i15_percent, 0)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.wf_i15_case)).assertDoesNotExist()
        // Keep the exact optimistic frame: only revoking its shared receipt changes. The screen
        // must observe that revocation even when PhoneRemote's StateFlow holds an equal frame.
        compose.runOnIdle { receiver.observeAccount(AccountState(false)) }
        compose.onNodeWithText(context.getString(R.string.wf_i15_percent, 42)).assertDoesNotExist()
        compose.onAllNodesWithText(text(R.string.wf_i15_unknown)).onLast().assertIsDisplayed()
    }

    @Test fun visibleSongOptionsEntryOpensOnceWithoutPlaybackCommand() {
        var opens = 0
        val controls = ReadOnlyControls(NowPlaying(null, LinkStatus.CONNECTED))
        compose.setContent {
            WatchFrame {
                EssentialsScreen(controls, onQueue = {}, onSleep = {}, onOpenContext = { _, _ -> },
                    onRadio = {}, onAirPodsBattery = { opens++ })
            }
        }
        compose.onNodeWithText(text(R.string.wf_i15_headphones)).performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    /** Opening the battery UI has no reason to use any command, RPC, poll or transfer. */
    private class ReadOnlyControls(now: NowPlaying) : PlaybackControls {
        override val nowPlaying = MutableStateFlow(now)
        override val errors = MutableSharedFlow<String>()
        private fun command(): Nothing = error("Battery UI must remain read-only")
        override fun togglePlay() = command()
        override fun setPlaying(playing: Boolean) = command()
        override fun next() = command()
        override fun previous() = command()
        override fun seekTo(positionMs: Long) = command()
        override fun setShuffle(enabled: Boolean) = command()
        override fun setRepeat(mode: RepeatMode) = command()
        override fun setLiked(liked: Boolean) = command()
        override fun playContext(contextUri: String, startTrackUri: String?, shuffle: Boolean, label: String) = command()
        override fun playQueueIndex(index: Int, uri: String) = command()
        override fun addToQueue(uri: String) = command()
        override fun startRadio() = command()
        override fun transfer(deviceId: String) = command()
        override fun setVolume(level: Float, deviceId: String?) = command()
        override fun sleep(minutes: Int?, atTrackEnd: Boolean, cancel: Boolean) = command()
        override suspend fun addToPlaylist(playlistUri: String, trackUri: String): Boolean = command()
    }
}
