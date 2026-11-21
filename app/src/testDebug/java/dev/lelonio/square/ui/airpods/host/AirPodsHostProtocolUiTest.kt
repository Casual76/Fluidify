// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.ui.airpods.*
import dev.lelonio.square.ui.theme.SquareTheme
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real production settings pages -> host -> admitted controller -> writer/decoder.
 * Only Bluetooth facts, persistence and supplied SDUs are synthetic. No hardware claim. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w600dp-h960dp-mdpi",
    instrumentedPackages = ["dev.lelonio.square.airpods"], shadows = [HostProtocolSocketShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirPodsHostProtocolUiTest {
    @get:Rule val compose = createComposeRule()

    private fun mount(f: HostProtocolFixture, property: CommandProperty? = null,
        page: AirPodsSettingsPage = AirPodsSettingsPage.Main) {
        compose.setContent {
            val state by f.host.state.collectAsState()
            val scope = rememberCoroutineScope()
            val captured = f.host.stamp
            val dispatch: (AirPodsUiAction) -> Unit = { action -> scope.launch {
                f.host.dispatch(captured, action) { _, _ -> fail("No platform UI for protocol writes") }
            } }
            val backdrop = rememberGlassBackdrop()
            val modal = rememberFluidGlassModalHostState()
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                    LocalFluidGlassModalHostState provides modal,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.requiredSize(360.dp, 640.dp)) {
                        AirPodsSettingsContent(state, dispatch, Modifier.fillMaxSize(),
                            page = property?.let(::airPodsSettingsPageOf) ?: page)
                        FluidGlassModalHost(modal, backdrop = backdrop)
                    }
                }
            }
        }
    }

    private fun slider(tag: String) = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and
        (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))))
    private fun nameField() = compose.onNode(hasSetTextAction() and
        (hasTestTag("airpods-rename-name") or hasAnyAncestor(hasTestTag("airpods-rename-name"))))
    private fun progress(tag: String, value: Int) = slider(tag).performScrollTo()
        .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value.toFloat())) }
    private fun range(tag: String, value: Int) = slider(tag).performScrollTo()
        .assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(value.toFloat(), 0f..100f))

    @Test fun scalarClickShowsEffectiveImmediatelyAndDecodedContraryRxWins() {
        HostProtocolFixture().use { f ->
            f.start(); mount(f, CommandProperty.MIC_MODE)
            compose.onNodeWithTag("airpods-choice-MIC_MODE-0").assertIsSelected()
            compose.onNodeWithTag("airpods-choice-MIC_MODE-2").performClick()
            f.sent(CommandProperty.MIC_MODE)
            assertEquals(listOf(HostProtocolWire.bytes(HostProtocolWire.MIC_LEFT).toList()), f.transport.commandWrites)
            compose.onNodeWithTag("airpods-choice-MIC_MODE-2").assertIsSelected().assertIsEnabled()
            assertEquals(PropertyValue.Number(0), f.observed(CommandProperty.MIC_MODE))
            f.receiveScalar(HostProtocolWire.MIC_AUTO, CommandProperty.MIC_MODE, PropertyValue.Number(0))
            compose.onNodeWithTag("airpods-choice-MIC_MODE-0").assertIsSelected()
            assertEquals(CommandOutcome.OBSERVED_OTHER, f.host.state.value.domain.outcomes[CommandProperty.MIC_MODE])
            assertEquals(1, f.transport.commandWrites.size)
        }
    }
    @Test fun chimeSliderSendsPinnedMultibytePayloadAndContraryRxWins() {
        HostProtocolFixture().use { f ->
            f.start(); mount(f, CommandProperty.CHIME_VOLUME)
            range("airpods-chime-volume", 75)
            progress("airpods-chime-volume", 37); f.sent(CommandProperty.CHIME_VOLUME)
            assertEquals(listOf(HostProtocolWire.bytes(HostProtocolWire.CHIME_37).toList()), f.transport.commandWrites)
            range("airpods-chime-volume", 37)
            assertNull(f.observed(CommandProperty.CHIME_VOLUME))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6))
            f.refresh()
            range("airpods-chime-volume", 37)
            assertNull(f.host.state.value.domain.outcomes[CommandProperty.CHIME_VOLUME])
            assertEquals(1, f.transport.commandWrites.size)
            f.receive(HostProtocolWire.CHIME_40, CommandProperty.CHIME_VOLUME, PropertyValue.Number(40))
            range("airpods-chime-volume", 40)
            assertEquals(CommandOutcome.OBSERVED_OTHER, f.host.state.value.domain.outcomes[CommandProperty.CHIME_VOLUME])
            assertEquals(1, f.transport.commandWrites.size)
        }
    }
    @Test fun renameIsPrefilledAndUtf8IntentShowsBeforeInformation() {
        HostProtocolFixture().use { f ->
            f.start(); mount(f, page = AirPodsSettingsPage.Rename)
            nameField().assertTextContains("Pods")
                .performTextReplacement("Caff\u00e8")
            compose.onNode(hasText(f.text(R.string.airpods_feature_setting_device_rename)) and hasClickAction())
                .performScrollTo().performClick()
            f.sent(CommandProperty.NAME)
            assertEquals(listOf(HostProtocolWire.bytes(HostProtocolWire.RENAME_CAFFE).toList()), f.transport.commandWrites)
            assertEquals("Pods", f.host.state.value.identity?.name)
            assertEquals(PropertyValue.Name("Caff\u00e8"), f.host.state.value.domain.propertyEffective(CommandProperty.NAME)?.value)
            nameField().assertTextContains("Caff\u00e8")
            f.receive(HostProtocolWire.INFORMATION_CAFFE, CommandProperty.NAME, PropertyValue.Name("Caff\u00e8"))
            assertEquals("Caff\u00e8", f.host.state.value.identity?.name)
            assertEquals(CommandOutcome.OBSERVED_MATCH, f.host.state.value.domain.outcomes[CommandProperty.NAME])
            assertEquals(1, f.transport.commandWrites.size)
        }
    }
    @Test fun eqSlidersSendWholeEffectivePayloadAndDifferentDecodedEqWinsWithoutEcho() {
        HostProtocolFixture().use { f ->
            f.start()
            val previous = PropertyValue.Eq(false, 50, 50, 50)
            f.receive(HostProtocolWire.EQ_OLD, CommandProperty.EQ, previous)
            mount(f, page = AirPodsSettingsPage.Equalizer)
            compose.onNodeWithTag("airpods-switch-EQ").performScrollTo().performTouchInput { click() }
            f.sent(CommandProperty.EQ)
            assertArrayEquals(HostProtocolWire.bytes("04 00 04 00 63 00 05 00 01 02 32 32 32"), f.transport.commandWrites.last().toByteArray())
            progress("airpods-eq-low", 0)
            f.await("second EQ payload") { f.transport.commandWrites.size == 2 }
            f.sent(CommandProperty.EQ)
            assertArrayEquals(HostProtocolWire.bytes("04 00 04 00 63 00 05 00 01 02 00 32 32"), f.transport.commandWrites.last().toByteArray())
            progress("airpods-eq-high", 100)
            f.await("third EQ payload") { f.transport.commandWrites.size == 3 }
            f.sent(CommandProperty.EQ)
            assertArrayEquals(HostProtocolWire.bytes(HostProtocolWire.EQ_NEW), f.transport.commandWrites.last().toByteArray())
            assertEquals(3, f.transport.commandWrites.size)
            assertEquals(previous, f.observed(CommandProperty.EQ))
            range("airpods-eq-low", 0); range("airpods-eq-mid", 50); range("airpods-eq-high", 100)
            val other = PropertyValue.Eq(false, 10, 20, 30)
            f.receive(HostProtocolWire.EQ_OTHER, CommandProperty.EQ, other)
            assertEquals(CommandOutcome.OBSERVED_OTHER, f.host.state.value.domain.outcomes[CommandProperty.EQ])
            // Bands are not offered while the equalizer is off; turning it on reveals the received curve.
            compose.onNodeWithTag("airpods-switch-EQ").performScrollTo().performClick()
            f.await("fourth EQ payload") { f.transport.commandWrites.size == 4 }
            assertArrayEquals(HostProtocolWire.bytes("04 00 04 00 63 00 05 00 01 02 0a 14 1e"), f.transport.commandWrites.last().toByteArray())
            f.sent(CommandProperty.EQ)
            range("airpods-eq-low", 10); range("airpods-eq-mid", 20); range("airpods-eq-high", 30)
            compose.onNodeWithTag("airpods-switch-EQ").performScrollTo().assertIsOn()
            assertEquals(4, f.transport.commandWrites.size)
        }
    }
    @Test fun realWriteFailureWithdrawsIntentAndClosesControlsWithoutEcho() {
        HostProtocolFixture().use { f ->
            f.start()
            f.receive(HostProtocolWire.CHIME_40, CommandProperty.CHIME_VOLUME, PropertyValue.Number(40))
            mount(f, CommandProperty.CHIME_VOLUME)
            f.transport.failWriteHex = HostProtocolWire.CHIME_37
            progress("airpods-chime-volume", 37)
            f.await("raw failure withdraws intent") {
                f.owner.state.value.outcomes[CommandProperty.CHIME_VOLUME] == CommandOutcome.SEND_FAILED
            }
            f.refresh()
            assertEquals(1, f.transport.failedWriteAttempts.get())
            assertEquals(PropertyValue.Number(40), f.host.state.value.domain.propertyEffective(CommandProperty.CHIME_VOLUME)?.value)
            assertFalse(f.host.state.value.controlsReady)
            assertEquals(dev.lelonio.square.airpods.protocol.DesiredStatus.FAILED,
                f.host.state.value.domain.desired[CommandProperty.CHIME_VOLUME]?.status)
            slider("airpods-chime-volume").assertDoesNotExist()
            assertTrue(f.transport.commandWrites.isEmpty())
        }
    }
    @Test fun rapidChimeDragFlushesOneFinalPayloadAndDoesNotEchoOnRx() {
        HostProtocolFixture().use { f ->
            f.start(); mount(f, CommandProperty.CHIME_VOLUME)
            val slider = slider("airpods-chime-volume").performScrollTo()
            compose.mainClock.autoAdvance = false
            slider.performTouchInput {
                down(center)
                moveTo(centerRight, delayMillis = 10)
                moveTo(centerLeft, delayMillis = 10)
                moveTo(centerRight, delayMillis = 10)
                up()
            }
            compose.mainClock.advanceTimeBy(32)
            compose.mainClock.autoAdvance = true
            f.sent(CommandProperty.CHIME_VOLUME)
            assertEquals(1, f.transport.commandWrites.size)
            assertArrayEquals(HostProtocolWire.bytes("04 00 04 00 09 00 1f 64 50 00 00"), f.transport.commandWrites.single().toByteArray())
            range("airpods-chime-volume", 100)
            f.receive(HostProtocolWire.CHIME_40, CommandProperty.CHIME_VOLUME, PropertyValue.Number(40))
            range("airpods-chime-volume", 40)
            assertEquals(1, f.transport.commandWrites.size)
        }
    }

}
