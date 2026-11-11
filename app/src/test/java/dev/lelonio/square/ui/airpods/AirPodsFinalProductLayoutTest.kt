// SPDX-License-Identifier: GPL-3.0-or-later
//
// PROBE A-Opus, final review 2026-11-11.
// Root regression; original A probe and negative run are preserved in the review archive.
// Temporary: copy to app/src/test/java/dev/lelonio/square/ui/airpods/ in a scratch copy of the
// frozen source, run it, then delete it. Do not commit, do not leave it in the candidate.
//
// P1/P1b document finding A-Opus-02 (battery label clipping at 360 dp / font 2.0).
// P2/P2b document finding A-Opus-03 (master OFF: blocked switches without a reason, head gestures
// still offered although the runtime requires the master).
// Expected on the candidate as frozen: P1/P1b FAIL on "64%" if the archived 2026-11-10 renders are
// still representative; P2 FAIL (empty subtitle on EAR_PAUSE); P2b FAIL (HEAD_GESTURES enabled,
// no reason). A PASS falsifies the corresponding finding.
package dev.lelonio.square.ui.airpods

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import java.io.File
import dev.lelonio.square.airpods.BatteryValue
import dev.lelonio.square.airpods.Received
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
class AirPodsFinalProductLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun label(id: Int) = context.getString(id)

    private fun mount(font: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val backdrop = rememberGlassBackdrop()
            val host = rememberFluidGlassModalHostState()
            val density = LocalDensity.current
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalDensity provides Density(density.density, font),
                    LocalGlassBackdrop provides backdrop, LocalFluidGlassModalHostState provides host,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.fillMaxSize()) {
                        content()
                        FluidGlassModalHost(host, backdrop = backdrop)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** uiFixture() with three fresh, distinct readings (left 82, right 64, case 37). */
    private fun threeBatteries(): AirPodsUiState {
        val base = uiFixture()
        val g = base.domain.generation
        return base.copy(domain = base.domain.copy(batteries = mapOf(
            BatteryLeft to Received(BatteryValue(82, 2), 1000, g),
            BatteryRight to Received(BatteryValue(64, 2), 1000, g),
            BatteryCase to Received(BatteryValue(37, 1), 1000, g))))
    }

    private fun clipped(text: String): Boolean {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val result = results.single()
        if (result.hasVisualOverflow) println("BATTERY_LAYOUT[$text]: size=${result.size} " +
            "constraints=${result.layoutInput.constraints} widthOverflow=${result.didOverflowWidth} " +
            "heightOverflow=${result.didOverflowHeight} paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}")
        return result.hasVisualOverflow
    }

    @Test fun p1_settingsBatteryPercentNotClippedAt360dpFont200() {
        mount(2f) { AirPodsSettingsContent(threeBatteries(), {}) }
        val bad = listOf("82%", "64%", "37%").filter(::clipped)
        assertTrue("Clipped battery labels, settings page, 360dp/font 2.0: $bad", bad.isEmpty())
        capture("settings-battery-font200")
    }

    @Test fun p1b_quickBatteryPercentNotClippedAt360dpFont200() {
        mount(2f) { AirPodsQuickContent(threeBatteries(), {}) }
        val bad = listOf("82%", "64%", "37%").filter(::clipped)
        assertTrue("Clipped battery labels, quick pane, 360dp/font 2.0: $bad", bad.isEmpty())
        capture("quick-battery-font200")
    }

    private fun masterOff(): AirPodsUiState {
        val base = uiFixture()
        return base.copy(pair = base.pair.copy(host = base.pair.host.copy(
            automationsEnabled = false, earPause = true, headGestures = false)),
            availability = LocalToggle.entries.associate { "local.$it" to UiAvailability(true) })
    }

    /** Contract stated at AirPodsControls.kt:843: "A blocked switch says why in its second line". */
    @Test fun p2_masterOffBlockedEarPauseStatesItsReason() {
        val reason = label(R.string.airpods_reason_automations_off)
        mount(1f) { AirPodsSettingsContent(masterOff(), {}, page = AirPodsSettingsPage.Automations) }
        compose.onNode(hasText(label(toggleLabels.getValue(LocalToggle.EAR_PAUSE))) and isToggleable())
            .performScrollTo().assertIsNotEnabled()
            .assert(hasContentDescription(reason, substring = true))
        capture("automations-master-off")
    }

    /** Runtime requires the master for head gestures (AirPodsAndroidRuntime.kt:242, :630, :902). */
    @Test fun p2b_masterOffHeadGesturesAreMarkedDependent() {
        val reason = label(R.string.airpods_reason_automations_off)
        mount(1f) { AirPodsSettingsContent(masterOff(), {}, page = AirPodsSettingsPage.HeadGestures) }
        compose.onNode(hasText(label(toggleLabels.getValue(LocalToggle.HEAD_GESTURES))) and isToggleable())
            .performScrollTo()
            .assert(isNotEnabled() and hasContentDescription(reason, substring = true))
        capture("head-gestures-master-off")
    }

    @Test fun channelStatusRemainsVisibleWhenAllBatteryReadingsExpire() {
        val state = uiFixture().copy(nowMs = 301_000)
        mount(1f) { AirPodsSettingsContent(state, {}) }
        compose.onNodeWithTag("airpods-summary-state")
            .assertTextEquals(label(state.connectionSummary())).assertIsDisplayed()
        compose.onAllNodesWithText("80%").assertCountEquals(0)
        capture("channel-with-expired-battery")
    }

    private fun capture(name: String) {
        val file = File("build/final-product-layout/$name.png")
        file.parentFile.mkdirs()
        check(!file.exists() || file.delete())
        compose.onRoot().captureRoboImage(file.path,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        check(file.isFile)
    }
}
