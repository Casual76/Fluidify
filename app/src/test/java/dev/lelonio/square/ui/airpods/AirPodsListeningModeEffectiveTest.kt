// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.storage.AirPodsPairValues
import dev.lelonio.square.airpods.runtime.storage.SavedProperty
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Prepared for I2: synthetic Compose input and frame geometry, no hardware claims. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w800dp-h1000dp-mdpi")
class AirPodsListeningModeEffectiveTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(uiFixture())
    private val writes = mutableListOf<ListeningMode>()

    private fun mount(twoSurfaces: Boolean = false, accept: Boolean = true) {
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                Column(Modifier.width(360.dp)) {
                    val dispatch: (AirPodsUiAction) -> Unit = { action ->
                        if (action is AirPodsUiAction.Write && action.property == CommandProperty.MODE) {
                            val wire = (action.value as PropertyValue.Number).value
                            writes += ListeningMode.entries.first { it.wire == wire }
                            if (accept) {
                                val s = state.value
                                state.value = s.copy(domain = s.domain.copy(desired = mapOf(
                                    CommandProperty.MODE to DesiredProperty(action.value, writes.size.toLong(),
                                        s.domain.generation, DesiredStatus.SENT, s.nowMs))))
                            }
                        }
                    }
                    ModeControl(state.value, dispatch, "page")
                    if (twoSurfaces) AirPodsQuickContent(state.value, dispatch)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun left(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.left

    private fun assertThumbAt(prefix: String, mode: ListeningMode) {
        // Slot dividers occupy one pixel; tolerate their accumulated layout rounding.
        assertTrue("thumb and selected slot differ", abs(left("$prefix-thumb") - left("$prefix-${mode.wire}")) < 4f)
    }

    @Test fun rapidChoicesStayEffectiveAcrossBothSurfacesWithoutRxOrTimeoutRollback() {
        mount(twoSurfaces = true)
        compose.onNodeWithTag("page-3").performClick()
        compose.onNodeWithTag("airpods-quick-MODE-4").performClick()
        compose.onNodeWithTag("page-1").performClick()
        compose.mainClock.advanceTimeBy(60_000)
        for (prefix in listOf("page", "airpods-quick-MODE")) {
            compose.onNodeWithTag("$prefix-1").assertIsSelected()
            compose.onNodeWithTag("$prefix-2").assertHasClickAction()
            assertThumbAt(prefix, ListeningMode.OFF)
        }
        assertEquals(listOf(ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE, ListeningMode.OFF), writes)
        assertEquals(ListeningMode.ANC, state.value.domain.mode?.value) // intent never becomes RX
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        for (id in listOf(R.string.airpods_ui_pending, R.string.airpods_ui_not_confirmed)) {
            compose.onAllNodesWithText(context.getString(id)).assertCountEquals(0)
        }
    }

    @Test fun dragThroughClickableMarksFollowsFingerAndReleaseDoesNotReturnToOldMode() {
        mount()
        val track = compose.onNodeWithTag("page-track")
        val width = track.fetchSemanticsNode().boundsInRoot.width
        val slot = width / 4f
        // ANC (last slot) -> Transparency. Multiple moves cross slop while still on the thumb.
        track.performTouchInput {
            down(Offset(slot * 3.5f, centerY))
            for (step in 1..12) moveTo(Offset(slot * 3.5f - step * slot / 6f, centerY))
        }
        compose.waitForIdle()
        val held = left("page-thumb")
        assertTrue("drag was intercepted by clickable marks", held < left("page-2") - slot)
        assertEquals(emptyList<ListeningMode>(), writes) // no write before release
        track.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("page-3").assertIsSelected()
        assertThumbAt("page", ListeningMode.TRANSPARENCY)
        assertEquals(listOf(ListeningMode.TRANSPARENCY), writes)
    }

    @Test fun contradictoryRxAnimatesBothThumbsWithoutEchoWrite() {
        mount(twoSurfaces = true)
        compose.onNodeWithTag("page-1").performClick()
        compose.mainClock.advanceTimeBy(1500)
        assertThumbAt("airpods-quick-MODE", ListeningMode.OFF)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            val s = state.value
            state.value = s.copy(domain = s.domain.copy(desired = emptyMap(),
                mode = Received(ListeningMode.ANC, s.nowMs + 1, s.domain.generation)))
        }
        compose.mainClock.advanceTimeByFrame()
        val firstFrame = left("page-thumb")
        compose.mainClock.advanceTimeBy(64)
        val movingFrame = left("page-thumb")
        assertTrue("RX must move the thumb", movingFrame > firstFrame)
        assertTrue("RX must animate rather than jump", movingFrame < left("page-2"))
        compose.mainClock.advanceTimeBy(1500)
        compose.mainClock.autoAdvance = true
        for (prefix in listOf("page", "airpods-quick-MODE")) {
            compose.onNodeWithTag("$prefix-2").assertIsSelected()
            assertThumbAt(prefix, ListeningMode.ANC)
        }
        assertEquals(listOf(ListeningMode.OFF), writes)
    }

    @Test fun rejectedDispatchDoesNotInventLocalSelection() {
        mount(accept = false)
        compose.onNodeWithTag("page-3").performClick()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("page-2").assertIsSelected()
        assertThumbAt("page", ListeningMode.ANC)
    }

    @Test fun explicitTransparencySendsForDefaultAndCacheAndResendsKnownValue() {
        val initial = state.value
        val property = CommandProperty.MODE
        state.value = initial.copy(domain = initial.domain.copy(mode = null))
        mount(twoSurfaces = true)
        val transparency = compose.onNodeWithTag("page-3")
        transparency.assertHasClickAction().assertIsNotSelected().performClick()
        transparency.assertIsSelected().assertHasClickAction()
        assertEquals(listOf(ListeningMode.TRANSPARENCY), writes)

        compose.runOnIdle {
            state.value = initial.copy(domain = initial.domain.copy(mode = null,
                cachedValues = AirPodsPairValues(observed = mapOf(property to SavedProperty(PropertyValue.Number(3), 1)))))
        }
        compose.onNodeWithTag("airpods-quick-MODE-3").assertHasClickAction().assertIsNotSelected().performClick()
        assertEquals(listOf(ListeningMode.TRANSPARENCY, ListeningMode.TRANSPARENCY), writes)
        compose.runOnIdle {
            state.value = initial.copy(domain = initial.domain.copy(mode = Received(ListeningMode.TRANSPARENCY, 1001, 7)))
        }
        transparency.assertIsSelected().assertHasClickAction()
    }

    @Test fun explicitFlatResetSendsBeforeRxAndPreservesKnownEnablement() {
        val initial = state.value
        val property = CommandProperty.EQ
        val flat = PropertyValue.Eq(false, 50, 50, 50)
        val actions = mutableListOf<AirPodsUiAction>()
        state.value = initial.copy(domain = initial.domain.copy(properties = emptyMap()))
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                Column(Modifier.width(360.dp)) { EqualizerPage(state.value, actions::add) }
            }
        }
        val reset = compose.onNodeWithTag("airpods-eq-reset")
        reset.assertIsEnabled().assertHasClickAction().performClick()
        assertEquals(listOf(AirPodsUiAction.Write(7, property, flat)), actions)
        val enabled = flat.copy(enabled = true)
        compose.runOnIdle {
            state.value = initial.copy(domain = initial.domain.copy(properties = emptyMap(),
                cachedValues = AirPodsPairValues(observed = mapOf(property to SavedProperty(enabled, 1)))))
        }
        reset.performClick()
        assertEquals(AirPodsUiAction.Write(7, property, enabled), actions.last())
        compose.runOnIdle {
            state.value = initial.copy(domain = initial.domain.copy(properties = emptyMap(), desired = mapOf(
                property to DesiredProperty(enabled, 1, 7, DesiredStatus.SENT, 1001))))
        }
        reset.performClick()
        compose.runOnIdle {
            state.value = initial.copy(domain = initial.domain.copy(properties = mapOf(property to Received(enabled, 1001, 7))))
        }
        reset.performClick()
        compose.waitForIdle()
        assertEquals(2, actions.size)
    }

    @Test fun modelAndOffRestrictionFilterSlotsAndConversationIsNeverAMode() {
        val s = state.value
        state.value = s.copy(domain = s.domain.copy(
            identityReceived = Received(AirPodsIdentity("A2084", "Apple Inc.", "fixture"), 1000, 7),
            properties = s.domain.properties + (CommandProperty.ALLOW_OFF_OPTION to
                Received(PropertyValue.Number(2), 1000, 7))))
        mount()
        compose.onNodeWithTag("page-1").assertDoesNotExist()
        compose.onNodeWithTag("page-4").assertDoesNotExist()
        compose.onNodeWithTag("page-3").assertHasClickAction()
        compose.onNodeWithTag("page-2").assertIsSelected()
        compose.onAllNodes(hasAnySibling(hasTestTag("page-2")) and hasClickAction()).assertCountEquals(1)
    }
}
