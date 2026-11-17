// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared Compose wiring checks with synthetic state. No owner, transport or device. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w800dp-h1000dp-mdpi")
class AirPodsC5PagesTest {
    @get:Rule val compose = createComposeRule()
    private val writes = mutableListOf<AirPodsUiAction.Write>()
    private var state by mutableStateOf(uiFixture().let { it.copy(domain = it.domain.copy(properties = emptyMap())) })

    private fun mount(controls: Boolean = false) {
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    val dispatch: (AirPodsUiAction) -> Unit = { action ->
                        if (action is AirPodsUiAction.Write) {
                            writes += action
                            state = state.copy(domain = state.domain.copy(desired = state.domain.desired +
                                (action.property to DesiredProperty(action.value, writes.size.toLong(),
                                    action.generation, DesiredStatus.SENT, 1001))))
                        }
                    }
                    Column(Modifier.requiredSize(360.dp, 640.dp).verticalScroll(rememberScrollState())) {
                        if (controls) ControlsPage(state, dispatch) else EqualizerPage(state, dispatch)
                    }
                }
            }
        }
    }

    private fun slider(tag: String) = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and
            (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))))

    private fun assertNoApply() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onAllNodesWithText(context.getString(R.string.airpods_ui_apply)).assertCountEquals(0)
    }

    @Test fun eqMountsThreeSlidersAtDomainDefaultsWithoutWritingOrTextEditors() {
        mount()
        listOf("low", "mid", "high").forEach {
            slider("airpods-eq-$it").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                androidx.compose.ui.semantics.ProgressBarRangeInfo(50f, 0f..100f)))
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(0)
        assertNoApply()
        compose.onNodeWithTag("airpods-switch-EQ").assertIsOff()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
    }

    @Test fun resetRestoresAllBandsAndPreservesEnabledWithoutBypassingGate() {
        mount()
        slider("airpods-eq-low").performSemanticsAction(SemanticsActions.SetProgress) { it(82f) }
        compose.onNodeWithTag("airpods-eq-reset").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(PropertyValue.Eq(false, 50, 50, 50), writes.last().value)
            state = state.copy(bluetoothGranted = false)
        }
        compose.onNodeWithTag("airpods-eq-reset").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, writes.size) }
    }

    @Test fun talkBackBandChangeAndToggleSendCompleteEffectiveQuadruples() {
        mount()
        slider("airpods-eq-low").performSemanticsAction(SemanticsActions.SetProgress) { it(82f) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, writes.size)
            assertEquals(PropertyValue.Eq(false, 82, 50, 50), writes.single().value)
        }
        compose.onNodeWithTag("airpods-switch-EQ").performClick()
        compose.onNodeWithTag("airpods-switch-EQ").assertIsOn()
        compose.runOnIdle {
            assertEquals(2, writes.size)
            assertEquals(PropertyValue.Eq(true, 82, 50, 50), writes.last().value)
        }
    }

    @Test fun controlsUseEffectiveChoicesAndToneSliderWithoutApplyOrEditors() {
        mount(controls = true)
        compose.onNodeWithTag("airpods-choice-DOUBLE_CLICK_INTERVAL-0").assertIsSelected()
        compose.onNodeWithTag("airpods-choice-DOUBLE_CLICK_INTERVAL-2").performClick()
        compose.onNodeWithTag("airpods-choice-DOUBLE_CLICK_INTERVAL-2").assertIsSelected()
        slider("airpods-chime-volume").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(42f) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(CommandProperty.DOUBLE_CLICK_INTERVAL, CommandProperty.CHIME_VOLUME),
                writes.map { it.property })
            assertEquals(PropertyValue.Number(42), writes.last().value)
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(0)
        assertNoApply()
    }
}
