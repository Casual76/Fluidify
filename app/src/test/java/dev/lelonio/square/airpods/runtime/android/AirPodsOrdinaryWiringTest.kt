package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Build
import dev.lelonio.square.airpods.runtime.android.platform.*
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** PREPARED, NOT EXECUTED. Actual controller + ordinary adapter with a fake profile port. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [30])
class AirPodsOrdinaryWiringTest {
    private class Profile(val cold: Boolean = false) : AudioProfilePort {
        val operations = mutableListOf<AudioOperation>()
        var callback: (() -> Unit)? = null
        var closed = false
        override fun ready() = !cold
        override fun prepare(onReady: () -> Unit): Boolean { callback = onReady; return true }
        override fun invoke(pair: PairAddress, operation: AudioOperation): PlatformResult {
            operations += operation; return PlatformResult.RequestAccepted
        }
        override fun close() { closed = true }
    }
    private fun fixture(profile: Profile, conformance: Boolean = true): AirPodsAndroidController {
        org.robolectric.shadows.ShadowProcess.setPid(41991)
        val context = RuntimeEnvironment.getApplication()
        val pair = PairAddress("11:22:33:44:55:66")
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(pair.value)
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
        val owner = AirPodsAndroidController(context, MutableStateFlow(true), { conformance }, null,
            audioProfileFactory = { profile })
        owner.store.select(pair).getOrThrow()
        owner.store.recordSetup(pair, PairVerification("fixture", Build.FINGERPRINT, 1)).getOrThrow()
        owner.store.transaction { it.copy(start = it.start.copy(enabled = true, consentGeneration = 1)) to Unit }.getOrThrow()
        return owner
    }
    private fun close(owner: AirPodsAndroidController) = runBlocking {
        owner.stopControls()
        val context = RuntimeEnvironment.getApplication()
        context.unregisterReceiver(ReflectionHelpers.getField(owner, "receiver"))
        ReflectionHelpers.getField<CoroutineScope>(owner, "processScope").cancel()
    }
    @Test fun disconnectedVerifiedPairConnectsAudioWithoutControlTicketOrFgs() = runBlocking {
        val profile = Profile(); val owner = fixture(profile)
        try {
            assertFalse(owner.hasAdmission())
            assertEquals(PlatformResult.RequestAccepted, owner.requestAudio(AudioOperation.CONNECT))
            assertEquals(listOf(AudioOperation.CONNECT), profile.operations)
            assertTrue(owner.requestAudio(AudioOperation.DISCONNECT, AudioOrigin.UNWORN) is PlatformResult.Denied)
            assertEquals(listOf(AudioOperation.CONNECT), profile.operations)
            assertFalse(owner.runtime.value.active)
        } finally { close(owner) }
    }
    @Test fun disconnectDispatchIsSeparateFromStopAndDoesNotInventConnectedState() = runBlocking {
        val profile = Profile(); val owner = fixture(profile)
        try {
            assertEquals(PlatformResult.RequestAccepted, owner.requestAudio(AudioOperation.DISCONNECT))
            assertTrue(owner.store.snapshot().getOrThrow().start.enabled)
            assertFalse(owner.runtime.value.active)
            owner.stopControls()
            assertEquals(listOf(AudioOperation.DISCONNECT), profile.operations)
        } finally { close(owner) }
    }
    @Test fun stopCancelsColdContinuationAndLateCallbackCannotDispatch() = runBlocking {
        val profile = Profile(true); val owner = fixture(profile)
        try {
            assertEquals(PlatformResult.Queued, owner.requestAudio(AudioOperation.CONNECT))
            owner.stopControls(); profile.callback?.invoke()
            assertTrue(profile.operations.isEmpty()); assertTrue(profile.closed)
        } finally { close(owner) }
    }
    @Test fun productFalseAndUnwornWithoutCurrentSessionNeverReachPlatform() = runBlocking {
        val profile = Profile(); val owner = fixture(profile, false)
        try {
            assertTrue(owner.requestAudio(AudioOperation.CONNECT) is PlatformResult.Denied)
            assertTrue(owner.requestAudio(AudioOperation.DISCONNECT, AudioOrigin.UNWORN) is PlatformResult.Denied)
            assertTrue(profile.operations.isEmpty())
        } finally { close(owner) }
    }
    @Test(timeout = 10_000) fun pairSelectionAndConfigurationRevokeColdRequests() = runBlocking {
        val profile = Profile(true); val owner = fixture(profile)
        try {
            assertEquals(PlatformResult.Queued, owner.requestAudio(AudioOperation.CONNECT))
            owner.configure { it.copy(host = it.host.copy(fluidifyBackgroundEnabled = true)) }.getOrThrow()
            profile.callback?.invoke()
            assertTrue(profile.operations.isEmpty())
            owner.store.select(PairAddress("11:22:33:44:55:77")).getOrThrow()
            assertTrue(owner.requestAudio(AudioOperation.CONNECT) is PlatformResult.Denied)
            assertTrue(profile.operations.isEmpty())
        } finally { close(owner) }
    }
    @Test fun tileListeningEligibilityIsInertWithoutCurrentIdentity() = runBlocking {
        val profile = Profile(); val owner = fixture(profile)
        try {
            val before = owner.store.snapshot().getOrThrow()
            assertEquals("NO_ADMITTED_SESSION", owner.tileUnavailableReason())
            assertFalse(owner.cycleTile())
            assertEquals(before, owner.store.snapshot().getOrThrow())
            assertTrue(profile.operations.isEmpty())
        } finally { close(owner) }
    }
    @Test fun communicationModeBlocksActualControllerAudioDispatch() = runBlocking {
        val profile = Profile(); val owner = fixture(profile)
        val manager = RuntimeEnvironment.getApplication().getSystemService(android.media.AudioManager::class.java)
        try {
            manager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            assertEquals(PlatformResult.Denied(PlatformFailure.CALL_OR_ROUTE_MIX), owner.requestAudio(AudioOperation.CONNECT))
            assertTrue(profile.operations.isEmpty())
        } finally { manager.mode = android.media.AudioManager.MODE_NORMAL; close(owner) }
    }
}
