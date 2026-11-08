package dev.lelonio.square.ui.airpods.host

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.airpods.runtime.android.AirPodsAndroidController
import dev.lelonio.square.ui.airpods.*
import dev.lelonio.square.ui.theme.SquareTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w600dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirPodsSetupRowAvailabilityTest {
    @get:Rule val compose = createComposeRule()
    @Test fun realHostAvailableSetupRowsHaveNoUnavailableHintAndDisabledRowKeepsReason() = runBlocking {
        org.robolectric.shadows.ShadowProcess.setPid(41998)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val owner = AirPodsAndroidController(context, MutableStateFlow(false), { false }, null)
        val host = AirPodsHost(context, owner)
        val clicks = mutableListOf<AirPodsUiAction>()
        try {
            host.refresh(); val state = host.state.value
            assertTrue(state.allowed("setup.BLUETOOTH_SETTINGS"))
            assertTrue(state.allowed("setup.EXPORT_DIAGNOSTICS"))
            assertTrue(state.allowed("setup.DELETE_DIAGNOSTICS"))
            assertFalse(state.allowed("setup.INFORMATION"))
            assertEquals(context.getString(R.string.airpods_reason_selection), state.availability.getValue("setup.INFORMATION").reason)
            compose.setContent {
                val backdrop = rememberGlassBackdrop()
                SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                    CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                        LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                        Column {
                            SetupButton(state, SetupAction.BLUETOOTH_SETTINGS, R.string.airpods_feature_connect, clicks::add)
                            SetupButton(state, SetupAction.EXPORT_DIAGNOSTICS, R.string.airpods_feature_title, clicks::add)
                            SetupButton(state, SetupAction.DELETE_DIAGNOSTICS, R.string.airpods_feature_stop, clicks::add)
                            SetupButton(state, SetupAction.INFORMATION, R.string.airpods_feature_title, clicks::add)
                        }
                    }
                }
            }
            compose.onAllNodesWithText(context.getString(R.string.airpods_ui_unavailable)).assertCountEquals(0)
            compose.onNodeWithText(state.availability.getValue("setup.INFORMATION").reason!!).assertExists()
            compose.onNodeWithTag("airpods-setup-INFORMATION").assertHasNoClickAction()
            compose.onNodeWithTag("airpods-setup-BLUETOOTH_SETTINGS").assertHasClickAction().performClick()
            assertEquals(listOf(AirPodsUiAction.Setup(SetupAction.BLUETOOTH_SETTINGS)), clicks)
        } finally {
            host.close(); owner.stopControls()
            context.unregisterReceiver(ReflectionHelpers.getField(owner, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(owner, "processScope").cancel()
        }
    }
}
