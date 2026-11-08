package dev.lelonio.square.ui.player

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.LocalGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.lelonio.square.R
import dev.lelonio.square.playback.AudioLightFrame
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Synthetic local state: no Spotify credentials, network artwork or Bluetooth required. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w1400dp-h1000dp-mdpi")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AdaptivePlayerScreenshots {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)

    private fun fixture(width: State<Int>, height: Int, font: Float = 1f, dark: Boolean = true, amoled: Boolean = false, motionScale: Float = 1f,
                        withLyrics: Boolean = false, onRadio: (() -> Unit)? = null, onAdd: () -> Unit = {},
                        contrastedBackdrop: Boolean = false, contextualLyrics: Boolean = false) {
        restoration.setContent {
            val ground = rememberGlassBackdrop()
            val density = LocalDensity.current
            SquareTheme(darkTheme = dark, amoled = amoled, systemBars = false) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, font),
                    dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy provides
                        dev.antigravity.fluidengine.ui.fluid.FluidMotionPolicy(motionScale),
                    LocalGlassBackdrop provides ground,
                    LocalAudioLightPreview provides remember { mutableStateOf<AudioLightFrame?>(null) },
                ) {
                    Box(Modifier.requiredSize(width.value.dp, height.dp)
                        // SquareTheme deliberately leaves background transparent for AppBackdrop.
                        // Supply that host's opaque page tone in this standalone fixture.
                        .background(if (!dark) Color(0xFFF2EFF7) else if (amoled) Color.Black else Color(0xFF18161C))
                        .testTag("fixture")) {
                        if (contrastedBackdrop) {
                            // Real blur over an opaque high-contrast pattern, recorded using
                            // the production stage ordering. Native rendering is an inspection
                            // fixture, not proof of RenderEffect/AGSL fidelity on Samsung.
                            Box(Modifier.fillMaxSize().playerStageSource(ground) {
                                val radius = 26.dp.toPx()
                                renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                            }.drawBehind {
                                drawRect(Color.Black)
                                val stripe = 48.dp.toPx()
                                var x = 0f
                                while (x < size.width) {
                                    drawRect(Color.White, Offset(x, 0f), Size(stripe, size.height))
                                    x += stripe * 2
                                }
                                drawCircle(Color.Red, size.minDimension * .25f, center)
                            })
                        }
                        PlayerScreen(
                            state = PlaybackState(hasItem = true, title = "Notturno sul lago", artist = "Aurora Viola",
                                durationMs = 214000, hasNext = true, hasPrevious = true),
                            positionMs = remember { mutableLongStateOf(82000) },
                            onCollapse = {}, onTogglePlay = {}, onNext = {}, onPrevious = {}, onSeek = {},
                            onToggleShuffle = {}, onCycleRepeat = {},
                            queue = listOf(QueueEntry(0, "local:fixture", "Coda persistente", "Artista locale", null, false, true)),
                            lyrics = if (withLyrics) dev.lelonio.square.data.Lyrics(
                                if (contextualLyrics) (0..24).map {
                                    dev.lelonio.square.data.LyricLine(it * 10_000L,
                                        if (it == 8) "Una luce attraversa il lago" else "Il paesaggio cambia, verso $it")
                                } else listOf(dev.lelonio.square.data.LyricLine(0L, "Una luce attraversa il lago")),
                                synced = true,
                            ) else null,
                            lyricsLoading = false, credits = null, creditsLoading = false,
                            onWantCredits = {}, onWantArtist = { _, _ -> }, onPlayQueueItem = {},
                            onRemoveQueueItem = {}, onMoveQueueItem = { _, _ -> }, canvas = null,
                            devices = MainViewModel.DevicesState(), onOpenDevices = {}, onCloseDevices = {},
                            onMorphDrag = {}, onMorphRelease = {}, playerOpen = { true }, ground = ground,
                            artist = null, artistLoading = false, onRefreshDevices = {}, onSelectDevice = {},
                            onSetDeviceVolume = { _, _ -> }, onAddToPlaylist = onAdd, onRadio = onRadio,
                            addToPlaylist = MainViewModel.AddToPlaylistState(), onPickPlaylist = {},
                        )
                    }
                }
            }
        }
    }

    private fun capture(name: String, width: Int, height: Int, font: Float = 1f, dark: Boolean = true, amoled: Boolean = false, motionScale: Float = 1f) {
        fixture(mutableStateOf(width), height, font, dark, amoled, motionScale)
        compose.mainClock.advanceTimeBy(1000)
        File("build/outputs/roborazzi/adaptive-player").mkdirs()
        compose.onNodeWithTag("fixture").captureRoboImage("build/outputs/roborazzi/adaptive-player/$name.png")
    }

    @Test fun phone() = capture("360x640", 360, 640)
    @Test fun medium() = capture("600x960", 600, 960)
    @Test fun breakpoint() = capture("840x600", 840, 600)
    @Test fun tabletLight() = capture("1280x800-light", 1280, 800, dark = false)
    @Test fun shortLandscape() = capture("720x360", 720, 360)
    @Test fun reducedMotion() = capture("840x600-motion0", 840, 600, motionScale = 0f)
    @Test fun largeFont() = capture("840x600-font150", 840, 600, font = 1.5f)
    @Test fun largestFontAmoled() = capture("360x640-font200-amoled", 360, 640, font = 2f, amoled = true)

    private fun openPanel(label: Int) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithContentDescription(context.getString(label)).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1000)
    }

    private fun captureFixture(name: String) {
        File("build/outputs/roborazzi/adaptive-player").mkdirs()
        compose.onNodeWithTag("fixture").captureRoboImage("build/outputs/roborazzi/adaptive-player/$name.png")
    }

    @Test fun tabletQueueHasReadingViewportAndSelectedAction() {
        fixture(mutableStateOf(840), 600, withLyrics = true)
        compose.mainClock.advanceTimeBy(1000)
        openPanel(R.string.queue)
        compose.onNodeWithText("Coda persistente").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithContentDescription(context.getString(R.string.queue)).assertIsSelected()
        captureFixture("840x600-queue")
    }

    @Test fun tabletLyricsAndCreditsToggleFromSameActionRail() {
        fixture(mutableStateOf(1280), 800, dark = false, withLyrics = true)
        compose.mainClock.advanceTimeBy(1000)
        openPanel(R.string.lyrics)
        compose.onNodeWithText("Una luce attraversa il lago").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithContentDescription(context.getString(R.string.lyrics)).assertIsSelected()
        captureFixture("1280x800-light-lyrics")
        openPanel(R.string.credits)
        compose.onNodeWithContentDescription(context.getString(R.string.credits)).assertIsSelected()
        openPanel(R.string.credits)
        compose.onNodeWithContentDescription(context.getString(R.string.credits)).assertIsNotSelected()
    }

    @Test fun tabletSecondaryActionsKeepCallbacks() {
        var radio = 0
        var add = 0
        fixture(mutableStateOf(840), 600, onRadio = { radio++ }, onAdd = { add++ })
        compose.mainClock.advanceTimeBy(1000)
        openPanel(R.string.radio)
        compose.runOnIdle { org.junit.Assert.assertEquals(1, radio) }
        openPanel(R.string.add_to_playlist)
        compose.runOnIdle { org.junit.Assert.assertEquals(1, add) }
    }

    @Test fun tabletAmoledQueue() {
        fixture(mutableStateOf(1280), 800, amoled = true, withLyrics = true)
        compose.mainClock.advanceTimeBy(1000)
        openPanel(R.string.queue)
        captureFixture("1280x800-amoled-queue")
    }

    @Test fun tabletLyricsContrastedBlurDark() = contrastedLyrics("dark", dark = true)
    @Test fun tabletLyricsContrastedBlurLight() = contrastedLyrics("light", dark = false)
    @Test fun tabletLyricsContrastedBlurAmoled() = contrastedLyrics("amoled", dark = true, amoled = true)

    private fun contrastedLyrics(name: String, dark: Boolean, amoled: Boolean = false) {
        fixture(mutableStateOf(1280), 800, dark = dark, amoled = amoled, withLyrics = true,
            contrastedBackdrop = true, contextualLyrics = true)
        compose.mainClock.advanceTimeBy(1000)
        openPanel(R.string.lyrics)
        compose.onNodeWithText("Una luce attraversa il lago").assertIsDisplayed()
        compose.onNodeWithText("Il paesaggio cambia, verso 9").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val action = compose.onNodeWithContentDescription(context.getString(R.string.lyrics))
            .fetchSemanticsNode().boundsInRoot
        val verse = compose.onNodeWithText("Una luce attraversa il lago")
            .fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Lyrics should stay close to the real action rail",
            verse.top - action.bottom in 16f..160f)
        captureFixture("1280x800-$name-lyrics-contrasted-blur")
    }

    @Test fun lyricsSelectionSurvivesBothBreakpoints() {
        val width = mutableStateOf(839)
        fixture(width, 600, withLyrics = true)
        compose.mainClock.advanceTimeBy(1000)
        openPanel(R.string.lyrics)
        for (size in listOf(840, 899, 900, 839)) {
            compose.runOnIdle { width.value = size }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("Una luce attraversa il lago").assertIsDisplayed()
        }
    }

    @Test fun selectedLyricsSurviveSavedStateRestoration() {
        fixture(mutableStateOf(1280), 800, withLyrics = true)
        compose.mainClock.advanceTimeBy(1000)
        openPanel(R.string.lyrics)
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Una luce attraversa il lago").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithContentDescription(context.getString(R.string.lyrics)).assertIsSelected()
    }

    @Test fun queueSelectionSurvivesBothBreakpoints() {
        val width = mutableStateOf(839)
        fixture(width, 600)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithContentDescription(context.getString(R.string.queue)).performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Coda persistente").assertIsDisplayed()
        for (size in listOf(840, 899, 900, 839)) {
            compose.runOnIdle { width.value = size }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("Coda persistente").assertIsDisplayed()
        }
    }
}
