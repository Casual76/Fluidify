// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import android.app.Application
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.ui.airpods.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PREPARED ONLY: no compilation/execution in this worktree. Uses the unchanged host fixture's
 * real service, C4, lease, functional permit, controller, repository, writer and decoder.
 * Synthetic RX establishes received software state, not firmware support or causal ACKs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35],
    instrumentedPackages = ["dev.lelonio.square.airpods"], shadows = [HostProtocolSocketShadow::class])
class AirPodsScalarHostTest {
    @Test fun explicitMatrixCoversExactlyTheTwentyPublicScalarFamilies() {
        assertEquals(20, ScalarHostCases.all.size)
        assertEquals(20, ScalarHostCases.all.map { it.property }.toSet().size)
        // HEARING_AID is a paired write, covered by the repository tests, not a scalar family.
        assertEquals(AirPodsProperties.controls.filterNot { it.internalDependency || it.property == CommandProperty.HEARING_AID }.map { it.catalogId }.toSet(),
            ScalarHostCases.all.map { it.catalogId }.toSet())
        ScalarHostCases.all.forEach { c ->
            assertEquals(c.catalogId, AirPodsProperties.spec(c.property)?.catalogId)
            assertNotEquals(c.catalogId, c.requested.value, c.different.value)
        }
    }

    @Test fun allFamiliesKeepRxSeparateFromPendingAndDecodeDifferentAndMatchingValues() {
        HostProtocolFixture().use { f ->
            f.start()
            for (c in ScalarHostCases.all) {
                f.receiveScalar(c.initial.hex, c.property, c.initial.number)
                val previous = f.observed(c.property)
                val before = f.transport.commandWrites.size
                f.write(c.property, c.requested.number)
                f.await("${c.catalogId} reaches raw transport") { f.transport.commandWrites.size == before + 1 }
                f.sent(c.property)
                assertArrayEquals(c.catalogId, HostProtocolWire.bytes(c.requested.hex), f.transport.commandWrites.last().toByteArray())
                f.refresh()
                val request = f.owner.state.value.desired.getValue(c.property)
                assertEquals(c.catalogId, c.requested.number, request.requested)
                assertEquals(c.catalogId, f.owner.state.value.generation, request.generation)
                assertEquals(c.catalogId, previous, f.observed(c.property))
                assertEquals(c.catalogId, PropertyStatus.OBSERVED, f.host.state.value.domain.propertyView(c.property)?.status)
                assertEquals(c.catalogId, c.requested.number, f.host.state.value.domain.propertyEffective(c.property)?.value)
                assertTrue(c.catalogId, f.host.state.value.writeAllowed(c.property, c.requested.number))
                f.write(c.property, c.requested.number)
                f.await("${c.catalogId} identical intent re-sends") { f.transport.commandWrites.size == before + 2 }
                f.sent(c.property)
                val resent = f.owner.state.value.desired.getValue(c.property)
                assertTrue(c.catalogId, resent.sequence > request.sequence)
                // trailing padding is tolerated now, like upstream).
                // A truncated frame is rejected through the REAL read loop (nonzero
                // trailing padding is tolerated now, like upstream). The following
                val activityEvents = f.events.count { it.step == QualificationStep.RX && it.flags == 5 }
                f.transport.deliver(c.requested.hex.take(16))
                f.transport.deliver(ScalarHostCases.CONVERSATION_ACTIVITY_START)
                f.await("${c.catalogId} malformed + unrelated RX fence") {
                    f.events.count { it.step == QualificationStep.RX && it.flags == 5 } > activityEvents &&
                        f.owner.state.value.conversationActivity?.value == 1
                }
                assertEquals(c.catalogId, previous, f.observed(c.property))
                val afterFence = f.owner.state.value
                assertEquals(c.catalogId, DesiredStatus.SENT, afterFence.desired[c.property]?.status)
                assertEquals(c.catalogId, resent.sequence, afterFence.desired.getValue(c.property).sequence)
                f.receiveScalar(c.different.hex, c.property, c.different.number)
                assertEquals(c.catalogId, CommandOutcome.OBSERVED_OTHER, f.owner.state.value.outcomes[c.property])
                assertEquals(c.catalogId, PropertyStatus.OBSERVED, f.host.state.value.domain.propertyView(c.property)?.status)

                // Each enumerated input and both numeric endpoints enter through Host.dispatch.
                // This catches an ingress/property mix-up even if the standalone codec is correct.
                for (input in c.inputs) {
                    val old = f.observed(c.property)
                    val count = f.transport.commandWrites.size
                    assertTrue("${c.catalogId}/${input.value}", f.host.state.value.writeAllowed(c.property, input.number))
                    f.write(c.property, input.number)
                    f.await("${c.catalogId}/${input.value} raw TX") { f.transport.commandWrites.size == count + 1 }
                    f.sent(c.property)
                    assertArrayEquals(c.catalogId, HostProtocolWire.bytes(input.hex), f.transport.commandWrites.last().toByteArray())
                    assertEquals("TX must not manufacture RX: ${c.catalogId}", old, f.observed(c.property))
                    f.receiveScalar(input.hex, c.property, input.number)
                    assertEquals(c.catalogId, CommandOutcome.OBSERVED_MATCH, f.owner.state.value.outcomes[c.property])
                    assertEquals(c.catalogId, input.number, f.host.state.value.domain.propertyObserved(c.property)?.value)
                    if (c.property == CommandProperty.MODE)
                        assertEquals(input.value, f.host.state.value.currentMode?.wire)
                    if (c.property == CommandProperty.CONVERSATION)
                        assertEquals(input.value == 1, f.owner.state.value.conversation?.value)
                    assertFalse(c.catalogId, f.host.state.value.productGatePassed)
                    assertFalse(c.catalogId, f.owner.runtime.value.productConformance)
                    assertNull(f.owner.store.snapshot().getOrThrow().pairs.getValue(HostProtocolFixture.PAIR).verification)
                }
            }
        }
    }

    private fun denied(f: HostProtocolFixture, c: ScalarHostCase, value: PropertyValue = c.requested.number) {
        val writes = f.transport.commandWrites.size
        val events = f.events.count { it.step == QualificationStep.TX && it.property == c.property }
        val previous = f.observed(c.property)
        assertFalse(c.catalogId, f.host.state.value.writeAllowed(c.property, value))
        f.write(c.property, value)
        assertEquals(c.catalogId, writes, f.transport.commandWrites.size)
        assertEquals(c.catalogId, events, f.events.count { it.step == QualificationStep.TX && it.property == c.property })
        assertFalse(c.catalogId, c.property in f.owner.state.value.pending)
        assertEquals(c.catalogId, previous, f.observed(c.property))
    }

    @Test fun hostRejectsOutOfRangeWrongValueTypeAndEverySingleBitModeMask() {
        HostProtocolFixture().use { f ->
            f.start()
            for (c in ScalarHostCases.all) {
                denied(f, c, PropertyValue.Number(c.range.first - 1))
                denied(f, c, PropertyValue.Number(c.range.last + 1))
                denied(f, c, PropertyValue.Name("wrong scalar type"))
            }
            val mask = ScalarHostCases.get(CommandProperty.LISTENING_MODE_CONFIGS)
            listOf(0, 1, 2, 4, 8, 16).forEach { denied(f, mask, PropertyValue.Number(it)) }
            assertEquals(0, f.transport.commandWrites.size)
        }
    }

    @Test fun recognizedModelWithoutCapabilitiesStillSendsGatedFamiliesLikeUpstream() {
        HostProtocolFixture().use { f ->
            f.start()
            f.receiveModel(ScalarHostCases.INFORMATION_A1523, "A1523")
            assertTrue(f.host.state.value.controlsReady)
            for (c in ScalarHostCases.all) {
                if (c.needsCapability) {
                    assertEquals(c.catalogId, PropertyStatus.DEPENDENCY_EXCLUDED,
                        f.host.state.value.domain.propertyView(c.property)?.status)
                    // Capability is visibility-only: a direct write is still sent. 
                    val count = f.transport.commandWrites.size
                    f.write(c.property, c.requested.number)
                    f.await("gated ${c.catalogId} raw TX") { f.transport.commandWrites.size == count + 1 }
                    f.receiveScalar(c.requested.hex, c.property, c.requested.number)
                } else {
                    assertTrue(c.catalogId, f.host.state.value.writeAllowed(c.property, c.requested.number))
                    val count = f.transport.commandWrites.size
                    f.write(c.property, c.requested.number)
                    f.await("ungated ${c.catalogId} raw TX") { f.transport.commandWrites.size == count + 1 }
                    assertArrayEquals(c.catalogId, HostProtocolWire.bytes(c.requested.hex), f.transport.commandWrites.last().toByteArray())
                    f.receiveScalar(c.requested.hex, c.property, c.requested.number)
                }
            }
            f.receiveModel(ScalarHostCases.INFORMATION_UNKNOWN, "A9999")
            assertFalse(f.host.state.value.controlsReady)
            ScalarHostCases.all.forEach { denied(f, it) }
        }
    }

    @Test fun modeWritesAreSentWithoutModelOrAllowOffGatesWhileVisibilityStillFollowsThem() {
        HostProtocolFixture().use { f ->
            f.start()
            val mode = ScalarHostCases.get(CommandProperty.MODE)
            f.receiveModel(ScalarHostCases.INFORMATION_A2084, "A2084")
            assertTrue(f.host.state.value.controlsReady)
            assertTrue(f.host.state.value.writeAllowed(CommandProperty.MODE, PropertyValue.Number(2)))
            f.write(mode.property, PropertyValue.Number(4)); f.await("adaptive raw TX") { f.transport.commandWrites.isNotEmpty() }
            f.receiveModel(HostProtocolWire.INFORMATION, "A3063")
            val off = ScalarHostCases.get(CommandProperty.ALLOW_OFF_OPTION)
            f.receiveScalar(off.initial.hex, off.property, off.initial.number)
            assertFalse(ListeningMode.OFF in f.host.state.value.modes)
            val offCount = f.transport.commandWrites.size
            f.write(mode.property, PropertyValue.Number(1))
            f.await("off mode raw TX") { f.transport.commandWrites.size == offCount + 1 }
            val mask = ScalarHostCases.get(CommandProperty.LISTENING_MODE_CONFIGS)
            f.receiveScalar(mask.initial.hex, mask.property, mask.initial.number)
            val maskCount = f.transport.commandWrites.size
            f.write(mask.property, PropertyValue.Number(15))
            f.await("off mask raw TX") { f.transport.commandWrites.size == maskCount + 1 }
            assertTrue(f.host.state.value.writeAllowed(mask.property, PropertyValue.Number(6)))
            // Re-enabling changes the effective policy immediately, without manufacturing RX.
            f.write(off.property, off.requested.number)
            f.await("enable Off raw TX") { f.transport.count(off.requested.hex) == 1 }
            f.sent(off.property)
            f.refresh()
            assertTrue(ListeningMode.OFF in f.host.state.value.modes)
            assertTrue(f.host.state.value.writeAllowed(mode.property, PropertyValue.Number(1)))
            f.receiveScalar(off.requested.hex, off.property, off.requested.number)
            assertTrue(ListeningMode.OFF in f.host.state.value.modes)
            assertTrue(f.host.state.value.writeAllowed(mode.property, PropertyValue.Number(1)))
            assertTrue(f.host.state.value.writeAllowed(mask.property, PropertyValue.Number(15)))
        }
    }

    @Test fun staleGenerationAndDetachedIngressRejectEveryFamilyBeforeEnqueue() {
        HostProtocolFixture().use { f ->
            f.start()
            val generation = f.owner.state.value.generation
            for (c in ScalarHostCases.all) {
                f.write(c.property, c.requested.number, generation = generation - 1)
                assertFalse(c.catalogId, c.property in f.owner.state.value.pending)
            }
            f.ingressCurrent = false
            ScalarHostCases.all.forEach { f.write(it.property, it.requested.number) }
            assertTrue(f.owner.state.value.pending.isEmpty())
            f.ingressCurrent = true
            val savedStamp = f.host.stamp
            runBlocking { f.owner.stopControls() }
            f.refresh()
            assertEquals(AacpStage.CLOSED, f.owner.state.value.stage)
            assertFalse(f.host.state.value.controlsReady)
            for (c in ScalarHostCases.all) {
                f.write(c.property, c.requested.number, savedStamp, generation)
                denied(f, c) // also reject the current host stamp/current CLOSED generation
            }
            assertEquals(0, f.transport.commandWrites.size)
            assertFalse(f.events.any { it.step == QualificationStep.TX && it.property != null })
        }
    }

    @Test fun ordinaryAndClosedHostsCannotUseFunctionalAuthorityForAnyFamily() {
        HostProtocolFixture().use { f ->
            f.start()
            val ordinary = AirPodsHost(f.context, f.owner)
            f.installHostBluetooth(ordinary)
            try {
                runBlocking {
                    ordinary.refresh()
                    assertFalse(ordinary.state.value.controlsReady)
                    for (c in ScalarHostCases.all) ordinary.dispatch(ordinary.stamp,
                        AirPodsUiAction.Write(f.owner.state.value.generation, c.property, c.requested.number)) {
                        _, _ -> fail("No platform launch")
                    }
                }
                val stamp = f.host.stamp
                f.detachHostBluetooth(f.host)
                f.host.close()
                ScalarHostCases.all.forEach { f.write(it.property, it.requested.number, stamp) }
                assertTrue(f.owner.state.value.pending.isEmpty())
                assertEquals(0, f.transport.commandWrites.size)
            } finally { f.detachHostBluetooth(ordinary); ordinary.close() }
        }
    }

    @Test fun bluetoothPermissionLossDeniesEveryFamily() = revoked { it.revokeBluetoothPermission() }
    @Test fun revokedRealPermitDeniesEveryFamily() = revoked { it.permit.revoke() }

    private fun revoked(revoke: (HostProtocolFixture) -> Unit) {
        HostProtocolFixture().use { f ->
            f.start()
            revoke(f)
            f.refresh()
            assertFalse(f.host.state.value.controlsReady)
            ScalarHostCases.all.forEach { denied(f, it) }
            assertEquals(0, f.transport.commandWrites.size)
        }
    }
}
