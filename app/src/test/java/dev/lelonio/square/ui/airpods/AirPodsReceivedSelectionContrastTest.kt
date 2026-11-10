// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.ui.theme.SquareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w800dp-h1000dp-mdpi")
class AirPodsReceivedSelectionContrastTest {
    @get:Rule val compose = createComposeRule()
    private var captureCase = "unmounted"
    private fun mount(dark: Boolean, font: Float = 1f, content: @Composable () -> Unit) {
        captureCase = "${if (dark) "dark" else "light"}-$font"
        compose.setContent {
            SquareTheme(darkTheme = dark, amoled = false, systemBars = false) {
                val density = LocalDensity.current
                val backdrop = rememberGlassBackdrop()
                val host = rememberFluidGlassModalHostState()
                CompositionLocalProvider(LocalDensity provides Density(density.density, font),
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f),
                    LocalGlassBackdrop provides backdrop, LocalFluidGlassModalHostState provides host) {
                    Box(Modifier.requiredSize(360.dp, 640.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .glassBackdropSource(backdrop))
                        content()
                        FluidGlassModalHost(host, backdrop = backdrop)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    /** Use the project's native host renderer, rather than Android WindowCapture/PixelCopy. */
    private fun capture(tag: String): PixelMap {
        val output = File("build/airpods-glass/c9-contrast/$captureCase/$tag.png")
        output.parentFile?.mkdirs()
        compose.onNodeWithTag(tag, useUnmergedTree = true).captureRoboImage(output.path)
        return requireNotNull(BitmapFactory.decodeFile(output.path)) { "Native renderer output missing: $output" }
            .asImageBitmap().toPixelMap()
    }
    private fun ratio(a: Color, b: Color): Float =
        (max(a.luminance(), b.luminance()) + 0.05f) / (min(a.luminance(), b.luminance()) + 0.05f)
    /** The final reserved 18dp contains only the received check, never a label or leading icon. */
    private fun markerPixels(p: PixelMap): Int {
        val background = p[p.width - 4, p.height / 2]
        return (p.width - 24 until p.width - 6).sumOf { x ->
            (p.height / 2 - 9 until p.height / 2 + 9).count { y -> ratio(p[x, y], background) >= 3f }
        }
    }
    private fun assertReceivedMark(dark: Boolean, font: Float) {
        mount(dark, font) {
            Column(Modifier.width(176.dp)) {
                OptionCell("Ricevuto", true, null, Modifier.testTag("received"))
                OptionCell("Richiesto", false, null, Modifier.testTag("pending"), awaiting = true)
                OptionCell("Sconosciuto", false, {}, Modifier.testTag("unknown"))
            }
        }
        compose.onNodeWithTag("received").assertIsSelected().assert(hasClickAction().not())
        compose.onNodeWithTag("pending").assertIsNotSelected().assert(hasClickAction().not())
        compose.onNodeWithTag("unknown").assertIsNotSelected()
        val received = capture("received")
        val pending = capture("pending")
        val unknown = capture("unknown")
        assertTrue("Received mark must remain visible independently of its fill", markerPixels(received) >= 8)
        assertEquals("A requested value must never have the received mark", 0, markerPixels(pending))
        assertEquals("Unknown is not received", 0, markerPixels(unknown))
    }
    @Test fun receivedMarkIsVisibleInDarkTheme() = assertReceivedMark(true, 1f)
    @Test fun receivedMarkIsVisibleInLightTheme() = assertReceivedMark(false, 1f)
    @Test fun receivedMarkStaysDistinctFromPendingWithLargeText() = assertReceivedMark(true, 2f)
}
