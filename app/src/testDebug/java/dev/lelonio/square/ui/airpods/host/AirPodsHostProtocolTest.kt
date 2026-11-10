// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import android.app.Application
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.storage.SuppressReason
import dev.lelonio.square.ui.airpods.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** PREPARED, NOT COMPILED OR EXECUTED. Host actions, real admitted controller, real IO,
 * pinned TX and independently supplied RX. Synthetic Bluetooth is NOT hardware validation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35],
    instrumentedPackages = ["dev.lelonio.square.airpods"], shadows = [HostProtocolSocketShadow::class])
class AirPodsHostProtocolTest {
    @Test fun sharedScalarMultibyteInversionEqAndUtf8NameReachTransportWithoutOptimisticRx() {
        HostProtocolFixture().use { f ->
            f.start()
            data class Case(val property: CommandProperty, val value: PropertyValue, val tx: String, val rx: String = tx)
            val cases = listOf(
                Case(CommandProperty.MIC_MODE, PropertyValue.Number(2), HostProtocolWire.MIC_LEFT),
                Case(CommandProperty.CHIME_VOLUME, PropertyValue.Number(37), HostProtocolWire.CHIME_37),
                Case(CommandProperty.CALL_MANAGEMENT_CONFIG, PropertyValue.Number(2), HostProtocolWire.CALL_FLIPPED),
                Case(CommandProperty.AUTO_ANC_STRENGTH, PropertyValue.Number(25), HostProtocolWire.ADAPTIVE_UI_25),
                Case(CommandProperty.EQ, PropertyValue.Eq(true, 0, 50, 100), HostProtocolWire.EQ_NEW),
                Case(CommandProperty.NAME, PropertyValue.Name("Caffè"), HostProtocolWire.RENAME_CAFFE,
                    HostProtocolWire.INFORMATION_CAFFE),
            )
            for (case in cases) {
                val old = f.observed(case.property)
                assertTrue(f.host.state.value.writeAllowed(case.property, case.value))
                val before = f.transport.writes.size
                f.write(case.property, case.value)
                f.await("raw TX ${case.property}") { f.transport.writes.size == before + 1 }
                assertArrayEquals(case.property.name, HostProtocolWire.bytes(case.tx), f.transport.writes.last().toByteArray())
                val request = f.owner.state.value.pending.getValue(case.property)
                assertEquals(case.value, request.requested)
                assertEquals(HostProtocolWire.bytes(case.tx).map { it.toInt() and 255 }, request.packet)
                assertEquals(f.owner.state.value.generation, request.generation)
                assertEquals("Send success must not become RX", old, f.observed(case.property))
                assertFalse(f.host.state.value.writeAllowed(case.property, case.value))
                AirPodsProperties.spec(case.property)?.let {
                    assertEquals(PropertyStatus.PENDING, f.host.state.value.domain.propertyView(case.property)?.status)
                }
                // A second host action for this property is rejected before another writer is scheduled.
                f.write(case.property, case.value)
                assertEquals(before + 1, f.transport.writes.size)
                assertEquals(request.sequence, f.owner.state.value.pending.getValue(case.property).sequence)
                f.receive(case.rx, case.property, case.value)
                assertEquals(CommandOutcome.OBSERVED_MATCH, f.host.state.value.domain.outcomes[case.property])
                assertTrue(f.host.state.value.writeAllowed(case.property, case.value))
                assertFalse(f.host.state.value.productGatePassed)
                assertNull(f.owner.store.snapshot().getOrThrow().pairs.getValue(HostProtocolFixture.PAIR).verification)
            }
            assertEquals(HostProtocolWire.BOOTSTRAP_PACKET_COUNT + cases.size, f.transport.writes.size) // only bootstrap plus these commands
        }
    }

    @Test fun malformedAndUnrelatedRxLeavePendingThenDifferentValueIsObservedOther() {
        HostProtocolFixture().use { f ->
            f.start()
            f.write(CommandProperty.CHIME_VOLUME, PropertyValue.Number(37))
            f.await("chime raw write") { f.transport.count(HostProtocolWire.CHIME_37) == 1 }
            f.transport.deliver(HostProtocolWire.CHIME_BAD)
            f.transport.deliver(HostProtocolWire.MIC_LEFT) // ordered read-loop fence after malformed chime
            f.await("unrelated decoded RX") { f.observed(CommandProperty.MIC_MODE) == PropertyValue.Number(2) }
            f.refresh()
            assertNull(f.observed(CommandProperty.CHIME_VOLUME))
            assertTrue(CommandProperty.CHIME_VOLUME in f.host.state.value.domain.pending)
            assertEquals(PropertyStatus.PENDING, f.host.state.value.domain.propertyView(CommandProperty.CHIME_VOLUME)?.status)
            f.receive(HostProtocolWire.CHIME_40, CommandProperty.CHIME_VOLUME, PropertyValue.Number(40))
            assertEquals(CommandOutcome.OBSERVED_OTHER, f.host.state.value.domain.outcomes[CommandProperty.CHIME_VOLUME])
            assertEquals(PropertyStatus.OBSERVED, f.host.state.value.domain.propertyView(CommandProperty.CHIME_VOLUME)?.status)
            assertEquals(1, f.transport.count(HostProtocolWire.CHIME_37))
        }
    }

    @Test fun renameTransmitEchoCannotConfirmNameWithoutInformationRx() {
        HostProtocolFixture().use { f ->
            f.start()
            f.write(CommandProperty.NAME, PropertyValue.Name("Caffè"))
            f.await("rename raw write") { f.transport.count(HostProtocolWire.RENAME_CAFFE) == 1 }
            f.transport.deliver(HostProtocolWire.RENAME_CAFFE) // opcode 0x1a is not INFORMATION
            f.transport.deliver(HostProtocolWire.MIC_LEFT)
            f.await("fence after rename echo") { f.observed(CommandProperty.MIC_MODE) == PropertyValue.Number(2) }
            f.refresh()
            assertEquals(PropertyValue.Name("Pods"), f.observed(CommandProperty.NAME))
            assertEquals("Pods", f.host.state.value.identity?.name)
            assertTrue(CommandProperty.NAME in f.host.state.value.domain.pending)
            f.receive(HostProtocolWire.INFORMATION_CAFFE, CommandProperty.NAME, PropertyValue.Name("Caffè"))
            assertEquals("Caffè", f.host.state.value.identity?.name)
            assertEquals(CommandOutcome.OBSERVED_MATCH, f.host.state.value.domain.outcomes[CommandProperty.NAME])
        }
    }

    @Test fun noRxExpiresPendingAndLateRxDoesNotRetroactivelyConfirmTheCommand() {
        HostProtocolFixture().use { f ->
            f.start()
            f.write(CommandProperty.CHIME_VOLUME, PropertyValue.Number(37))
            f.await("chime reaches raw transport") { f.transport.count(HostProtocolWire.CHIME_37) == 1 }
            f.await("real repository five-second pending timeout", timeoutMs = 6_500) {
                f.owner.state.value.outcomes[CommandProperty.CHIME_VOLUME] == CommandOutcome.NOT_CONFIRMED
            }
            f.refresh()
            assertTrue(f.owner.state.value.pending.isEmpty())
            assertNull(f.observed(CommandProperty.CHIME_VOLUME))
            assertEquals(PropertyStatus.NOT_CONFIRMED,
                f.host.state.value.domain.propertyView(CommandProperty.CHIME_VOLUME)?.status)
            f.receive(HostProtocolWire.CHIME_37, CommandProperty.CHIME_VOLUME, PropertyValue.Number(37))
            assertEquals(PropertyValue.Number(37), f.host.state.value.domain.propertyObserved(CommandProperty.CHIME_VOLUME)?.value)
            assertEquals(CommandOutcome.NOT_CONFIRMED, f.host.state.value.domain.outcomes[CommandProperty.CHIME_VOLUME])
            assertEquals(1, f.transport.count(HostProtocolWire.CHIME_37))
        }
    }

    @Test fun permissionLossAtProductionWriterQueueRejectsBeforeRawTransport() {
        queuedAuthorityLoss { it.revokeBluetoothPermission() }
    }

    @Test fun realPermitRevocationAtProductionWriterQueueRejectsBeforeRawTransport() {
        queuedAuthorityLoss { it.permit.revoke() }
    }

    private fun queuedAuthorityLoss(revoke: (HostProtocolFixture) -> Unit) {
        HostProtocolFixture().use { f ->
            f.start()
            val barrier = HostProtocolTransport.Barrier(HostProtocolWire.MIC_LEFT)
            f.transport.barrier = barrier
            try {
                f.write(CommandProperty.MIC_MODE, PropertyValue.Number(2))
                assertTrue(barrier.entered.await(2, TimeUnit.SECONDS))
                f.write(CommandProperty.CHIME_VOLUME, PropertyValue.Number(37))
                assertTrue(CommandProperty.CHIME_VOLUME in f.owner.state.value.pending)
                assertEquals(0, f.transport.count(HostProtocolWire.CHIME_37))
                revoke(f)
                f.write(CommandProperty.MODE, PropertyValue.Number(2))
                assertEquals(0, f.transport.count(HostProtocolWire.MODE_ANC))
                barrier.release.countDown()
                // Wait for the second writer's terminal observation, not a quiet-time assumption.
                f.await("queued writer fails authorization") { f.events.any {
                    it.step == QualificationStep.TX && it.phase == QualificationPhase.FAILED &&
                        it.property == CommandProperty.CHIME_VOLUME
                } }
                assertEquals("The already-entered first write cannot be undone", 1, f.transport.count(HostProtocolWire.MIC_LEFT))
                assertEquals(0, f.transport.count(HostProtocolWire.CHIME_37))
                assertEquals(0, f.transport.count(HostProtocolWire.MODE_ANC))
                f.refresh()
                assertFalse(f.host.state.value.controlsReady)
                assertNull(f.host.state.value.currentMode)
            } finally { barrier.release.countDown() }
        }
    }

    @Test fun staleGenerationAndRxReturnedDuringCloseCannotReviveHostOrQueuedCommand() {
        HostProtocolFixture().use { f ->
            f.start()
            val oldStamp = f.host.stamp
            val oldGeneration = f.owner.state.value.generation
            // A wrong generation with a CURRENT stamp must also be inert.
            f.write(CommandProperty.CHIME_VOLUME, PropertyValue.Number(37), generation = oldGeneration - 1)
            assertEquals(0, f.transport.count(HostProtocolWire.CHIME_37))
            val barrier = HostProtocolTransport.Barrier(HostProtocolWire.MIC_LEFT)
            f.transport.barrier = barrier
            try {
                f.write(CommandProperty.MIC_MODE, PropertyValue.Number(2))
                assertTrue(barrier.entered.await(2, TimeUnit.SECONDS))
                f.write(CommandProperty.CHIME_VOLUME, PropertyValue.Number(37))
                f.transport.lateOnClose = HostProtocolWire.CHIME_37
                val lateRead = HostProtocolTransport.Barrier(HostProtocolWire.CHIME_37)
                f.transport.lateReadBarrier = lateRead
                f.await("raw reader waiting before close") {
                    f.transport.readsStarted.get() > f.transport.readsReturned.get()
                }
                val beforeRead = f.transport.readsReturned.get()
                runBlocking { f.owner.stopControls(SuppressReason.USER_STOP) }
                assertTrue(lateRead.entered.await(2, TimeUnit.SECONDS))
                assertTrue(f.owner.state.value.generation > oldGeneration)
                lateRead.release.countDown()
                f.await("closed read returned") { f.transport.readsReturned.get() > beforeRead }
                f.await("generation retired") { f.owner.state.value.generation > oldGeneration }
                f.refresh()
                assertEquals(AacpStage.CLOSED, f.host.state.value.domain.stage)
                assertTrue(f.host.state.value.domain.pending.isEmpty())
                assertNull(f.observed(CommandProperty.CHIME_VOLUME))
                assertNull(f.host.state.value.currentMode)
                assertFalse(f.host.state.value.controlsReady)
                assertTrue(f.host.state.value.domain.outcomes[CommandProperty.CHIME_VOLUME] in
                    setOf(CommandOutcome.CLOSED, CommandOutcome.SEND_FAILED))
                f.write(CommandProperty.CHIME_VOLUME, PropertyValue.Number(37), oldStamp, oldGeneration)
                assertEquals(0, f.transport.count(HostProtocolWire.CHIME_37))
                assertTrue(f.host.state.value.diagnostics.contains(f.text(R.string.airpods_host_stale)))
            } finally { barrier.release.countDown() }
        }
    }

    @Test fun ordinaryHostHasNoFunctionalAuthorityAndDetachedOrClosedHostRejectsSavedClosure() {
        HostProtocolFixture().use { f ->
            f.start()
            val ordinary = AirPodsHost(f.context, f.owner)
            f.installHostBluetooth(ordinary)
            try {
                runBlocking {
                    ordinary.refresh()
                    assertFalse(ordinary.state.value.productGatePassed)
                    assertFalse(ordinary.state.value.controlsReady)
                    ordinary.dispatch(ordinary.stamp,
                        AirPodsUiAction.Write(f.owner.state.value.generation, CommandProperty.CHIME_VOLUME, PropertyValue.Number(37))) {
                        _, _ -> fail("No platform launch")
                    }
                }
                val saved = f.host.stamp
                f.ingressCurrent = false
                f.write(CommandProperty.CHIME_VOLUME, PropertyValue.Number(37), saved)
                f.ingressCurrent = true
                f.detachHostBluetooth(f.host)
                f.host.close()
                f.write(CommandProperty.CHIME_VOLUME, PropertyValue.Number(37), saved)
                assertEquals(0, f.transport.count(HostProtocolWire.CHIME_37))
                assertEquals(HostProtocolWire.BOOTSTRAP_PACKET_COUNT, f.transport.writes.size)
                assertTrue(f.owner.state.value.pending.isEmpty())
            } finally { f.detachHostBluetooth(ordinary); ordinary.close() }
        }
    }
}
