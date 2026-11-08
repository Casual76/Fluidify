package dev.lelonio.square.ui.airpods.host

import dev.lelonio.square.airpods.runtime.storage.*
import dev.lelonio.square.ui.airpods.*
import org.junit.Assert.*
import org.junit.Test

class AirPodsHostPolicyTest {
    private val a = PairAddress("00:11:22:33:44:55")
    private val b = PairAddress("00:11:22:33:44:66")
    private fun data() = AirPodsRuntimeData(selectedPair = a, pairs = mapOf(a to PairSettings(), b to PairSettings()),
        start = StartState(consentGeneration = 4))
    @Test fun queuedActionsCannotCrossPairConsentOrRepository() {
        val data = data()
        val stamp = HostStamp.capture(data, 7)
        assertTrue(stamp.matches(data, 7))
        assertFalse(stamp.matches(data.copy(selectedPair = b), 7))
        assertFalse(stamp.matches(data.copy(start = data.start.copy(consentGeneration = 5)), 7))
        assertFalse(stamp.matches(data, 8))
    }
    @Test fun candidateIdsAreOpaqueStableAndNeverSelectFirst() {
        val ids = CandidateIds()
        val first = ids.id(a)
        assertEquals(first, ids.id(PairAddress(a.value.lowercase())))
        assertNotEquals(first, ids.id(b))
        assertFalse(first.contains(a.value, true))
        assertNull(ids.resolve("Paired device 1"))
        assertNull(ids.resolve(a.value))
        ids.retain(setOf(b))
        assertNull(ids.resolve(first))
    }
    @Test fun leftRightCannotBecomePlaybackShortcuts() {
        assertEquals(LongPressAction.CYCLE_NOISE_CONTROL_MODES, longAction(GestureAction.NOISE_CONTROL))
        assertEquals(LongPressAction.DIGITAL_ASSISTANT, longAction(GestureAction.DIGITAL_ASSISTANT))
        listOf(GestureAction.NONE, GestureAction.NEXT, GestureAction.PREVIOUS, GestureAction.PLAY_PAUSE).forEach { assertNull(longAction(it)) }
    }
    @Test fun nodShakeDoNotGainAssistantOrNoiseControl() {
        assertNull(headAction(GestureAction.NOISE_CONTROL)); assertNull(headAction(GestureAction.DIGITAL_ASSISTANT))
        assertEquals(HeadAction.NONE, headAction(GestureAction.NONE))
        assertEquals(HeadAction.TOGGLE_PLAYBACK, headAction(GestureAction.PLAY_PAUSE))
        assertEquals(HeadAction.NEXT, headAction(GestureAction.NEXT)); assertEquals(HeadAction.PREVIOUS, headAction(GestureAction.PREVIOUS))
    }
    @Test fun otherAppsConsentIsDedicatedAndOffByDefault() {
        val defaults = PairSettings()
        assertFalse(defaults.host.otherAppsEnabled)
        val opted = toggleSettings(defaults, LocalToggle.OTHER_APPS, true)
        assertTrue(opted.host.otherAppsEnabled)
        assertFalse(opted.host.automationsEnabled); assertFalse(opted.host.fluidifyBackgroundEnabled)
        assertFalse(toggleSettings(opted, LocalToggle.OTHER_APPS, false).host.otherAppsEnabled)
    }
    @Test fun changingPacketFormatPreservesVerificationAndIndependentIntent() {
        val before = PairSettings(verification=PairVerification("A3063", "build", 1),
            nodAction=HeadAction.NEXT, host=HostSettings(otherAppsEnabled=true))
        val after = toggleSettings(before, LocalToggle.ALTERNATE_PACKETS, false)
        assertFalse(after.headPacketAlternate); assertEquals(before.verification, after.verification)
        assertEquals(HeadAction.NEXT,after.nodAction); assertTrue(after.host.otherAppsEnabled); assertFalse(after.host.headGestures)
    }
}
