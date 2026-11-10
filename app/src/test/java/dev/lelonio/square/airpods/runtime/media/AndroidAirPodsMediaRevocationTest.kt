// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import android.Manifest
import android.app.Application
import android.media.AudioManager
import android.os.Looper
import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.AutomationSession
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * PREPARED, NOT EXECUTED AND NOT COMPILED. Real AndroidAirPodsMediaRuntime and environment on the
 * shadow platform; no media session, route, NLS or hardware evidence. The phone stream volume is
 * the one effect this rig can observe without any of those.
 *
 * The first test never registers an observer. The second starts the real port, so it also runs
 * AndroidMediaEnvironment.observe on Robolectric, which no earlier test did: if it fails inside
 * start() the rig is at fault and the revocation is neither confirmed nor refuted by it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AndroidAirPodsMediaRevocationTest {
    private val pair = PairAddress("00:11:22:33:44:55")
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val audio: AudioManager get() = app.getSystemService(AudioManager::class.java)

    private fun port(): AndroidAirPodsMediaRuntime {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        audio.mode = AudioManager.MODE_NORMAL
        return AndroidAirPodsMediaRuntime(app)
    }
    private fun adoptedPair(port: AndroidAirPodsMediaRuntime): PairAddress? =
        ReflectionHelpers.getField<AndroidMediaEnvironment>(port, "environment").pair
    private fun music() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    /** Runs [block] on a thread that is not the port's serial domain and returns what it threw. */
    private fun offMain(block: () -> Unit): Throwable? {
        var failure: Throwable? = null
        val worker = Thread({ failure = runCatching(block).exceptionOrNull() }, "media-revocation-worker")
        worker.start()
        worker.join(5_000)
        assertFalse("RIG: worker still running", worker.isAlive)
        return failure
    }

    @Test fun revocationNeedsNoSerialDomainAndAStartThatLostTheRaceStaysInert() {
        val port = port()
        assertTrue("RIG: the worker must be outside the serial domain", offMain { port.stop() } is IllegalStateException)
        assertNull("revoke() must not need the serial domain", offMain { port.revoke(); port.revoke() })
        val before = music()
        val wanted = if (before == 0) 1 else 0
        // Stop won the race: the bootstrap still calls start() on main afterwards.
        port.start(AutomationSession(pair.value, 1), pair, HostSettings())
        port.setUiVisible(true)
        port.updateSettings(HostSettings())
        assertNull("a revoked port adopted a pair", adoptedPair(port))
        assertFalse(port.setUserVolume(wanted))
        assertFalse(port.setUserVolume(wanted) { true })
        assertFalse(port.gesture(AirPodsMediaGesture.PLAY))
        assertNull(port.gestureContext())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, music())
        assertNull(port.volume.value)
        port.stop()
        assertNull(offMain { port.revoke() })
    }

    @Test fun control_startedPortSetsTheVolume_thenRevocationFromAnotherThreadEndsItAtOnce() {
        val port = port()
        port.start(AutomationSession(pair.value, 1), pair, HostSettings())
        port.setUiVisible(true)
        val first = if (music() == 0) 1 else 0
        assertTrue("CONTROL FAILED: a started, visible port refused the manual volume", port.setUserVolume(first))
        assertEquals(first, music())
        assertNotNull("CONTROL FAILED: no phone volume published", port.volume.value)
        assertEquals(pair, adoptedPair(port))

        assertNull(offMain { port.revoke() })
        // Before any handler turn: the stopping thread does not wait for main.
        assertNull("volume must read unavailable at once", port.volume.value)
        val second = if (first == 0) 1 else 0
        assertFalse(port.setUserVolume(second))
        assertFalse(port.setUserVolume(second) { true })
        assertFalse(port.gesture(AirPodsMediaGesture.PLAY))
        // Whatever the observers queued runs revoked.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(first, music())
        assertNull(port.volume.value)

        port.stop()
        assertNull(adoptedPair(port))
        assertNull(port.volume.value)
    }
}
