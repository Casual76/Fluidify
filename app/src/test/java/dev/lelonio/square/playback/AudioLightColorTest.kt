package dev.lelonio.square.playback

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.lelonio.square.ui.player.contrastingAudioLightColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class AudioLightColorTest {
    @Test fun canvasUsesASeparateColourAlreadyInTheCover() {
        val pink = Color(0xffdc7095)
        val violet = Color(0xff723faa)
        assertEquals(violet, contrastingAudioLightColor(pink, listOf(pink, Color(0xffdb8099), violet), Color.Blue))
    }

    @Test fun oneColourCoverGetsAStableDistinctAccent() {
        val orange = Color(0xffc6732c)
        val accent = contrastingAudioLightColor(orange, listOf(orange), Color.Blue)
        fun hue(color: Color) = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) }[0]
        val distance = kotlin.math.abs(hue(accent) - hue(orange))
        assertTrue(minOf(distance, 360 - distance) > 80)
        assertEquals(accent, contrastingAudioLightColor(orange, listOf(orange), Color.Blue))
    }

    @Test fun monochromeCoverUsesTheThemeAccent() {
        assertEquals(Color.Blue, contrastingAudioLightColor(Color.White, listOf(Color.White, Color.Gray), Color.Blue))
    }
}
