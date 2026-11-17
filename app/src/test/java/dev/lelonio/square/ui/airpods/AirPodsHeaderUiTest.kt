// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.storage.*
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared for I2; synthetic presentation tests, no radio or hardware qualification. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w800dp-h1000dp-mdpi")
class AirPodsHeaderUiTest {
    @get:Rule val compose = createComposeRule()

    private fun mount(content: @Composable () -> Unit) {
        compose.setContent {
            val backdrop = rememberGlassBackdrop()
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) { content() }
            }
        }
    }

    @Test fun renamePrefillsSendsAndFollowsLateInformationWithoutOverwritingNewEdit() {
        var state by mutableStateOf(uiFixture().let { s -> s.copy(domain = s.domain.copy(
            properties = mapOf(CommandProperty.NAME to Received(PropertyValue.Name("Known"), 1000, 7)))) })
        val actions = mutableListOf<AirPodsUiAction>()
        mount {
            Column {
                Text(deviceTitle(state))
                RenamePage(state) { action ->
                    actions += action
                    val write = action as AirPodsUiAction.Write
                    state = state.copy(domain = state.domain.copy(desired = mapOf(CommandProperty.NAME to
                        DesiredProperty(write.value, 1, 7, DesiredStatus.SENT, 1001))))
                }
            }
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("Known").performTextReplacement("Requested")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNode(hasText(context.getString(R.string.airpods_feature_setting_device_rename)) and hasClickAction()).performClick()
        compose.onNode(hasText("Requested") and !hasSetTextAction()).assertExists()
        compose.runOnIdle {
            assertEquals(PropertyValue.Name("Requested"), (actions.single() as AirPodsUiAction.Write).value)
            state = state.copy(domain = state.domain.copy(desired = emptyMap(), properties = mapOf(
                CommandProperty.NAME to Received(PropertyValue.Name("Late information"), 60000, 7))))
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("Late information").performTextReplacement("Unsent")
        compose.runOnIdle {
            state = state.copy(domain = state.domain.copy(properties = mapOf(CommandProperty.NAME to
                Received(PropertyValue.Name("External name"), 70000, 7))))
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("Unsent")
        // The identity line used to expose this fixture's manufacturer on the rename page.
        compose.onNodeWithText("Apple Inc.", substring = true).assertDoesNotExist()
    }

    @Test fun savedCaseHasVisibleAndSpokenStaleLabel() {
        val s = uiFixture().let { it.copy(domain = it.domain.copy(cachedValues = AirPodsPairValues(
            casePercent = SavedCasePercent(61, 1234)))) }
        mount { AirPodsBatteryView(s) }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val stale = context.getString(R.string.airpods_header_not_live)
        compose.onNodeWithText(stale, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(stale, substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.airpods_feature_percent, 61), useUnmergedTree = true).assertExists()
    }
}
