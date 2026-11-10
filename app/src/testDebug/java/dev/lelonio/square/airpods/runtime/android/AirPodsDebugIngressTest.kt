// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import dev.lelonio.square.airpods.runtime.AirPodsForegroundState
import dev.lelonio.square.airpods.runtime.android.platform.*
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

/** PREPARED ONLY. Real controller/platform queue with synthetic facts; no native/socket invocation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsDebugIngressTest {
    private val pair = PairAddress("12:34:56:78:9A:BC")
    @Before fun processIdentity() { ShadowProcess.setPid(44222) }
    private class Facts : BluetoothRuntimeFacts {
        override val adapter: BluetoothAdapter? = null
        var connected = false; var permission = true
        override fun permitted() = permission
        override fun bond(address: PairAddress) = BondFact.BONDED
        override fun connection(address: PairAddress) = if(connected) ProfileConnection.CONNECTED else ProfileConnection.DISCONNECTED
        override fun prepare() {}
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? = null
        override fun audioSettingsIntent() = Intent("settings")
    }
    private class Registry : CompanionRegistryFacade {
        override fun hasFeature() = false
        override fun hasObservePermission() = false
        override fun isBonded(address: PairAddress) = true
        override fun ownAssociations() = emptyList<OwnedAssociation>()
        override fun start(registration: ObservationRegistration) = false
        override fun stop(registration: ObservationRegistration) = true
        override fun disassociate(association: OwnedAssociation) = false
    }
    private class Profile : AudioProfilePort {
        var cold = true; var invoked = 0; var callback: (() -> Unit)? = null
        var result: PlatformResult = PlatformResult.RequestAccepted
        override fun ready() = !cold
        override fun prepare(onReady: () -> Unit): Boolean { callback = onReady; return true }
        override fun invoke(pair: PairAddress, operation: AudioOperation): PlatformResult { invoked++; return result }
        override fun close() {}
    }
    private class Fixture {
        val facts = Facts(); val profile = Profile(); val visible = AirPodsForegroundState().apply { value = true }
        val store = AirPodsRuntimeStore(object : RuntimePersistence {
            var bytes: ByteArray? = null
            override fun read() = bytes?.clone()
            override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes.clone(); return true }
        })
        /** One-shot pause inside C4 reconciliation, only on [historyThread]: a cold callback is held
         * inside its live authorization (operations monitor owned) at a deterministic point. */
        @Volatile var historyThread: Thread? = null
        @Volatile var historyHook: (() -> Unit)? = null
        val owner = AirPodsAndroidController(RuntimeEnvironment.getApplication(), visible, { false }, null,
            AirPodsFunctionalTestPermit.issue(SystemClock::elapsedRealtime), store, audioProfileFactory = { profile },
            factsOverride = facts, registryOverride = Registry(),
            exitHistoryOverride = ExitHistoryProvider {
                if (Thread.currentThread() === historyThread) historyHook?.also { historyHook = null }?.invoke()
                ExitHistory.Available(emptyList())
            }, runtimeDispatcher = Dispatchers.Unconfined)
        suspend fun grantAudio() {
            owner.selectPair(PairAddress("12:34:56:78:9A:BC")).getOrThrow()
            val receipt = AirPodsAudioSetupReceipt(PairAddress("12:34:56:78:9A:BC"), requireNotNull(airPodsQualificationBuild(RuntimeEnvironment.getApplication())), 7,
                SystemClock.elapsedRealtime(), SystemClock::elapsedRealtime)
            assertTrue(owner.beginFunctionalAudio(receipt) is GateResult.Resumed)
        }
    }
    @Test fun disconnectedAudioGrantDoesNotCreateSetupTransportLeaseOrAutomation() = runBlocking {
        val f = Fixture()
        try {
            f.grantAudio()
            assertEquals(PlatformResult.Queued, f.owner.requestAudio(AudioOperation.CONNECT))
            f.profile.callback?.invoke()
            assertEquals(1, f.profile.invoked)
            assertFalse(f.owner.runtime.value.active); assertFalse(f.owner.runtime.value.setupVerified)
            assertFalse(f.owner.runtime.value.productConformance); assertFalse(f.owner.hasAdmission())
            assertEquals(ProfileConnection.DISCONNECTED, f.owner.audioProfile.value)
            assertNull(f.store.snapshot().getOrThrow().pairs.getValue(pair).verification)
            assertFalse(f.owner.cycleTile()); assertFalse(f.owner.setVolume(1))
            assertTrue(f.owner.requestAudio(AudioOperation.DISCONNECT, AudioOrigin.UNWORN) is PlatformResult.Denied)
            assertEquals(1, f.profile.invoked)
        } finally { f.owner.disposeFunctionalTest() }
    }
    @Test fun hideShowAndStopEachRejectColdAudioCallback() = runBlocking {
        for (stop in listOf(false, true)) {
            val f = Fixture()
            try {
                f.grantAudio()
                assertEquals(PlatformResult.Queued, f.owner.requestAudio(AudioOperation.CONNECT))
                if (stop) f.owner.stopControls() else { f.visible.value = false; f.visible.value = true }
                f.profile.callback?.invoke()
                assertEquals(0, f.profile.invoked)
            } finally { f.owner.disposeFunctionalTest() }
        }
    }
    /** Regression for the SDK35 jcmd cycle (callback: audio monitor -> platformLock; Stop:
     * platformLock -> audio.close). The callback is held inside its live authorization while Stop
     * takes gate/platformLock and starts closing; both must finish, without a command after Stop
     * began and without publishing the old outcome. Unconfined runtime dispatcher kept on purpose. */
    @Test fun coldCallbackDuringStopTerminatesWithoutCommandOrStaleOutcome() = runBlocking {
        val f = Fixture()
        var terminated = false
        try {
            f.grantAudio()
            assertEquals(PlatformResult.Queued, f.owner.requestAudio(AudioOperation.CONNECT))
            assertEquals(PlatformResult.Queued, f.owner.audioResult.value)
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val callback = Thread({ f.profile.callback?.invoke() }, "sdk-a2dp-callback").apply { isDaemon = true }
            f.historyThread = callback
            f.historyHook = { entered.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
            callback.start()
            assertTrue("callback never reached live authorization", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            var stopped: GateResult? = null
            val stop = Thread({ stopped = runBlocking { f.owner.stopControls() } }, "stop-controls").apply { isDaemon = true }
            stop.start()
            // Stop cleared the published outcome under platformLock; it then waits for the audio monitor.
            val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (f.owner.audioResult.value != null) {
                assertTrue("stop never entered platform cancellation", System.nanoTime() < end); Thread.sleep(1)
            }
            release.countDown()
            callback.join(5_000); stop.join(5_000)
            assertTerminated(callback, stop)
            terminated = true
            assertTrue("stop result=$stopped", stopped is GateResult.Stopped)
            assertEquals(0, f.profile.invoked)
            assertNull(f.owner.audioResult.value)
            f.profile.callback?.invoke() // late duplicate after Stop: closed, still no command
            assertEquals(0, f.profile.invoked)
            assertNull(f.owner.audioResult.value)
            val lateCallback = f.profile.callback
            // Authority is gone: a new request is refused before reaching the profile (nothing queued).
            assertTrue(f.owner.requestAudio(AudioOperation.CONNECT) is PlatformResult.Denied)
            assertSame(lateCallback, f.profile.callback)
            assertEquals(0, f.profile.invoked)
        } finally { if (terminated) f.owner.disposeFunctionalTest() }
    }
    private fun assertTerminated(vararg threads: Thread) {
        val alive = threads.filter { it.isAlive }
        if (alive.isEmpty()) return
        // android.jar test classpath has no java.lang.management: reach the host JVM reflectively.
        val deadlocked = runCatching {
            val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
            (Class.forName("java.lang.management.ThreadMXBean").getMethod("findMonitorDeadlockedThreads").invoke(bean) as LongArray?)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val names = Thread.getAllStackTraces().keys.associate { it.id to it.name }
        fail("not terminated within the finite timeout: " + alive.joinToString(" | ") {
            "${it.name}=${it.state} at ${it.stackTrace.take(8).joinToString(" < ")}"
        } + "; monitor deadlock=" + deadlocked.map { names[it] ?: "tid$it" })
    }
    @Test fun coldFallbackIsObservableDataAndDoesNotLaunchSettings() = runBlocking {
        val f = Fixture()
        try {
            f.grantAudio(); f.profile.result = PlatformResult.Denied(PlatformFailure.HIDDEN_API_UNAVAILABLE)
            assertEquals(PlatformResult.Queued, f.owner.requestAudio(AudioOperation.CONNECT))
            f.profile.callback?.invoke()
            assertTrue(f.owner.audioResult.value is PlatformResult.SettingsRequired)
            assertEquals(1, f.profile.invoked)
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        } finally { f.owner.disposeFunctionalTest() }
    }
    @Test fun resolverRejectsStaleEntryAndSetupAuthorityDoesNotFollowAnAdmittedLeaseAlone() = runBlocking {
        val f = Fixture(); var entry: AirPodsIngressSelection? = null
        try {
            f.facts.connected = true
            f.owner.selectPair(pair).getOrThrow()
            assertTrue(f.owner.beginFunctionalTest() is GateResult.Started)
            entry = AirPodsIngressResolver.publish(f.owner, { true }, {}, {})
            assertSame(f.owner, AirPodsIngressResolver.resolve())
            assertFalse(entry.authority.permits(f.owner.state.value.generation)) // no INFORMATION/basic RX
            AirPodsIngressResolver.detach(entry)
            assertFalse(entry.current())
            assertNotSame(f.owner, AirPodsIngressResolver.resolve())
        } finally { AirPodsIngressResolver.detach(entry); f.owner.disposeFunctionalTest() }
    }
}
