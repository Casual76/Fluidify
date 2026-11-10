// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import android.Manifest
import android.app.Application
import android.media.AudioManager
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
 * shadow platform: a revocation that lands while start() is registering the observers.
 *
 * The interleaving is forced through the port's own authority callback, which the core consults
 * during start(): the callback revokes the port, as a stopping thread would at that instant.
 * "Observer registered" is read from the environment's private `changed` field, set by observe()
 * and cleared when the registration is closed.
 *
 * Both tests run AndroidMediaEnvironment.observe on Robolectric. If the control fails inside
 * start(), the rig is at fault and the other test proves nothing either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AndroidAirPodsMediaLateRevocationTest {
    private val pair = PairAddress("00:11:22:33:44:55")
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun prepare() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
    }
    private fun environment(port: AndroidAirPodsMediaRuntime): AndroidMediaEnvironment =
        ReflectionHelpers.getField(port, "environment")
    private fun observing(port: AndroidAirPodsMediaRuntime): Boolean =
        ReflectionHelpers.getField<Any?>(environment(port), "changed") != null

    @Test fun control_startRegistersTheObserverAndStopReleasesIt() {
        prepare()
        var asked = 0
        val port = AndroidAirPodsMediaRuntime(app, authority = { asked++; true })
        assertFalse("constructing must register nothing", observing(port))
        port.start(AutomationSession(pair.value, 1), pair, HostSettings())
        assertTrue("CONTROL FAILED: start() did not register the observer this rig reads", observing(port))
        assertTrue("CONTROL FAILED: start() never consulted the authority, the other test cannot place its revocation", asked > 0)
        assertEquals(pair, environment(port).pair)
        port.stop()
        assertFalse(observing(port))
        assertNull(environment(port).pair)
    }

    @Test fun revocationLandingInsideStartLeavesNoObserverBehind() {
        prepare()
        var asked = 0
        lateinit var port: AndroidAirPodsMediaRuntime
        port = AndroidAirPodsMediaRuntime(app, authority = { if (asked++ == 0) port.revoke(); true })
        port.start(AutomationSession(pair.value, 1), pair, HostSettings())
        assertTrue("RIG: the revocation never landed inside start()", asked > 0)
        // No stop() was called by the owner: start() itself must have undone what it registered.
        assertFalse("OBSERVER LEFT BEHIND by a start() that lost to a revocation", observing(port))
        assertNull(environment(port).pair)
        assertNull(port.volume.value)
        port.setUiVisible(true)
        port.updateSettings(HostSettings())
        assertFalse("a revoked port registered again", observing(port))
        assertFalse(port.setUserVolume(0))
        port.stop() // the owner's queued teardown still follows and must be harmless
        assertFalse(observing(port))
    }
}
