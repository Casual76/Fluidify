// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.storage.*
import org.junit.Assert.*
import org.junit.Test

class AirPodsHeaderPresentationTest {
    private fun cached(percent: Int = 61) = uiFixture().let {
        it.copy(domain = it.domain.copy(cachedValues = AirPodsPairValues(
            casePercent = SavedCasePercent(percent, 1234))))
    }

    @Test fun unavailableSampleUsesSavedPercentWithoutChargingState() {
        val s = cached().let { it.copy(domain = it.domain.copy(batteries = mapOf(
            BatteryCase to Received(BatteryValue(null, 1), 1000, 7)))) }
        assertEquals(CaseBatteryReading(61, false, 2), s.caseBatteryReading())
    }

    @Test fun currentSampleWinsAndMayShowActualCharging() {
        val s = cached().let { it.copy(domain = it.domain.copy(batteries = mapOf(
            BatteryCase to Received(BatteryValue(40, 1), 1000, 7)))) }
        assertEquals(CaseBatteryReading(40, true, 1), s.caseBatteryReading())
        assertEquals(CaseBatteryReading(61, false, 2), s.copy(nowMs = 301000).caseBatteryReading())
        assertEquals(CaseBatteryReading(61, false, 2), s.copy(profileConnected = false).caseBatteryReading())
        assertEquals(CaseBatteryReading(61, false, 2), s.copy(nowMs = 999).caseBatteryReading())
        assertEquals(CaseBatteryReading(61, false, 2), s.copy(domain = s.domain.copy(generation = 8)).caseBatteryReading())
    }

    @Test fun zeroIsSavedReadingAndMissingPairCacheIsUnknown() {
        assertEquals(CaseBatteryReading(0, false, 2), cached(0).caseBatteryReading())
        assertNull(uiFixture().caseBatteryReading())
        // Switching to the new pair projection must not consult any previous UI reading.
        assertNull(cached().copy(domain = AirPodsState(generation = 8)).caseBatteryReading())
    }

    @Test fun requestedNameIsImmediateAndLaterRxReconciles() {
        val original = uiFixture().domain.copy(properties = mapOf(
            CommandProperty.NAME to Received(PropertyValue.Name("Old"), 1000, 7)))
        val pending = original.copy(desired = mapOf(CommandProperty.NAME to DesiredProperty(
            PropertyValue.Name("New"), 1, 7, DesiredStatus.SENT, 1001)))
        val ui = uiFixture().copy(domain = pending, nowMs = 60000)
        assertEquals("New", ui.headerName)
        assertEquals("Old", (pending.propertyObserved(CommandProperty.NAME)?.value as PropertyValue.Name).value)
        val late = pending.copy(properties = mapOf(CommandProperty.NAME to
            Received(PropertyValue.Name("Device name"), 60000, 7)), desired = mapOf(
            CommandProperty.NAME to pending.desired.getValue(CommandProperty.NAME).copy(status = DesiredStatus.CONTRADICTED)))
        assertEquals("Device name", ui.copy(domain = late).headerName)
        assertEquals("New", ui.copy(domain = pending.copy(desired = mapOf(CommandProperty.NAME to
            pending.desired.getValue(CommandProperty.NAME).copy(status = DesiredStatus.UNCERTAIN)))).headerName)
    }

    @Test fun draftFollowsLateInformationUntilUserEditsAndAgainAfterSubmit() {
        val initial = AirPodsRenameDraft("").reconcile("Known")
        assertEquals("Known", initial.text)
        val edited = initial.edit("My name")
        assertEquals("My name", edited.reconcile("Late old name").text)
        assertEquals("My name", edited.submitted().reconcile("My name").text)
        assertEquals("Firmware name", edited.submitted().reconcile("Firmware name").text)
    }
}
