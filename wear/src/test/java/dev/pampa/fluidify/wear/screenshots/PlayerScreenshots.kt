package dev.pampa.fluidify.wear.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.protocol.DeviceInfo
import dev.pampa.fluidify.wear.protocol.DeviceKind
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.ui.more.MoreScreen
import dev.pampa.fluidify.wear.ui.player.ImmersiveScreen
import dev.pampa.fluidify.wear.ui.player.PlayerScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The watch screens, rendered on the JVM at the Galaxy Watch 7's two sizes.
 *
 * `./gradlew :wear:recordRoborazziDebug` writes them to wear/screenshots. They
 * are for looking at, not for asserting pixels: the glass is real HWUI here, but
 * a device is where it is judged.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = Watch44)
class PlayerScreenshots {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var art: ArtStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        installSynchronousImageLoader(context)
        art = ArtStore(context)
        storeSampleCover(art)
    }

    private fun capture(name: String, ambient: Boolean = false, fontScale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                WatchFrame(ambient = ambient) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private fun player(snapshot: PlaybackSnapshot?, link: LinkStatus = LinkStatus.CONNECTED) =
        @androidx.compose.runtime.Composable {
            PlayerScreen(FakeControls(snapshot, link), art, onQueue = {}, onOutput = {}, onEssentials = {})
        }

    @Test
    fun playing() = capture("player_playing", content = player(sampleSnapshot()))

    @Test
    fun pausedAndLiked() = capture("player_paused_liked", content = player(sampleSnapshot(playing = false, liked = true)))

    @Test
    fun longTitleOnAComputer() = capture(
        "player_long_title_connect",
        content = player(
            sampleSnapshot(
                title = "Un titolo molto lungo che non ci sta nella capsula",
                artist = "Orchestra Sinfonica di Qualcosa, Solista Ospite",
                device = DeviceInfo("pc", "PC di casa", DeviceKind.COMPUTER, volume = 0.3f, canSetVolume = true),
            ),
        ),
    )

    @Test
    fun noCoverYet() = capture("player_no_cover", content = player(sampleSnapshot(artKey = null)))

    @Test
    fun nothingPlayingPhoneAway() = capture("player_nothing_unreachable", content = player(null, LinkStatus.UNREACHABLE))

    @Test
    fun ambient() = capture("player_ambient", ambient = true, content = player(sampleSnapshot()))

    @Test fun enteringAmbientKeepsTheTitleAnchorAndDisposesTheInteractivePlayer() {
        val snapshot = sampleSnapshot()
        val controls = FakeControls(snapshot)
        val now = controls.nowPlaying.value
        val ambient = androidx.compose.runtime.mutableStateOf(dev.antigravity.fluidengine.wear.ambient.FluidAmbientState.preview(false))
        var disposed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            WatchFrame {
                CompositionLocalProvider(dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient provides ambient.value) {
                    dev.pampa.fluidify.wear.ui.player.WatchAmbientSurface(now) {
                        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { disposed++ } }
                        PlayerScreen(controls, art, onQueue = {}, onOutput = {}, onEssentials = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_200)
        val title = snapshot.track!!.title
        val before = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { ambient.value = dev.antigravity.fluidengine.wear.ambient.FluidAmbientState.preview(true) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val after = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(before.top, after.top, 2f)
        org.junit.Assert.assertEquals(1, disposed)
        compose.onRoot().captureRoboImage("screenshots/player_aod_same_anchor.png")
    }

    @Test
    fun immersive() = capture("immersive") { ImmersiveScreen(FakeControls(sampleSnapshot()), art) }

    @Test
    fun more() = capture("more") { MoreScreen(FakeControls(sampleSnapshot()), onOutput = {}) }

    @Test
    @Config(qualifiers = Watch40)
    fun playingSmallWatch() = capture("player_playing_40mm", content = player(sampleSnapshot()))

    @Test
    @Config(qualifiers = WatchSmall)
    fun playingSmallestWatch() = capture("player_playing_192dp", content = player(sampleSnapshot()))

    @Test @Config(qualifiers = WatchSmall)
    fun largeFontSmallest() = largeFont("player_192dp_font130_status")

    @Test @Config(qualifiers = Watch40)
    fun largeFontMedium() = largeFont("player_216dp_font130_status")

    @Test fun largeFontRegular() = largeFont("player_240dp_font130_status")

    private fun largeFont(name: String) {
        capture(name, fontScale = 1.3f) {
            PlayerScreen(FakeControls(sampleSnapshot()), art, onQueue = {}, onOutput = {}, onEssentials = {},
                status = "Connessione in corso")
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun bounds(id: Int) = compose.onNodeWithContentDescription(context.getString(id)).fetchSemanticsNode().boundsInRoot
        val previous = bounds(dev.pampa.fluidify.wear.R.string.previous)
        val next = bounds(dev.pampa.fluidify.wear.R.string.next)
        val pause = bounds(dev.pampa.fluidify.wear.R.string.pause)
        org.junit.Assert.assertTrue(previous.right <= pause.left)
        org.junit.Assert.assertTrue(pause.right <= next.left)
        org.junit.Assert.assertTrue(pause.bottom <= bounds(dev.pampa.fluidify.wear.R.string.queue).top)
    }

    @Test
    fun turningTheVolume() {
        val controls = FakeControls(sampleSnapshot())
        // A dispatcher that never runs: the overlay stays up for the capture instead of hiding itself.
        val volume = dev.pampa.fluidify.wear.playback.VolumeControl(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher()),
            controls,
        )
        volume.sync(0.4f)
        volume.turn(2)
        capture("player_volume") {
            PlayerScreen(controls, art, onQueue = {}, onOutput = {}, onEssentials = {}, volume = volume)
        }
    }
}
