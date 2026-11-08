package dev.lelonio.square.ui.airpods

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.protocol.PropertyValue
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared only. Synthetic software state is not a physical validation. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[36], application=Application::class, qualifiers="w800dp-h1000dp-mdpi")
class AirPodsUiPolishTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun label(id: Int) = context.getString(id)
    private fun mount(font: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val backdrop = rememberGlassBackdrop()
            val host = rememberFluidGlassModalHostState()
            val density = LocalDensity.current
            SquareTheme(darkTheme=false, amoled=false, systemBars=false) {
                CompositionLocalProvider(LocalDensity provides Density(density.density,font),
                    LocalGlassBackdrop provides backdrop, LocalFluidGlassModalHostState provides host,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.requiredSize(360.dp,640.dp)) {
                        Row(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
                            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.primaryContainer))
                            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface))
                        }
                        content()
                        FluidGlassModalHost(host, backdrop=backdrop)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    @Test fun allSettingsStaysVisibleAtFont2AndDismissesBeforeNavigation() {
        val events = mutableListOf<String>()
        mount(2f) { AirPodsQuickOverlay(uiFixture(), true, {events += "dismiss"}, {}, {events += "settings"}) }
        compose.onNodeWithTag("airpods-all-settings").assertIsDisplayed().performClick()
        assertEquals(listOf("dismiss","settings"), events)
    }
    @Test fun compactModeWritesTypedValueWithoutInventingSelection() {
        val actions = mutableListOf<AirPodsUiAction>()
        val fixture = uiFixture().let { it.copy(domain=it.domain.copy(mode=null)) }
        mount { AirPodsQuickOverlay(fixture,true,{}, {actions += it}, {}) }
        compose.onNodeWithText(label(R.string.airpods_feature_mode_transparency)).performScrollTo().assertIsNotSelected().performClick()
        assertEquals(listOf(AirPodsUiAction.Write(7,CommandProperty.MODE,PropertyValue.Number(3))),actions)
    }
    @Test fun liveQuickDoesNotDuplicateHistoricalBattery() {
        mount { AirPodsQuickOverlay(uiFixture(),true,{}, {}, {}) }
        compose.onNodeWithText("80%", substring=true).assertExists()
        compose.onAllNodesWithText("last known", substring=true).assertCountEquals(0)
        compose.onAllNodesWithText("1000 ms", substring=true).assertCountEquals(0)
    }
    private fun gesture(side: GestureSide, chosen: GestureAction) {
        val actions = mutableListOf<AirPodsUiAction>()
        val s = uiFixture().copy(availability=mapOf("gesture.$side" to UiAvailability(true)))
        mount { GesturePicker(s,side) {actions += it} }
        compose.onNodeWithTag("airpods-gesture-$side").performClick()
        compose.waitForIdle()
        val forbidden = GestureAction.entries - gestureOptions(side).toSet()
        forbidden.forEach { compose.onAllNodesWithText(label(gestureLabels.getValue(it))).assertCountEquals(0) }
        compose.onNodeWithText(label(gestureLabels.getValue(chosen))).performClick()
        assertEquals(listOf(AirPodsUiAction.AssignGesture(side,chosen)),actions)
    }
    @Test fun leftAssistantCallbackIsNotAbsorbedIntoNoiseControl() = gesture(GestureSide.LEFT,GestureAction.DIGITAL_ASSISTANT)
    @Test fun rightNoiseControlRemainsDistinct() = gesture(GestureSide.RIGHT,GestureAction.NOISE_CONTROL)
    @Test fun nodOffersOnlyMediaActions() = gesture(GestureSide.NOD,GestureAction.NEXT)
    @Test fun shakeAllowsExplicitNoAction() = gesture(GestureSide.SHAKE,GestureAction.NONE)
}
