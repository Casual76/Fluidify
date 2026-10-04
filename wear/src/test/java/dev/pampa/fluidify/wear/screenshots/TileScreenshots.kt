package dev.pampa.fluidify.wear.screenshots

import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ProtoLayoutScope
import androidx.wear.protolayout.expression.VersionBuilders.VersionInfo
import androidx.wear.protolayout.material3.materialScopeWithResources
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.loadAction
import androidx.wear.tiles.renderer.TileRenderer
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.system.COVER_PX
import dev.pampa.fluidify.wear.system.CoverImages
import dev.pampa.fluidify.wear.system.PlayerTileClicks
import dev.pampa.fluidify.wear.system.PlayerTileModel
import dev.pampa.fluidify.wear.system.fluidifyTileColors
import dev.pampa.fluidify.wear.system.playerTileLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * The tile, laid out by the code the service runs and drawn by Wear's own tile
 * renderer. The playing render doubles as the tile's preview in the picker
 * (copied to res/drawable-nodpi/tile_preview.png).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = Watch44)
class TileScreenshots {

    private val playing = PlayerTileModel(
        title = "Notturno sul lago",
        artist = "Aurora Viola",
        isPlaying = true,
        liked = true,
        trackUri = "spotify:track:1",
        coverKey = SampleArtKey,
        positionMs = 84_000,
        durationMs = 212_000,
        // Now: the ring is worked out from the renderer's clock, as on the watch.
        sampledAtEpochMs = System.currentTimeMillis(),
    )

    @Test
    fun playing() = render("tile_playing", playing, withCover = true)

    /** Past half way: the right arc full, the left one filling. */
    @Test
    fun playingLate() = render("tile_playing_late", playing.copy(positionMs = 170_000), withCover = true)

    /** The smallest round watch: the buttons must fit, and the ring stay clear of the edge button. */
    @Test
    @Config(qualifiers = WatchSmall)
    fun playingSmallestWatch() = render("tile_playing_192dp", playing, withCover = true, widthDp = 192)

    @Test
    fun pausedNoCoverYet() = render("tile_paused_no_cover", playing.copy(isPlaying = false, liked = false), withCover = false)

    @Test
    fun nothingPlaying() = render(
        "tile_nothing",
        PlayerTileModel(title = "", artist = "", isPlaying = false, liked = null, trackUri = null, coverKey = null),
        withCover = false,
    )

    private fun render(name: String, model: PlayerTileModel, withCover: Boolean, widthDp: Int = 240) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val art = ArtStore(activity)
        storeSampleCover(art)
        val cover = if (withCover) CoverImages.compressed(art, SampleArtKey, COVER_PX) else null
        val backdrop = if (withCover) CoverImages.backdrop(art, SampleArtKey) else null

        val scope = ProtoLayoutScope()
        val device = DeviceParametersBuilders.DeviceParameters.Builder()
            .setScreenWidthDp(widthDp)
            .setScreenHeightDp(widthDp)
            .setScreenDensity(2f)
            // Text that must not grow with the font size is divided by it; the system always
            // sends one, a hand-built request has to.
            .setFontScale(1f)
            .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
            .setDevicePlatform(DeviceParametersBuilders.DEVICE_PLATFORM_WEAR_OS)
            .setRendererSchemaVersion(VersionInfo.Builder().setMajor(1).setMinor(500).build())
            .build()
        val element = materialScopeWithResources(activity, scope, device, allowDynamicTheme = false, defaultColorScheme = fluidifyTileColors()) {
            playerTileLayout(model, FakeClicks, cover, backdrop = backdrop)
        }

        val frame = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
        }
        activity.setContentView(frame, ViewGroup.LayoutParams(widthDp * 2, widthDp * 2))
        val renderer = TileRenderer(activity, ContextCompat.getMainExecutor(activity)) { }
        val future = renderer.inflateAsync(Layout.fromLayoutElement(element), scope.collectResources(), frame)
        shadowOf(Looper.getMainLooper()).idle()
        future.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        frame.captureRoboImage("screenshots/$name.png")
    }


    private object FakeClicks : PlayerTileClicks {
        override val open: Clickable = clickable(loadAction(), "open")
        override val previous: Clickable = clickable(loadAction(), "previous")
        override val toggle: Clickable = clickable(loadAction(), "toggle")
        override val next: Clickable = clickable(loadAction(), "next")
        override val like: Clickable = clickable(loadAction(), "like")
        override val resume: Clickable = clickable(loadAction(), "resume")
    }
}
