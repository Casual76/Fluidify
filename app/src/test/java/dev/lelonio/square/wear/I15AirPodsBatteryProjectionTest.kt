package dev.lelonio.square.wear

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.HeadSample
import dev.pampa.fluidify.wear.protocol.AIRPODS_BATTERY_LIFETIME_MS
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class I15AirPodsBatteryProjectionTest {
    private fun state(at: Long = 10_000, generation: Long = 1, percent: Int? = 42) = AirPodsState(
        generation = generation, stage = AacpStage.SUBSCRIBED,
        batteries = mapOf(2 to Received(BatteryValue(percent, 1), at, generation)),
    )
    private fun AirPodsBatteryProjection.read(s: AirPodsState = state(), elapsed: Long = 20_000,
        wall: Long = 100_000, account: String? = "a", active: Boolean = true) =
        snapshot(AirPodsBatteryObservation.from(s), active, account, wall, elapsed)

    @Test fun originalRxAgeSurvivesRepublishAndTrueZeroIsNotUnknown() {
        val p = AirPodsBatteryProjection { "source" }
        val first = p.read(state(percent = 0))!!
        assertEquals(90_000L, first.right!!.observedAtEpochMs)
        assertEquals(0, first.right!!.percent)
        assertNull(first.left)
        assertNull(first.case)
        val later = p.read(state(percent = 0), elapsed = 100_000, wall = 180_000)!!
        assertEquals(first, later)
        assertNull(p.read(state(percent = 0), elapsed = 10_000 + AIRPODS_BATTERY_LIFETIME_MS,
            wall = 90_000 + AIRPODS_BATTERY_LIFETIME_MS))
    }

    @Test fun accountSwapLogoutAndSameNameNewLoginNeedANewRx() {
        val p = AirPodsBatteryProjection { "source" }
        assertNotNull(p.read())
        p.accountChanged(null, 21_000)
        p.accountChanged("b", 22_000)
        assertNull(p.read(elapsed = 23_000, wall = 103_000, account = "b"))
        assertEquals(42, p.read(state(23_001), 24_000, 104_000, "b")!!.right!!.percent)
        p.accountChanged("b-new-login", 25_000)
        assertNull(p.read(state(23_001), 26_000, 106_000, "b-new-login"))
        assertNotNull(p.read(state(26_001), 27_000, 107_000, "b-new-login"))
    }

    @Test fun disconnectAndPairGenerationCannotBorrowPreviousComponents() {
        var ids = 0
        val p = AirPodsBatteryProjection { "source-${++ids}" }
        val first = p.read()!!
        assertNull(p.read(state().copy(stage = AacpStage.CLOSED), 21_000, 101_000, active = false))
        assertNull(p.read(elapsed = 22_000, wall = 102_000))
        val next = state(22_001, generation = 2).copy(batteries = mapOf(
            2 to Received(BatteryValue(7, 1), 22_001, 2),
            4 to Received(BatteryValue(99, 1), 22_001, 1),
        ))
        val shown = p.read(next, 23_000, 103_000)!!
        assertNotEquals(first.sourceSession, shown.sourceSession)
        assertEquals(7, shown.right!!.percent)
        assertNull(shown.left)
    }

    @Test fun expiredCaseUnknownPercentAndFutureRxAreNeverInvented() {
        val p = AirPodsBatteryProjection()
        val mixed = state(310_000).copy(batteries = mapOf(
            2 to Received(BatteryValue(70, 1), 310_000, 1),
            4 to Received(BatteryValue(null, 1), 310_000, 1),
            8 to Received(BatteryValue(99, 1), 10_000, 1),
        ))
        val shown = p.read(mixed, 310_001, 1_000_000)!!
        assertEquals(70, shown.right!!.percent)
        assertNull(shown.left)
        assertNull(shown.case)
        assertNull(AirPodsBatteryProjection().read(state(at = 20_001)))
        assertNull(AirPodsBatteryProjection().read(state(percent = 101)))
        assertNull(AirPodsBatteryProjection().read(state().copy(batteries = mapOf(2 to Received(BatteryValue(50, 0), 10_000, 1)))))
    }

    @Test fun clockJumpAndMonotonicResetRequireRxAfterTheBoundary() {
        val p = AirPodsBatteryProjection()
        assertNotNull(p.read())
        assertNull(p.read(elapsed = 21_000, wall = 40_000))
        assertNull(p.read(elapsed = 22_000, wall = 41_000))
        assertNotNull(p.read(state(22_001), 23_000, 42_000))
        assertNull(p.read(state(at = 1), 2, 43_000))
        assertNull(AirPodsBatteryProjection().read(AirPodsState())) // process/cache has no observed RX
    }

    @Test fun actualBatteryProjectionSuppressesAHighFrequencySensorStream() = runTest {
        val baseline = state()
        // The production map/distinct boundary receives genuine AirPodsState frames, including
        // 200 head updates, and only emits the initial battery plus a genuinely new battery RX.
        val frames = (0 until 200).map { i -> baseline.copy(head = Received(
            HeadSample(0, 0, 0, i, 0, 0), i.toLong(), 1)) } + state(10_001, percent = 41)
        val output = frames.asFlow().map { AirPodsBatteryObservation.from(it) }.distinctUntilChanged().toList()
        assertEquals(2, output.size)
        assertEquals(42, output.first().batteries[2]!!.value.percent)
        assertEquals(41, output.last().batteries[2]!!.value.percent)
    }
}
