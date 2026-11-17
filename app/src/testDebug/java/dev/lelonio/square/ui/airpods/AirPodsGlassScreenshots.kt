// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import dev.lelonio.square.R
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The three AirPods surfaces on the debug glass stage ([AirPodsGlassStage]): the real page host,
 * the real portal, the artwork canvas wired as in SquareApp, invented state. Prepared captures,
 * not recorded or approved here.
 *
 * Start with the `board*` captures: the capsule, the quick pane's content and the main settings
 * page side by side in one picture. The rest is one surface, one page or one state per picture.
 * The directory is chosen by the environment, so two runs cannot overwrite each other:
 *
 *     AIRPODS_UI_CAPTURE=name   -> app/build/airpods-ui-reuse/name/
 *     (unset)                   -> app/build/airpods-ui-reuse/captures/
 *
 * What these can and cannot show. Robolectric draws in software unless it is told otherwise, and
 * a software canvas has no RenderEffect: layout, pictures, type, spacing, wrapping, theme sides
 * and what each state says are faithful here, the refraction and blur of the material are not,
 * and nothing here says how the selector feels under a finger. Both are judged on the same stage
 * on a device, through AirPodsGlassPreviewActivity. Every frame carries the stage's DEMO notice:
 * none of this is a reading of a device.
 */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "it-rIT-w412dp-h892dp-xhdpi")
class AirPodsGlassScreenshots {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)

    private val variant = (System.getenv("AIRPODS_UI_CAPTURE") ?: "captures")
        .filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "captures" }

    private fun shoot(name: String) {
        val output = File("build/airpods-ui-reuse/$variant").apply { mkdirs() }
        compose.waitForIdle()
        compose.onNodeWithTag("airpods-demo-notice").assertExists()
        val file = File(output, "$name.png")
        check(!file.exists() || file.delete())
        compose.onRoot().captureRoboImage(file.path,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        check(file.isFile) { "Capture was not recorded: $file" }
    }

    /** Brings the first node printing [target] to the top of the page, under the bar. */
    private fun bringToTop(target: String) {
        val node = compose.onAllNodesWithText(target).onFirst()
        node.performScrollTo()
        compose.waitForIdle()
        val scroller = compose.onAllNodes(hasScrollAction()).onFirst()
        val top = node.fetchSemanticsNode().boundsInRoot.top
        val origin = scroller.fetchSemanticsNode().boundsInRoot.top
        scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, top - origin - 24f) }
        compose.waitForIdle()
    }

    private fun stage(name: String, view: String = "page", dark: Boolean = true, amoled: Boolean = false,
        fontScale: Float = 1f, state: AirPodsUiState = AirPodsDemo.connected,
        page: AirPodsSettingsPage = AirPodsSettingsPage.Main, scrollTo: Int? = null) {
        compose.setContent {
            AirPodsGlassStage(dark = dark, amoled = amoled, fontScale = fontScale, view = view, state = state, page = page)
        }
        compose.waitForIdle()
        if (scrollTo != null) bringToTop(text(scrollTo))
        shoot(name)
    }

    private val gateOff get() = AirPodsDemo.gateOff(text(R.string.airpods_reason_gate))
    private val disconnected get() = AirPodsDemo.disconnected(text(R.string.airpods_reason_connection))

    // The three surfaces together. Wide enough for the three 380 dp columns without scrolling.
    @Test @Config(qualifiers = "it-rIT-w1240dp-h900dp-xhdpi")
    fun boardDark() = stage("board-dark", view = "board")
    @Test @Config(qualifiers = "it-rIT-w1240dp-h900dp-xhdpi")
    fun boardLight() = stage("board-light", view = "board", dark = false)
    @Test @Config(qualifiers = "it-rIT-w1240dp-h900dp-xhdpi")
    fun boardLocalIntentMode() = stage("board-dark-local-intent-mode", view = "board", state = AirPodsDemo.localIntent)
    @Test @Config(qualifiers = "it-rIT-w1240dp-h900dp-xhdpi")
    fun boardPairedOptimisedCharging() = stage("board-dark-paired", view = "board", state = AirPodsDemo.paired)
    @Test @Config(qualifiers = "it-rIT-w1240dp-h900dp-xhdpi")
    fun boardPartialBattery() = stage("board-light-partial-battery", view = "board", dark = false, state = AirPodsDemo.partial)
    @Test @Config(qualifiers = "it-rIT-w1240dp-h900dp-xhdpi")
    fun boardDisconnected() = stage("board-dark-disconnected", view = "board", state = disconnected)
    @Test @Config(qualifiers = "it-rIT-w1240dp-h900dp-xhdpi")
    fun boardGateOff() = stage("board-dark-gate-off", view = "board", state = gateOff)
    @Test @Config(qualifiers = "it-rIT-w1240dp-h900dp-xhdpi")
    fun boardLongPress() = stage("board-dark-long-press", view = "board", page = AirPodsSettingsPage.LongPressLeft)

    // The main page, top to bottom.
    @Test fun pageDark() = stage("page-412-dark")
    @Test fun pageLight() = stage("page-412-light", dark = false)
    @Test fun pageAmoled() = stage("page-412-amoled", amoled = true)
    @Test fun pageDarkAudio() = stage("page-412-dark-audio", scrollTo = R.string.airpods_feature_audio_group)
    @Test fun pageLightSwitches() = stage("page-412-light-switches", dark = false,
        scrollTo = R.string.airpods_feature_setting_aacp_ear_detection_config)
    @Test fun pageDarkEnd() = stage("page-412-dark-end", scrollTo = R.string.airpods_ui_connect_setup)

    // The page behind every row.
    @Test fun pageRename() = stage("page-412-dark-rename", page = AirPodsSettingsPage.Rename)
    @Test fun pageLongPressLeft() = stage("page-412-dark-long-press-left", page = AirPodsSettingsPage.LongPressLeft)
    @Test fun pageLongPressRight() = stage("page-412-light-long-press-right", dark = false, page = AirPodsSettingsPage.LongPressRight)
    @Test fun pageCalls() = stage("page-412-dark-calls", page = AirPodsSettingsPage.Calls)
    @Test fun pageAdaptiveStrength() = stage("page-412-dark-adaptive-inline", scrollTo = R.string.airpods_feature_audio_group)
    @Test fun pageEqualizer() = stage("page-412-light-equalizer", dark = false, page = AirPodsSettingsPage.Equalizer)
    @Test fun pageControls() = stage("page-412-dark-controls", page = AirPodsSettingsPage.Controls)
    @Test fun pageHeadGestures() = stage("page-412-dark-head-gestures", page = AirPodsSettingsPage.HeadGestures)
    @Test fun pageAutomations() = stage("page-412-dark-automations", page = AirPodsSettingsPage.Automations)
    @Test fun pageSetup() = stage("page-412-light-setup", dark = false, page = AirPodsSettingsPage.Setup)
    @Test fun pageDiagnostics() = stage("page-412-dark-diagnostics", page = AirPodsSettingsPage.Diagnostics)

    // The states that must not be dressed up.
    @Test fun pageGateOffDark() = stage("page-412-dark-gate-off", state = gateOff)
    @Test fun pageGateOffLight() = stage("page-412-light-gate-off", dark = false, state = gateOff)
    @Test fun pageGateOffSetup() = stage("page-412-dark-gate-off-setup", state = gateOff, page = AirPodsSettingsPage.Setup)
    @Test fun pageDisconnected() = stage("page-412-dark-disconnected", state = disconnected)
    @Test fun pagePartialBattery() = stage("page-412-dark-partial-battery", state = AirPodsDemo.partial)
    @Test fun pageLocalIntentMode() = stage("page-412-dark-local-intent-mode", state = AirPodsDemo.localIntent)

    @Test fun pageContraryRx() = stage("page-contrary-rx", state = AirPodsDemo.contraryRx)
    @Test fun pageCached() = stage("page-cached", state = AirPodsDemo.cached)

    // The quick pane, declared through the real portal over a player-like top.
    @Test fun quickDark() = stage("quick-412-dark", view = "quick")
    @Test fun quickLight() = stage("quick-412-light", view = "quick", dark = false)
    @Test fun quickLocalIntent() = stage("quick-412-dark-local-intent-mode", view = "quick", state = AirPodsDemo.localIntent)
    @Test fun quickPartialBattery() = stage("quick-412-light-partial-battery", view = "quick", dark = false,
        state = AirPodsDemo.partial)
    @Test fun quickDisconnected() = stage("quick-412-dark-disconnected", view = "quick", state = disconnected)

    // The capsule at the widths Home (96, 120) and the player (160) give it.
    @Test fun capsulesDark() = stage("capsules-412-dark", view = "capsules")
    @Test fun capsulesLight() = stage("capsules-412-light", view = "capsules", dark = false)
    @Test fun capsulesPartial() = stage("capsules-412-dark-partial", view = "capsules", state = AirPodsDemo.partial)
    @Test fun capsulesDisconnected() = stage("capsules-412-dark-disconnected", view = "capsules", state = disconnected)

    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun narrowPage() = stage("page-360-dark")
    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun narrowQuick() = stage("quick-360-dark", view = "quick")
    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun narrowQuickLarge() = stage("quick-360-font200-dark", view = "quick", fontScale = 2f)
    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun narrowPageLarge() = stage("page-360-font200-light", dark = false, fontScale = 2f)
    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun narrowCapsulesLarge() = stage("capsules-360-font200-dark", view = "capsules", fontScale = 2f)
    @Test @Config(qualifiers = "it-rIT-w720dp-h360dp-xhdpi")
    fun landscapeQuick() = stage("quick-720x360-dark", view = "quick")
    @Test @Config(qualifiers = "it-rIT-w800dp-h1280dp-xhdpi")
    fun tabletPage() = stage("page-tablet-dark")
    @Test @Config(qualifiers = "en-rUS-w412dp-h892dp-xhdpi")
    fun englishPage() = stage("page-412-dark-en")
    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun germanQuick() = stage("quick-360-dark-de", view = "quick")
    // P1: the original 51 scenes plus large-font pages that the old gallery did not expose.
    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun narrowLargeName() = stage("p1-name-360-font200", fontScale = 2f, scrollTo = R.string.airpods_p1_name)
    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun narrowLargeEnd() = stage("p1-end-360-font200", fontScale = 2f, scrollTo = R.string.airpods_ui_connect_setup)
    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun narrowLargeControls() = stage("p1-controls-360-font200", fontScale = 2f, page = AirPodsSettingsPage.Controls)
    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun narrowLargeLongPress() = stage("p1-longpress-360-font200", fontScale = 2f, page = AirPodsSettingsPage.LongPressLeft)
    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun narrowLargeEqualizer() = stage("p1-equalizer-360-font200", dark = false, fontScale = 2f, page = AirPodsSettingsPage.Equalizer)

    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun germanQuickLarge() = stage("cr-quick-de-360-font200", view = "quick", fontScale = 2f)
    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun germanLongPressLarge() = stage("cr-longpress-de-360-font200", page = AirPodsSettingsPage.LongPressLeft, fontScale = 2f)
    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun germanNameLarge() = stage("cr-name-de-360-font200", scrollTo = R.string.airpods_p1_name, fontScale = 2f)

    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun germanQuickModesLarge() = stage("cr-quick-modes-de-360-font200", view = "quick", fontScale = 2f,
        scrollTo = R.string.airpods_feature_mode_transparency)
    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun italianQuickModesLarge() = stage("cr-quick-modes-it-360-font200", view = "quick", fontScale = 2f,
        scrollTo = R.string.airpods_feature_mode_transparency)
    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun germanLongPressBottomLarge() = stage("cr-longpress-bottom-de-360-font200", page = AirPodsSettingsPage.LongPressLeft,
        fontScale = 2f, scrollTo = R.string.airpods_feature_mode_anc)
    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun italianLongPressBottomLarge() = stage("cr-longpress-bottom-it-360-font200", page = AirPodsSettingsPage.LongPressLeft,
        fontScale = 2f, scrollTo = R.string.airpods_feature_mode_anc)

    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun matrixpageit360font100() {
        stage("cr2-page-it-360-font100", view = "page", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun matrixquickit360font100() {
        stage("cr2-quick-it-360-font100", view = "quick", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun matrixpageit360font130() {
        stage("cr2-page-it-360-font130", view = "page", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun matrixquickit360font130() {
        stage("cr2-quick-it-360-font130", view = "quick", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun matrixpageit360font150() {
        stage("cr2-page-it-360-font150", view = "page", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun matrixquickit360font150() {
        stage("cr2-quick-it-360-font150", view = "quick", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun matrixpageit360font200() {
        stage("cr2-page-it-360-font200", view = "page", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w360dp-h640dp-xhdpi")
    fun matrixquickit360font200() {
        stage("cr2-quick-it-360-font200", view = "quick", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w412dp-h892dp-xhdpi")
    fun matrixpageit412font100() {
        stage("cr2-page-it-412-font100", view = "page", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w412dp-h892dp-xhdpi")
    fun matrixquickit412font100() {
        stage("cr2-quick-it-412-font100", view = "quick", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w412dp-h892dp-xhdpi")
    fun matrixpageit412font130() {
        stage("cr2-page-it-412-font130", view = "page", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w412dp-h892dp-xhdpi")
    fun matrixquickit412font130() {
        stage("cr2-quick-it-412-font130", view = "quick", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w412dp-h892dp-xhdpi")
    fun matrixpageit412font150() {
        stage("cr2-page-it-412-font150", view = "page", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w412dp-h892dp-xhdpi")
    fun matrixquickit412font150() {
        stage("cr2-quick-it-412-font150", view = "quick", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w412dp-h892dp-xhdpi")
    fun matrixpageit412font200() {
        stage("cr2-page-it-412-font200", view = "page", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "it-rIT-w412dp-h892dp-xhdpi")
    fun matrixquickit412font200() {
        stage("cr2-quick-it-412-font200", view = "quick", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun matrixpagede360font100() {
        stage("cr2-page-de-360-font100", view = "page", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun matrixquickde360font100() {
        stage("cr2-quick-de-360-font100", view = "quick", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun matrixpagede360font130() {
        stage("cr2-page-de-360-font130", view = "page", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun matrixquickde360font130() {
        stage("cr2-quick-de-360-font130", view = "quick", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun matrixpagede360font150() {
        stage("cr2-page-de-360-font150", view = "page", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun matrixquickde360font150() {
        stage("cr2-quick-de-360-font150", view = "quick", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun matrixpagede360font200() {
        stage("cr2-page-de-360-font200", view = "page", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-xhdpi")
    fun matrixquickde360font200() {
        stage("cr2-quick-de-360-font200", view = "quick", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w412dp-h892dp-xhdpi")
    fun matrixpagede412font100() {
        stage("cr2-page-de-412-font100", view = "page", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w412dp-h892dp-xhdpi")
    fun matrixquickde412font100() {
        stage("cr2-quick-de-412-font100", view = "quick", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w412dp-h892dp-xhdpi")
    fun matrixpagede412font130() {
        stage("cr2-page-de-412-font130", view = "page", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w412dp-h892dp-xhdpi")
    fun matrixquickde412font130() {
        stage("cr2-quick-de-412-font130", view = "quick", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w412dp-h892dp-xhdpi")
    fun matrixpagede412font150() {
        stage("cr2-page-de-412-font150", view = "page", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w412dp-h892dp-xhdpi")
    fun matrixquickde412font150() {
        stage("cr2-quick-de-412-font150", view = "quick", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w412dp-h892dp-xhdpi")
    fun matrixpagede412font200() {
        stage("cr2-page-de-412-font200", view = "page", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "de-rDE-w412dp-h892dp-xhdpi")
    fun matrixquickde412font200() {
        stage("cr2-quick-de-412-font200", view = "quick", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun matrixpageen360font100() {
        stage("cr2-page-en-360-font100", view = "page", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun matrixquicken360font100() {
        stage("cr2-quick-en-360-font100", view = "quick", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun matrixpageen360font130() {
        stage("cr2-page-en-360-font130", view = "page", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun matrixquicken360font130() {
        stage("cr2-quick-en-360-font130", view = "quick", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun matrixpageen360font150() {
        stage("cr2-page-en-360-font150", view = "page", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun matrixquicken360font150() {
        stage("cr2-quick-en-360-font150", view = "quick", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun matrixpageen360font200() {
        stage("cr2-page-en-360-font200", view = "page", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun matrixquicken360font200() {
        stage("cr2-quick-en-360-font200", view = "quick", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w412dp-h892dp-xhdpi")
    fun matrixpageen412font100() {
        stage("cr2-page-en-412-font100", view = "page", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w412dp-h892dp-xhdpi")
    fun matrixquicken412font100() {
        stage("cr2-quick-en-412-font100", view = "quick", fontScale = 1.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w412dp-h892dp-xhdpi")
    fun matrixpageen412font130() {
        stage("cr2-page-en-412-font130", view = "page", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-mode-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w412dp-h892dp-xhdpi")
    fun matrixquicken412font130() {
        stage("cr2-quick-en-412-font130", view = "quick", fontScale = 1.3f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-labels"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w412dp-h892dp-xhdpi")
    fun matrixpageen412font150() {
        stage("cr2-page-en-412-font150", view = "page", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w412dp-h892dp-xhdpi")
    fun matrixquicken412font150() {
        stage("cr2-quick-en-412-font150", view = "quick", fontScale = 1.5f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w412dp-h892dp-xhdpi")
    fun matrixpageen412font200() {
        stage("cr2-page-en-412-font200", view = "page", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-mode-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-rUS-w412dp-h892dp-xhdpi")
    fun matrixquicken412font200() {
        stage("cr2-quick-en-412-font200", view = "quick", fontScale = 2.0f)
        compose.onAllNodes(hasTestTag("airpods-quick-MODE-legend"), useUnmergedTree = true).assertCountEquals(1)
    }

}
