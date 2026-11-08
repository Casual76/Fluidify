// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.content.Intent
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowProcess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** PREPARED only. Fake clocks and barriers exercise production guard/lease code. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsFunctionalOwnerTest {
    @Before fun processIdentity() { ShadowProcess.setPid(41011) }
    @Test fun foregroundPermitExpiresWithoutBackgroundRearming() {
        var now = 1L; val p = AirPodsFunctionalTestPermit.issue { now }
        assertTrue(p.allowed(true)); assertFalse(p.allowed(false))
        now += AirPodsFunctionalTestPermit.MAX_WINDOW
        assertFalse(p.allowed(true)); assertFalse(p.armBackground(600_000, true))
    }
    @Test fun backgroundIsExplicitTenToFifteenMinutesAndCannotExtend() {
        var now = 1L; val p = AirPodsFunctionalTestPermit.issue { now }
        assertFalse(p.armBackground(90_000, true)); assertFalse(p.armBackground(900_001, true))
        assertFalse(p.armBackground(600_000, false)); assertFalse(p.allowed(false))
        assertTrue(p.armBackground(600_000, true))
        now += 91_000 // silence longer than the old 90 s POC does not end the window
        assertTrue(p.allowed(false))
        assertFalse(p.armBackground(900_000, true))
        now = 600_001; assertFalse(p.allowed(false)); assertFalse(p.allowed(true))
    }
    @Test fun revokeAtQueuedWriteBarrierNeverCallsTransport() {
        val p = AirPodsFunctionalTestPermit.issue { 1L }; val writes = AtomicInteger()
        val raw = object : AacpTransport {
            override fun connect() {}; override fun readSdu(): ByteArray? = null
            override fun write(packet: ByteArray) { writes.incrementAndGet() }; override fun close() {}
        }
        val guarded = GuardedTransport(raw) { p.allowed(true) }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var rejected = false
        val worker = thread { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); rejected = runCatching { guarded.write(byteArrayOf(1)) }.isFailure }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); p.revoke(); release.countDown(); worker.join(2000)
        assertFalse(worker.isAlive); assertTrue(rejected); assertEquals(0, writes.get())
    }
    @Test fun expiryWhileQueuedDeniesWriteEvenBeforeExpiryCleanupRuns() {
        var now = 1L; val p = AirPodsFunctionalTestPermit.issue { now }; var writes = 0
        val raw = object : AacpTransport {
            override fun connect() {}; override fun readSdu(): ByteArray? = null
            override fun write(packet: ByteArray) { writes++ }; override fun close() {}
        }
        val queued = { GuardedTransport(raw) { p.allowed(true) }.write(byteArrayOf(1)) }
        now += AirPodsFunctionalTestPermit.MAX_WINDOW
        assertTrue(runCatching(queued).isFailure); assertEquals(0, writes)
    }
    @Test fun factoryInFlightRetainsExclusionUntilClosedAfterRevocation() {
        val owners = AirPodsTransportOwnership(); val lease = owners.reserve(AirPodsTransportOwnership.Owner.DIAGNOSTIC)!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closes = AtomicInteger()
        val raw = object : AacpTransport {
            override fun connect() {}; override fun readSdu(): ByteArray? = null
            override fun write(packet: ByteArray) {}; override fun close() { closes.incrementAndGet() }
        }
        val worker = thread { runCatching { lease.construct { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); raw } } }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); lease.close()
        assertNull(owners.reserve(AirPodsTransportOwnership.Owner.PRODUCTION))
        release.countDown(); worker.join(2000)
        assertFalse(worker.isAlive); assertTrue(lease.released); assertEquals(1, closes.get())
    }
    private fun controller(): AirPodsAndroidController {
        val scratch = AirPodsRuntimeStore(object : RuntimePersistence {
            var bytes: ByteArray? = null
            override fun read() = bytes
            override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes; return true }
        })
        return AirPodsAndroidController(RuntimeEnvironment.getApplication(), MutableStateFlow(true), { false }, null,
            AirPodsFunctionalTestPermit.issue { 1L }, scratch)
    }
    @Test fun staleRegistryReleaseCannotClearReplacementController() = runBlocking {
        val owners = AirPodsTransportOwnership(); val first = controller(); val second = controller()
        val a = owners.reserve(AirPodsTransportOwnership.Owner.DIAGNOSTIC)!!
        assertTrue(AirPodsServiceOwnerRegistry.claim(first, a)); a.close(); AirPodsServiceOwnerRegistry.release(first, a)
        val b = owners.reserve(AirPodsTransportOwnership.Owner.PRODUCTION)!!
        try {
            assertTrue(AirPodsServiceOwnerRegistry.claim(second, b))
            AirPodsServiceOwnerRegistry.release(first, a)
            assertSame(second, AirPodsServiceOwnerRegistry.resolve())
        } finally { b.close(); AirPodsServiceOwnerRegistry.release(second, b); first.disposeFunctionalTest(); second.disposeFunctionalTest() }
    }
    @Test fun debugAndProductionContendForOneLeaseBeforeAnyFactory() {
        val owners = AirPodsTransportOwnership()
        val test = owners.reserve(AirPodsTransportOwnership.Owner.DIAGNOSTIC)!!
        assertNull(owners.reserve(AirPodsTransportOwnership.Owner.PRODUCTION)); test.close()
        val production = owners.reserve(AirPodsTransportOwnership.Owner.PRODUCTION)!!
        assertNull(owners.reserve(AirPodsTransportOwnership.Owner.DIAGNOSTIC)); production.close()
    }
    @Test fun scratchDisposalUnregistersReceiverAndDoesNotWriteProductVerification() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val before = shadowOf(app).registeredReceivers.size
        val c = controller()
        assertEquals(before + 1, shadowOf(app).registeredReceivers.size)
        assertFalse(c.runtime.value.productConformance)
        assertNull(c.store.snapshot().getOrThrow().selectedPair)
        c.disposeFunctionalTest()
        assertEquals(before, shadowOf(app).registeredReceivers.size)
        assertNull(AirPodsAndroidRuntime.get())
    }
    @Test fun forgedIntentCannotIssuePermitOrStartTestOwner() {
        val forged = Intent().putExtra("testEntitlement", true).putExtra("productConformance", true).putExtra("backgroundMinutes", 15)
        val activity = Robolectric.buildActivity(AirPodsDiagnosticActivity::class.java, forged).create().start().resume()
        try { assertNull(AirPodsFunctionalHarness.existing()); assertNull(AirPodsAndroidRuntime.get()) }
        finally { activity.pause().stop().destroy() }
    }
}
