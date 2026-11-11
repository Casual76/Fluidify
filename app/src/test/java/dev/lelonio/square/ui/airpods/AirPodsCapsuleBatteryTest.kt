package dev.lelonio.square.ui.airpods

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** PREPARED NOT EXECUTED: no hardware or runtime activation. */
class AirPodsCapsuleBatteryTest {
    private fun battery(vararg values: Pair<Int, Int?>): AirPodsUiState = uiFixture().let { s ->
        s.copy(domain = s.domain.copy(batteries = values.associate { (component, percent) ->
            component to Received(BatteryValue(percent, 2), 1000, 7)
        }))
    }
    @Test fun caseDoesNotBecomeEarbudAutonomy() {
        assertEquals(CapsuleBatteryReading(70), battery(4 to 80, 2 to 70, 8 to 5).capsuleBatteryReading())
    }
    @Test fun singleKnownEarbudKeepsSideEvenWithCase() {
        assertEquals(CapsuleBatteryReading(80, 4), battery(4 to 80, 2 to null, 8 to 5).capsuleBatteryReading())
        assertEquals(CapsuleBatteryReading(70, 2), battery(2 to 70).capsuleBatteryReading())
    }
    @Test fun caseOnlyIsExplicitAndUnknownIsNotZero() {
        assertEquals(CapsuleBatteryReading(5, 8), battery(8 to 5).capsuleBatteryReading())
        assertNull(battery(4 to null, 2 to null, 8 to null).capsuleBatteryReading())
    }
    @Test fun staleFutureDisconnectedAndOldGenerationNeverBecomeLiveCharge() {
        val s = battery(4 to 80, 2 to 70, 8 to 5)
        for (invalid in listOf(s.copy(nowMs=301000), s.copy(nowMs=999),
            s.copy(profileConnected=false), s.copy(domain=s.domain.copy(generation=8)))) {
            assertNull(invalid.capsuleBatteryReading())
        }
    }
    @Test fun staleCaseCannotReplaceFreshEarbudAndOldEarbudCannotLowerMinimum() {
        val s = battery(4 to 80, 2 to 1, 8 to 5)
        val samples = s.domain.batteries.toMutableMap()
        samples[2] = Received(BatteryValue(1, 2),1000,6)
        samples[8] = Received(BatteryValue(5, 2),0,7)
        assertEquals(CapsuleBatteryReading(80,4), s.copy(nowMs=300001,domain=s.domain.copy(batteries=samples)).capsuleBatteryReading())
    }
}
