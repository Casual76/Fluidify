// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import android.app.Application
import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.protocol.PropertyValue
import dev.lelonio.square.airpods.protocol.propertyEffective
import dev.lelonio.square.ui.airpods.AirPodsMainToggleFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prepared, not run. Real host/controller/repository, synthetic AACP and Bluetooth only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35],
    instrumentedPackages = ["dev.lelonio.square.airpods"], shadows = [HostProtocolSocketShadow::class])
class AirPodsC4HostCoordinationTest {
    @Test fun earGesturePersistsPauseAndResumeSends0aAndContraryRxDoesNotBecomeLocalIntent() {
        HostProtocolFixture().use { f ->
            f.start()
            val property = CommandProperty.EAR_DETECTION_CONFIG
            assertFalse(f.host.state.value.pair.host.automationsEnabled)
            f.write(property, PropertyValue.Number(1))
            f.sent(property)
            val saved = f.owner.store.snapshot().getOrThrow().pairs.getValue(HostProtocolFixture.PAIR).host
            assertFalse(saved.automationsEnabled); assertTrue(saved.earPause); assertTrue(saved.earResume)
            assertFalse(saved.otherAppsEnabled); assertFalse(saved.fluidifyBackgroundEnabled)
            assertEquals(1, f.transport.count("04 00 04 00 09 00 0a 01 00 00 00"))
            assertNull(f.observed(property))
            assertEquals(PropertyValue.Number(1), f.host.state.value.domain.propertyEffective(property)?.value)
            f.receive("04 00 04 00 09 00 0a 02 00 00 00", property, PropertyValue.Number(2))
            assertEquals(PropertyValue.Number(2), f.host.state.value.domain.propertyEffective(property)?.value)
            assertEquals(saved, f.owner.store.snapshot().getOrThrow().pairs.getValue(HostProtocolFixture.PAIR).host)
            f.write(property, PropertyValue.Number(2))
            val off = f.owner.store.snapshot().getOrThrow().pairs.getValue(HostProtocolFixture.PAIR).host
            assertFalse(off.earPause); assertFalse(off.earResume)
            assertFalse(off.automationsEnabled)
        }
    }

    @Test fun conversationCoordinates28While26And20LeaveAllLocalPoliciesUntouched() {
        HostProtocolFixture().use { f ->
            f.start()
            f.write(CommandProperty.CONVERSATION, PropertyValue.Number(1))
            f.sent(CommandProperty.CONVERSATION)
            val saved = f.owner.store.snapshot().getOrThrow().pairs.getValue(HostProtocolFixture.PAIR).host
            assertTrue(saved.conversationDuck); assertFalse(saved.conversationPause)
            assertEquals(40, saved.conversationVolumePercent)
            assertEquals(1, f.transport.count("04 00 04 00 09 00 28 01 00 00 00"))
            assertNull(f.observed(CommandProperty.CONVERSATION))
            for (property in listOf(CommandProperty.ADAPTIVE_VOLUME_CONFIG, CommandProperty.AUTOMATIC_CONNECTION_CONFIG)) {
                f.write(property, PropertyValue.Number(1)); f.sent(property)
                assertEquals(saved, f.owner.store.snapshot().getOrThrow().pairs.getValue(HostProtocolFixture.PAIR).host)
            }
            assertEquals(1, f.transport.count("04 00 04 00 09 00 26 01 00 00 00"))
            assertEquals(1, f.transport.count("04 00 04 00 09 00 20 01 00 00 00"))
            f.write(CommandProperty.CONVERSATION, PropertyValue.Number(2))
            f.sent(CommandProperty.CONVERSATION)
            val off = f.owner.store.snapshot().getOrThrow().pairs.getValue(HostProtocolFixture.PAIR).host
            assertTrue(off.conversationDuck); assertFalse(off.conversationPause)
            assertEquals(saved.conversationVolumePercent, off.conversationVolumePercent)
        }
    }

    @Test fun configuredPausePolicyIsKeptWhenEnablingConversation() {
        HostProtocolFixture().use { f ->
            f.start()
            runBlocking {
                f.owner.configure { it.copy(host = it.host.copy(conversationPause = true,
                    conversationDuck = false, conversationRelative = false, conversationVolumePercent = 33)) }.getOrThrow()
            }
            f.refresh()
            f.write(CommandProperty.CONVERSATION, PropertyValue.Number(1))
            f.sent(CommandProperty.CONVERSATION)
            val saved = f.owner.store.snapshot().getOrThrow().pairs.getValue(HostProtocolFixture.PAIR).host
            assertTrue(saved.conversationPause); assertFalse(saved.conversationDuck)
            assertFalse(saved.conversationRelative); assertEquals(33, saved.conversationVolumePercent)
        }
    }

    @Test fun staleGenerationAndPermissionRevocationCannotSavePreferencesOrSend() {
        HostProtocolFixture().use { f ->
            f.start()
            val before = f.owner.store.snapshot().getOrThrow()
            val writes = f.transport.commandWrites.size
            f.write(CommandProperty.EAR_DETECTION_CONFIG, PropertyValue.Number(1), generation = f.owner.state.value.generation - 1)
            assertEquals(before, f.owner.store.snapshot().getOrThrow())
            assertEquals(AirPodsMainToggleFailure.UNAVAILABLE, f.host.state.value.mainToggleIssue?.failure)
            f.revokeBluetoothPermission()
            f.write(CommandProperty.EAR_DETECTION_CONFIG, PropertyValue.Number(1))
            assertEquals(before, f.owner.store.snapshot().getOrThrow())
            assertEquals(writes, f.transport.commandWrites.size)
        }
    }
}
