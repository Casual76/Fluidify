package dev.pampa.fluidify.wear.link

import dev.pampa.fluidify.wear.protocol.*
import dev.pampa.fluidify.wear.ui.player.airPodsBatteryModel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic clock/transport receipts, no device, service, login or sensor. Requires the ROOT patch. */
class I15AirPodsBatteryFreshnessTest {
    private fun account(session: String = "a", time: Long = 100_000) =
        AccountState(true, changedAtEpochMs = time, localDataSession = session)
    private fun snapshot(seq: Long = 1, sent: Long = 101_000, session: String = "a", source: String = "one",
        observed: Long = 90_000, caseObserved: Long? = null) = PlaybackSnapshot(
        seq, sent, source = PlaybackSource.PHONE,
        airPodsBattery = AirPodsBatterySnapshot(session, source,
            right = AirPodsBatteryLevel(42, observed), case = caseObserved?.let { AirPodsBatteryLevel(80, it) }),
    )
    private fun receiver() = AirPodsBatteryReceiver().apply {
        beginContact("phone", 3_700_000, 10_000)
        observeClock(100_000, 3_700_000, 10_000) // watch is one hour ahead
        confirmContact("phone", 100_000, 3_700_000, 10_000)
        observeAccount(account())
    }
    private fun frame(s: PlaybackSnapshot, r: AirPodsBatteryReceipt?) =
        ReceivedSnapshot(s, 3_701_000, 3_600_000, r)

    @Test fun clockOffsetAndTransitConsumeTheOriginalLifetimePerComponent() {
        val receiver = receiver()
        val s = snapshot(sent = 411_000, observed = 400_000, caseObserved = 1)
        val receipt = receiver.accept(s, 4_041_000, 351_000)!! // 30 seconds in Data Layer
        assertNull(receipt.caseExpiresAtElapsedMs)
        assertNull(receipt.leftExpiresAtElapsedMs)
        val shown = airPodsBatteryModel(frame(s, receipt), LinkStatus.CONNECTED, "a", 4_041_000, 351_000)
        assertEquals(42, shown.right)
        assertNull(shown.left)
        assertNull(shown.case)
        val deadline = receipt.rightExpiresAtElapsedMs!!
        assertFalse(airPodsBatteryModel(frame(s, receipt), LinkStatus.CONNECTED, "a",
            4_041_000 + deadline - 351_000, deadline).available)
    }

    @Test fun newPublishTimeCannotRenewAnOldMeasurement() {
        val receiver = receiver()
        val first = receiver.accept(snapshot(), 3_701_000, 11_000)!!
        val next = receiver.accept(snapshot(seq = 2, sent = 180_000), 3_780_000, 90_000)!!
        assertEquals(first.rightExpiresAtElapsedMs, next.rightExpiresAtElapsedMs)
        assertNull(receiver.accept(snapshot(seq = 3, sent = 390_000), 3_990_000, 300_000))
    }

    @Test fun dataCacheNeedsALiveClockAndAccountAndCannotPrecedeThatHandshake() {
        val receiver = AirPodsBatteryReceiver()
        assertNull(receiver.accept(snapshot(), 3_701_000, 11_000))
        receiver.observeAccount(account())
        assertNull(receiver.accept(snapshot(), 3_701_000, 11_000))
        receiver.beginContact("phone", 3_702_000, 12_000)
        receiver.observeClock(102_000, 3_702_000, 12_000)
        receiver.confirmContact("phone", 102_000, 3_702_000, 12_000)
        assertNull(receiver.accept(snapshot(), 3_703_000, 13_000)) // cached before hello
        assertNull(receiver.accept(snapshot(sent = 103_000), 3_703_000, 13_000)) // cached account before hello
        receiver.observeAccount(account(time = 102_000))
        assertNotNull(receiver.accept(snapshot(sent = 103_000), 3_703_000, 13_000))
    }

    @Test fun revokedSessionAndSameNameNewLoginInvalidateOptimisticCopies() {
        val receiver = receiver()
        val s = snapshot()
        val r = receiver.accept(s, 3_701_000, 11_000)!!
        val optimistic = frame(s, r).copy(snapshot = s.copy(isPlaying = true, playWhenReady = true))
        assertTrue(airPodsBatteryModel(optimistic, LinkStatus.CONNECTED, "a", 3_701_000, 11_000).available)
        receiver.observeAccount(account("a-new-login", 101_000))
        assertFalse(airPodsBatteryModel(optimistic, LinkStatus.CONNECTED, "a", 3_701_000, 11_000).available)
        assertNull(receiver.accept(s.copy(seq = 2), 3_702_000, 12_000))
        assertNotNull(receiver.accept(snapshot(3, 102_000, "a-new-login"), 3_702_000, 12_000))
        receiver.observeAccount(AccountState(false))
        receiver.observeAccount(account("a", 103_000)) // replay cannot restore a retired correlation
        assertNull(receiver.accept(snapshot(4, 104_000), 3_704_000, 14_000))
    }

    @Test fun disconnectUnknownSourceAndNewConnectionRevokeOldFrameAuthority() {
        val receiver = receiver()
        val first = receiver.accept(snapshot(), 3_701_000, 11_000)!!
        assertNull(receiver.accept(snapshot(2).copy(airPodsBattery = null), 3_702_000, 12_000))
        assertFalse(first.isCurrent(3_702_000, 12_000))
        val next = receiver.accept(snapshot(3, 103_000, source = "two"), 3_703_000, 13_000)!!
        assertNull(receiver.accept(snapshot(4, 104_000).copy(source = PlaybackSource.CONNECT_REMOTE), 3_704_000, 14_000))
        assertFalse(next.isCurrent(3_704_000, 14_000))
    }

    @Test fun watchPlaybackUnreachableLinkAndWrongAccountNeverProjectPhoneBattery() {
        val s = snapshot()
        val r = receiver().accept(s, 3_701_000, 11_000)!!
        val held = frame(s, r)
        assertFalse(airPodsBatteryModel(held, LinkStatus.UNREACHABLE, "a", 3_701_000, 11_000).available)
        assertFalse(airPodsBatteryModel(held, LinkStatus.CONNECTED, "b", 3_701_000, 11_000).available)
        assertFalse(airPodsBatteryModel(held.copy(snapshot = s.copy(source = PlaybackSource.WATCH)),
            LinkStatus.CONNECTED, "a", 3_701_000, 11_000).available)
    }

    @Test fun clockStepsAndElapsedResetCannotRejuvenateAnOldReceipt() {
        val receiver = receiver()
        val r = receiver.accept(snapshot(), 3_701_000, 11_000)!!
        assertFalse(r.isCurrent(3_600_000, 12_000))
        assertFalse(r.isCurrent(3_701_000, 1))
        receiver.observeClock(102_000, 3_600_000, 12_000)
        assertFalse(r.isCurrent(3_702_000, 12_000))
        assertNull(receiver.accept(snapshot(2, 103_000, observed = 104_000), 3_601_000, 13_000))
    }

    @Test fun delayedAsymmetricHelloConsumesItsEntireRoundTripInsteadOfRejuvenatingRx() {
        val normal = receiver().accept(snapshot(), 3_701_000, 11_000)!!
        val delayed = AirPodsBatteryReceiver().apply {
            beginContact("phone", 3_700_000, 10_000)
            observeClock(100_000, 3_710_000, 20_000)
            confirmContact("phone", 100_000, 3_710_000, 20_000)
            observeAccount(account())
        }.accept(snapshot(), 3_710_000, 20_000)!!
        assertEquals(normal.rightExpiresAtElapsedMs, delayed.rightExpiresAtElapsedMs)
    }

    @Test fun unsolicitedWrongNodeOrLateHelloCannotAuthorizeCachedTelemetry() {
        val receiver = AirPodsBatteryReceiver()
        receiver.observeClock(100_000, 3_700_000, 10_000)
        receiver.observeAccount(account())
        assertNull(receiver.accept(snapshot(), 3_701_000, 11_000))
        receiver.beginContact("phone", 3_700_000, 10_000)
        receiver.confirmContact("different-phone", 100_000, 3_700_000, 10_000)
        assertNull(receiver.accept(snapshot(), 3_701_000, 11_000))
        receiver.confirmContact("phone", 100_000, 3_711_000, 21_000)
        assertNull(receiver.accept(snapshot(), 3_711_000, 21_000))
    }

    @Test fun malformedOrExpiredComponentDoesNotContaminateValidOtherSide() {
        val s = snapshot().let { it.copy(airPodsBattery = it.airPodsBattery!!.copy(
            left = AirPodsBatteryLevel(-1, 100_000), case = AirPodsBatteryLevel(100, 500_000))) }
        val r = receiver().accept(s, 3_701_000, 11_000)!!
        val model = airPodsBatteryModel(frame(s, r), LinkStatus.CONNECTED, "a", 3_701_000, 11_000)
        assertEquals(42, model.right)
        assertNull(model.left)
        assertNull(model.case)
    }

    @Test fun realWatchStateStripsPersistedBatteryAndReconcilesAccountAfterSnapshot() {
        val file = File.createTempFile("i15-watch-", ".json").apply { delete() }
        try {
            val state = WatchState(file)
            state.beginAirPodsBatteryContact("phone", 3_700_000, 10_000)
            state.observeClock(100_000, 3_700_000, 10_000)
            state.confirmAirPodsBatteryContact(100_000, "phone", 3_700_000, 10_000)
            state.accept(snapshot(), 3_701_000, 11_000) // actual delivery order: state before account
            assertNull(state.current.value!!.airPodsBatteryReceipt)
            state.observeAirPodsBatteryAccount(account(time = 101_000), 3_701_001, 11_001)
            assertNotNull(state.current.value!!.airPodsBatteryReceipt)
            val cached = WearCodec.decode(ReceivedSnapshot.serializer(), file.readBytes())
            assertNull(cached.snapshot.airPodsBattery)
            assertNull(cached.airPodsBatteryReceipt)
            val restarted = WatchState(file)
            assertNull(restarted.current.value!!.snapshot.airPodsBattery)
            assertNull(restarted.current.value!!.airPodsBatteryReceipt)
            assertTrue(state.current.value!!.snapshot.seq == restarted.current.value!!.snapshot.seq)
        } finally { file.delete() }
    }

    @Test fun legacyCacheWithBatteryFromAnOlderWriterIsAlsoStrippedOnLoad() {
        val file = File.createTempFile("i15-legacy-watch-", ".json")
        try {
            file.writeBytes(WearCodec.encode(ReceivedSnapshot.serializer(), frame(snapshot(), null)))
            val state = WatchState(file)
            assertNull(state.current.value!!.snapshot.airPodsBattery)
            assertNull(state.current.value!!.airPodsBatteryReceipt)
        } finally { file.delete() }
    }
}
