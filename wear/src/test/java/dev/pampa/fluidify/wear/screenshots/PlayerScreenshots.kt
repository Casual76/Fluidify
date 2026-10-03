package dev.pampa.fluidify.wear.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

    private fun capture(name: String, ambient: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { WatchFrame(ambient = ambient) { content() } }
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
