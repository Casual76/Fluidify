// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android.platform

import dev.lelonio.square.airpods.runtime.storage.PairAddress
import org.junit.Assert.*
import org.junit.Test

class OrdinaryPlatformOperationsTest {
    private class Profile : AudioProfilePort {
        var warm = true
        var callback: (() -> Unit)? = null
        var calls = mutableListOf<Pair<PairAddress, AudioOperation>>()
        var answer: PlatformResult = PlatformResult.RequestAccepted
        var closes = 0
        override fun ready() = warm
        override fun prepare(onReady: () -> Unit): Boolean { callback = onReady; return true }
        override fun invoke(pair: PairAddress, operation: AudioOperation): PlatformResult {
            calls += pair to operation; return answer
        }
        override fun close() { closes++ }
        fun arrive() { warm = true; callback?.invoke() }
    }
    private class Fixture {
        val profile = Profile()
        var now = 100L
        var current = true
        var facts = AudioAuthorization(true, true, true, true, true, true)
        val action = PlatformAction(PairAddress("00:11:22:33:44:55"), 3, 4, 100)
        val audio = OrdinaryAudioOperations(profile, { now }, { facts })
        fun request(origin: AudioOrigin = AudioOrigin.MANUAL, operation: AudioOperation = AudioOperation.DISCONNECT,
            completed: (PlatformResult) -> Unit = {}) = audio.request(action, operation, origin, { current }, completed)
    }
    @Test fun ordinaryManualConnectAndDisconnectSubmitDistinctAudioRequests() {
        val f = Fixture()
        assertEquals(PlatformResult.RequestAccepted, f.request(operation = AudioOperation.CONNECT))
        assertEquals(PlatformResult.RequestAccepted, f.request())
        assertEquals(listOf(AudioOperation.CONNECT, AudioOperation.DISCONNECT), f.profile.calls.map { it.second })
    }
    @Test fun coldProxyExecutesOnceAndNeverOverwritesPendingPair() {
        val f = Fixture(); f.profile.warm = false
        val results = mutableListOf<PlatformResult>()
        assertEquals(PlatformResult.Queued, f.request(completed = results::add))
        assertEquals(PlatformResult.Denied(PlatformFailure.BUSY), f.audio.request(
            f.action.copy(pair = PairAddress("11:22:33:44:55:66")), AudioOperation.CONNECT, AudioOrigin.MANUAL, { true }))
        f.profile.arrive(); f.profile.arrive()
        assertEquals(listOf(PlatformResult.RequestAccepted), results)
        assertEquals(listOf(f.action.pair to AudioOperation.DISCONNECT), f.profile.calls)
    }
    @Test fun coldProxyRechecksAllRevocationsAndNeverEmitsAudio() {
        val mutations: List<(Fixture) -> Unit> = listOf(
            { it.current = false }, // pair/session/consent change or system suppression
            { it.facts = it.facts.copy(permission = false) },
            { it.facts = it.facts.copy(selectedBonded = false) },
            { it.facts = it.facts.copy(controllerEntitled = false) },
            { it.facts = it.facts.copy(noCallOrRouteMix = false) },
            { it.now += 5_001 },
        )
        mutations.forEach { mutate ->
            val f = Fixture(); f.profile.warm = false; var completed: PlatformResult? = null
            f.request { completed = it }; mutate(f); f.profile.arrive()
            assertTrue(completed is PlatformResult.Denied); assertTrue(f.profile.calls.isEmpty())
        }
    }
    @Test fun unwornNeedsExplicitPreferenceValidatedNoneAndDisconnectOperation() {
        val f = Fixture()
        f.facts = f.facts.copy(unwornPreference = false)
        assertEquals(PlatformResult.Denied(PlatformFailure.UNWORN_NOT_AUTHORIZED), f.request(AudioOrigin.UNWORN))
        f.facts = f.facts.copy(unwornPreference = true, validatedNone = false)
        assertEquals(PlatformResult.Denied(PlatformFailure.UNWORN_NOT_AUTHORIZED), f.request(AudioOrigin.UNWORN))
        f.facts = f.facts.copy(validatedNone = true)
        assertEquals(PlatformResult.Denied(PlatformFailure.UNWORN_NOT_AUTHORIZED), f.request(AudioOrigin.UNWORN, AudioOperation.CONNECT))
        assertTrue(f.profile.calls.isEmpty())
        assertEquals(PlatformResult.RequestAccepted, f.request(AudioOrigin.UNWORN))
    }
    @Test fun unwornRevocationWhileProxyColdPreventsDisconnect() {
        val f = Fixture(); f.profile.warm = false
        f.request(AudioOrigin.UNWORN); f.facts = f.facts.copy(validatedNone = false); f.profile.arrive()
        assertTrue(f.profile.calls.isEmpty())
    }
    @Test fun unavailableHiddenMetadataAndBinderDenialOfferOnlyManualFallback() {
        listOf(PlatformFailure.HIDDEN_API_UNAVAILABLE, PlatformFailure.SECURITY, PlatformFailure.REJECTED).forEach { reason ->
            val f = Fixture(); f.profile.answer = PlatformResult.Denied(reason)
            assertEquals(PlatformResult.SettingsRequired(reason), f.request())
            assertEquals(PlatformResult.Denied(reason), f.request(AudioOrigin.UNWORN))
        }
    }
    @Test fun stopCancelsPendingAndLateProxyCannotExecute() {
        val f = Fixture(); f.profile.warm = false; val outcomes = mutableListOf<PlatformResult>()
        f.request(completed = outcomes::add); f.audio.close(); f.profile.arrive()
        assertEquals(listOf(PlatformResult.Denied(PlatformFailure.STALE)), outcomes)
        assertTrue(f.profile.calls.isEmpty()); assertEquals(1, f.profile.closes)
        assertEquals(PlatformResult.Denied(PlatformFailure.CLOSED), f.request())
    }
    @Test fun clockRollbackAndOldManualActionAreDenied() {
        val f = Fixture(); f.now = 99
        assertEquals(PlatformResult.Denied(PlatformFailure.STALE), f.request())
        f.now = 5_101
        assertEquals(PlatformResult.Denied(PlatformFailure.STALE), f.request())
        assertTrue(f.profile.calls.isEmpty())
    }

    private class Assistant {
        var now = 100L
        var current = true
        var facts = AssistantAuthorization(true, true, true, true, false, true, true, true)
        var calls = 0
        var answer: PlatformResult = PlatformResult.RequestAccepted
        val op = OrdinaryAssistantOperation(AssistantLaunchPort { calls++; answer }, { now }, { facts })
        val action = PlatformAction(PairAddress("00:11:22:33:44:55"), 3, 4, 100)
        fun request() = op.request(action) { current }
    }
    @Test fun ownCompanionAndLivePhysicalGestureAllowBackgroundBestEffortRequest() {
        val f = Assistant()
        assertEquals(PlatformResult.RequestAccepted, f.request()); assertEquals(1, f.calls)
    }
    @Test fun companionIsNotBlanketBackgroundPermissionOrSyntheticGestureGrant() {
        val denied = listOf(
            AssistantAuthorization(true, true, true, true, false, false, true, true),
            AssistantAuthorization(true, true, true, true, false, true, false, true),
            AssistantAuthorization(true, true, true, true, false, true, true, false),
            AssistantAuthorization(false, true, true, true, false, true, true, true),
            AssistantAuthorization(true, false, true, true, false, true, true, true),
            AssistantAuthorization(true, true, false, true, false, true, true, true),
            AssistantAuthorization(true, true, true, false, false, true, true, true),
        )
        denied.forEach { facts -> val f = Assistant(); f.facts = facts
            assertTrue(f.request() is PlatformResult.Denied); assertEquals(0, f.calls) }
    }
    @Test fun visiblePhysicalActionDoesNotRequireCompanionBackgroundOptIn() {
        val f = Assistant(); f.facts = f.facts.copy(visible = true, ownAssociation = false, backgroundOptIn = false)
        assertEquals(PlatformResult.RequestAccepted, f.request())
    }
    @Test fun assistantStaleActionOrRevocationCannotStart() {
        val f = Assistant(); f.current = false
        assertEquals(PlatformResult.Denied(PlatformFailure.STALE), f.request()); assertEquals(0, f.calls)
        f.current = true; f.now += 5_001
        assertEquals(PlatformResult.Denied(PlatformFailure.STALE), f.request()); assertEquals(0, f.calls)
    }
    @Test fun assistantNoHandlerOrSecurityAreNotAcknowledgements() {
        listOf(PlatformFailure.NO_HANDLER, PlatformFailure.SECURITY, PlatformFailure.NO_CONTEXT).forEach {
            val f = Assistant(); f.answer = PlatformResult.Denied(it)
            assertEquals(PlatformResult.Denied(it), f.request())
        }
    }
}
