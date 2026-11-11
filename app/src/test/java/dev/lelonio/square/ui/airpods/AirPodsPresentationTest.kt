// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import dev.lelonio.square.R
import dev.lelonio.square.airpods.AacpStage
import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.protocol.AirPodsProperties
import dev.lelonio.square.airpods.protocol.PropertySpec
import org.junit.Assert.*
import org.junit.Test

/** PREPARED, NOT EXECUTED by the writer. Pure presentation rules: no Compose, radio or runtime. */
class AirPodsPresentationTest {
    private val registry = AirPodsProperties.controls.filterNot { it.internalDependency }

    @Test fun everyPublicRegistryControlIsFiledOnExactlyOnePageInRegistryOrder() {
        val filed = AirPodsSettingsPage.entries.flatMap { airPodsPageSpecs(it) }
        assertEquals("No control dropped or repeated", registry.size, filed.size)
        assertEquals(registry.toSet(), filed.toSet())
        for (page in AirPodsSettingsPage.entries) {
            assertEquals(registry.filter { airPodsSettingsPageOf(it.property) == page }, airPodsPageSpecs(page))
        }
        assertTrue(filed.none { it.internalDependency })
    }

    @Test fun everyPropertyOfTheProtocolHasAPage() {
        // The mapping has no `else`: this only says so in a way a reader can run.
        for (property in CommandProperty.entries) assertNotNull(property.name, airPodsSettingsPageOf(property))
        assertEquals(AirPodsSettingsPage.Rename, airPodsSettingsPageOf(CommandProperty.NAME))
        assertEquals(AirPodsSettingsPage.Equalizer, airPodsSettingsPageOf(CommandProperty.EQ))
        assertEquals(AirPodsSettingsPage.Main, airPodsSettingsPageOf(CommandProperty.MODE))
    }

    @Test fun aControlAddedToTheRegistryIsOnItsPropertysPage() {
        val future = PropertySpec(CommandProperty.MIC_MODE, "aacp.mic_mode", 0x7f, "a-group-added-later", 0..1)
        val page = airPodsSettingsPageOf(future.property)
        assertTrue(future in airPodsPageSpecs(page, AirPodsProperties.controls + future))
        assertEquals(registry.size + 1,
            AirPodsSettingsPage.entries.sumOf { airPodsPageSpecs(it, AirPodsProperties.controls + future).size })
    }

    @Test fun anInternalDependencyNeverBecomesARow() {
        val hidden = AirPodsProperties.controls.filter { it.internalDependency }
        assertTrue("Fixture: the registry still has an internal dependency", hidden.isNotEmpty())
        val filed = AirPodsSettingsPage.entries.flatMap { airPodsPageSpecs(it) }
        assertTrue(hidden.none { it in filed })
    }

    @Test fun theMainPagesOrderNamesOnlyMainPageSwitchesAndNoneTwice() {
        val ordered = airPodsMainGroups.flatten()
        assertEquals(ordered, ordered.distinct())
        val main = airPodsPageSpecs(AirPodsSettingsPage.Main).map { it.property }
        assertTrue(ordered.all { it in main })
        // The mode is the selector; everything else on the main page is in the order.
        assertEquals((main - CommandProperty.MODE).toSet(), ordered.toSet())
    }

    @Test fun theSelectorReleasesOnTheNearestSlotInsideThePill() {
        assertEquals(0, noiseControlSlot(-40f, 100f, 4))
        assertEquals(0, noiseControlSlot(49f, 100f, 4))
        assertEquals(1, noiseControlSlot(51f, 100f, 4))
        assertEquals(3, noiseControlSlot(900f, 100f, 4))
        assertEquals(0, noiseControlSlot(10f, 0f, 4))
        assertEquals(listOf(1, 3, 4, 2), noiseControlOrder.map { it.wire })
    }

    @Test fun hostTogglesAreSplitWithoutLosingOrRepeatingOne() {
        assertEquals(LocalToggle.entries.size, gestureToggles.size + automationToggles.size)
        assertEquals(LocalToggle.entries.toSet(), (gestureToggles + automationToggles).toSet())
        assertTrue(gestureToggles.none { it in automationToggles })
        assertEquals(LocalToggle.entries.filterNot { it in gestureToggles }, automationToggles)
        LocalToggle.entries.forEach { assertTrue("$it has a label", it in toggleLabels) }
    }

    @Test fun connectedIsSaidOnlyForAnAuthorizedSubscribedSession() {
        val ready = uiFixture()
        assertEquals(R.string.airpods_feature_connected, ready.connectionSummary())
        assertEquals(StatusKind.Confirmed, ready.connectionKind())
        // Each of these is a profile that is up with something missing: none may read "Connected".
        val short = listOf(
            ready.copy(productGatePassed = false),
            ready.copy(conformanceMatches = false),
            ready.copy(enabled = false),
            ready.copy(domain = ready.domain.copy(stage = AacpStage.CLOSED)),
        )
        for (state in short) {
            assertEquals(R.string.airpods_feature_controls_unavailable, state.connectionSummary())
            assertEquals(StatusKind.Neutral, state.connectionKind())
        }
        for (stage in AacpStage.entries.filter { it != AacpStage.SUBSCRIBED }) {
            val summary = ready.copy(domain = ready.domain.copy(stage = stage)).connectionSummary()
            assertNotEquals("$stage", R.string.airpods_feature_connected, summary)
        }
    }

    @Test fun whatMakesTheRestUnknowableIsSaidFirst() {
        val ready = uiFixture()
        val nothing = ready.copy(bluetoothGranted = false, suppressed = true, profileConnected = false)
        assertEquals(R.string.airpods_reason_bluetooth_permission, nothing.connectionSummary())
        assertEquals(R.string.airpods_feature_stopped,
            ready.copy(suppressed = true, profileConnected = false).connectionSummary())
        assertEquals(R.string.airpods_feature_disconnected, ready.copy(profileConnected = false).connectionSummary())
        assertEquals(StatusKind.Neutral, ready.copy(profileConnected = false).connectionKind())
    }

    @Test fun aChannelErrorAndAnOpeningChannelAreNotMistakenForEachOther() {
        val ready = uiFixture()
        val error = ready.copy(domain = ready.domain.copy(stage = AacpStage.ERROR))
        assertEquals(R.string.airpods_ui_aacp_error, error.connectionSummary())
        assertEquals(StatusKind.Problem, error.connectionKind())
        for (stage in listOf(AacpStage.CONSTRUCTING, AacpStage.CONSTRUCTED, AacpStage.CONNECTING,
            AacpStage.CONNECTED, AacpStage.HANDSHAKE_SENT)) {
            val opening = ready.copy(domain = ready.domain.copy(stage = stage))
            assertEquals("$stage", R.string.airpods_feature_connecting, opening.connectionSummary())
            assertEquals("$stage", StatusKind.Waiting, opening.connectionKind())
        }
    }

    @Test fun aModeMarkFollowsTheSameFallbackAsItsLabel() {
        // Off and noise cancellation share upstream's drawing; an unexpected value is Adaptive, like its label.
        assertEquals(modeIconRes(1), modeIconRes(2))
        assertEquals(R.drawable.airpods_mode_transparency, modeIconRes(3))
        assertEquals(R.drawable.airpods_mode_adaptive, modeIconRes(4))
        assertEquals(modeIconRes(4), modeIconRes(9))
    }
}
