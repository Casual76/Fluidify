// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import android.Manifest
import android.app.Application
import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.*
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsGestureContextInvalidationTest {
    @Test fun gestureDoesNotFollowAReplacementAfterTheControllerVerifiedItsTarget() {
        val app: Application = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        app.getSystemService(android.media.AudioManager::class.java).mode =
            android.media.AudioManager.MODE_NORMAL
        val account = dev.lelonio.square.playback.PlaybackService.mediaAccountEpoch.toString()
        val first = MediaTarget("fluidify", "first", 1, "airpods", account)
        val second = first.copy(sessionId = "second", sessionGeneration = 2)
        var selected = first
        val dispatched = mutableListOf<MediaTarget>()
        val local = object : LocalAirPodsMediaBridge {
            override fun read(pair: PairAddress) =
                MediaCandidate(selected, Playback.PLAYING, null, true, true, true, false)
            override fun observe(changed: () -> Unit) = Closeable {}
            override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?) = false
            override fun gesture(target: MediaTarget, action: AirPodsMediaGesture): Boolean {
                dispatched += target
                return true
            }
        }
        val pair = PairAddress("AA:BB:CC:DD:EE:08")
        val port = AndroidAirPodsMediaRuntime(app, local)
        try {
            port.start(AutomationSession(pair.value, 1), pair, HostSettings(automationsEnabled = true))
            port.setUiVisible(true)
            val verified = requireNotNull(port.gestureContext())
            assertEquals(first, verified)
            selected = second
            val accepted = port.gesture(AirPodsMediaGesture.NEXT, verified)
            assertEquals("A replacement target must receive no gesture", emptyList<MediaTarget>(), dispatched)
            assertFalse(accepted)
            val replacement = requireNotNull(port.gestureContext())
            assertEquals(second, replacement)
            assertTrue(port.gesture(AirPodsMediaGesture.NEXT, replacement))
            assertEquals(listOf(second), dispatched)
        } finally {
            port.stop()
        }
    }

    @Test fun environmentCallbacksAndRevocationInvalidateTheAnyThreadContextRevision() {
        val app: Application = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        var signal: (() -> Unit)? = null
        val local = object : LocalAirPodsMediaBridge {
            override fun read(pair: PairAddress): MediaCandidate? = null
            override fun observe(changed: () -> Unit): Closeable { signal = changed; return Closeable { signal = null } }
            override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?) = false
            override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = false
        }
        val environment = AndroidMediaEnvironment(app, local)
        val port = AndroidAirPodsMediaRuntime(app, environment = environment)
        val pair = PairAddress("AA:BB:CC:DD:EE:08")
        try {
            port.start(AutomationSession(pair.value, 1), pair, HostSettings())
            assertNotNull(signal)
            val before = port.gestureContextRevision
            signal!!.invoke()
            assertTrue(port.gestureContextRevision > before)
            val after = port.gestureContextRevision
            port.revoke()
            assertTrue(port.gestureContextRevision > after)
        } finally { port.stop() }
        assertNull(signal)
    }
}
