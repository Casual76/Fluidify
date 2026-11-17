// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.protocol.AirPodsProperties
import dev.lelonio.square.airpods.protocol.PropertyValue
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Prepared only: real atomic store, failing persistence and explicit admission boundary. */
class AirPodsC4CoordinationTest {
    private class Persistence : RuntimePersistence {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes?.clone()
        override fun commit(bytes: ByteArray): Boolean {
            if (fail) return false
            this.bytes = bytes.clone()
            return true
        }
    }

    @Test fun earGestureUpdatesBothPreferencesAtomicallyAndKeepsOtherOptIns() {
        val old = PairSettings(host = HostSettings(conversationPause = true, conversationVolumePercent = 55,
            headGestures = true, disconnectUnworn = true))
        val enabled = mainToggleSettings(old, CommandProperty.EAR_DETECTION_CONFIG, true)
        assertFalse(enabled.host.automationsEnabled)
        assertTrue(enabled.host.earPause); assertTrue(enabled.host.earResume)
        assertEquals(old.host.copy(earPause = true, earResume = true), enabled.host)
        assertFalse(enabled.host.otherAppsEnabled); assertFalse(enabled.host.fluidifyBackgroundEnabled)
        val disabled = mainToggleSettings(enabled, CommandProperty.EAR_DETECTION_CONFIG, false)
        assertEquals(enabled.host.copy(earPause = false, earResume = false), disabled.host)
    }

    @Test fun conversationDefaultsToDuckButHonorsAnExistingPausePolicyAndVolume() {
        val enabled = mainToggleSettings(PairSettings(), CommandProperty.CONVERSATION, true)
        assertFalse(enabled.host.automationsEnabled); assertTrue(enabled.host.conversationDuck)
        assertFalse(enabled.host.conversationPause)
        val advanced = enabled.copy(host = enabled.host.copy(conversationDuck = false,
            conversationPause = true, conversationRelative = false, conversationVolumePercent = 33))
        assertEquals(advanced, mainToggleSettings(advanced, CommandProperty.CONVERSATION, true))
        val off = mainToggleSettings(advanced, CommandProperty.CONVERSATION, false)
        assertFalse(off.host.conversationDuck); assertTrue(off.host.conversationPause)
        assertEquals(advanced, mainToggleSettings(off, CommandProperty.CONVERSATION, true))
        assertFalse(off.host.conversationRelative); assertEquals(33, off.host.conversationVolumePercent)
        assertFalse(off.host.automationsEnabled)
    }

    @Test fun conversationRoundTripPreservesEveryConfiguredPolicyAndMasterGate() {
        for (master in listOf(false, true)) for (duck in listOf(false, true)) for (pause in listOf(false, true)) {
            if (!duck && !pause) continue
            val old = PairSettings(host = HostSettings(automationsEnabled = master,
                conversationDuck = duck, conversationPause = pause, headGestures = true))
            val off = mainToggleSettings(old, CommandProperty.CONVERSATION, false)
            assertEquals(old, off)
            assertEquals(old, mainToggleSettings(off, CommandProperty.CONVERSATION, true))
        }
    }

    @Test fun storageFailurePreventsDeviceSendAndCannotPersistHalfOfEarPolicy() = runTest {
        val persistence = Persistence()
        val store = AirPodsRuntimeStore(persistence)
        val address = PairAddress("02:00:00:00:00:74")
        store.select(address).getOrThrow()
        val before = store.snapshot().getOrThrow()
        persistence.fail = true
        var sends = 0
        val problem = coordinateMainToggle(CommandProperty.EAR_DETECTION_CONFIG, true,
            saveLocal = { store.configure(address, it).isSuccess }, send = { sends++; true })
        assertEquals(AirPodsMainToggleFailure.LOCAL_SAVE_FAILED, problem)
        assertEquals(before, store.snapshot().getOrThrow())
        assertEquals(0, sends)
    }

    @Test fun admissionFailureKeepsTheSuccessfulLocalIntentWithExplicitPartialResult() = runTest {
        var pair = PairSettings()
        val order = mutableListOf<String>()
        val problem = coordinateMainToggle(CommandProperty.CONVERSATION, true,
            saveLocal = { update -> order += "save"; pair = update(pair); true },
            send = { value ->
                order += "send"
                assertTrue(pair.host.conversationDuck)
                assertEquals(PropertyValue.Number(1), value)
                false
            })
        assertEquals(listOf("save", "send"), order)
        assertEquals(AirPodsMainToggleFailure.DEVICE_NOT_SENT, problem)
        assertTrue(pair.host.conversationDuck)
    }

    @Test fun offSavesBeforeSend() = runTest {
        var pair = mainToggleSettings(PairSettings(), CommandProperty.EAR_DETECTION_CONFIG, true)
        val problem = coordinateMainToggle(CommandProperty.EAR_DETECTION_CONFIG, false,
            saveLocal = { pair = it(pair); true }, send = {
                assertFalse(pair.host.earPause); assertFalse(pair.host.earResume)
                assertEquals(PropertyValue.Number(2), it)
                true
            })
        assertNull(problem)
    }

    @Test fun personalizationAndAutomaticConnectionAreNotLocalAutomationSettings() {
        assertEquals(setOf(CommandProperty.EAR_DETECTION_CONFIG, CommandProperty.CONVERSATION), coordinatedMainProperties)
        for (property in listOf(CommandProperty.ADAPTIVE_VOLUME_CONFIG, CommandProperty.AUTOMATIC_CONNECTION_CONFIG)) {
            assertFalse(property in coordinatedMainProperties)
        }
    }

    @Test fun adaptiveRegistryLivesOnMainAndInvertsTheProtocolExactlyOnce() {
        val property = CommandProperty.AUTO_ANC_STRENGTH
        assertEquals(AirPodsSettingsPage.Main, airPodsSettingsPageOf(property))
        assertTrue(airPodsPageSpecs(AirPodsSettingsPage.AdaptiveStrength).isEmpty())
        val spec = AirPodsProperties.spec(property)!!
        assertEquals(listOf(75), spec.encode(25))
        assertEquals(PropertyValue.Number(25), spec.decode(listOf(75, 0, 0, 0)))
        assertEquals(listOf(100), spec.encode(0)); assertEquals(listOf(0), spec.encode(100))
        assertTrue(spec.candidate(dev.lelonio.square.airpods.AirPodsIdentity("A3063", "Apple Inc.", "fixture")))
        assertFalse(spec.candidate(dev.lelonio.square.airpods.AirPodsIdentity("A2084", "Apple Inc.", "fixture")))
    }
}
