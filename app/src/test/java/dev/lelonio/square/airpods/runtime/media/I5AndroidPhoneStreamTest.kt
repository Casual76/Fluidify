// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import android.Manifest
import android.app.Application
import android.media.AudioManager
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real Android adapter + shadow platform. Prepared, not executed; no hardware evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [26, 35])
class I5AndroidPhoneStreamTest {
    @Test fun phoneStreamNeedsPairAndPermissionButNotNlsOrMediaSession() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val audio = app.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
        val env = AndroidMediaEnvironment(app)
        assertNull(env.readPhoneVolume())
        env.pair = PairAddress("00:11:22:33:44:55")
        val observed = env.readPhoneVolume()!!
        val requested = if (observed.volume.current == 0) 1 else 0
        assertTrue(env.writePhoneVolume(observed, requested))
        assertEquals(requested, env.readPhoneVolume()!!.volume.current)
        env.pair = null
        assertNull(env.readPhoneVolume()); assertFalse(env.writePhoneVolume(observed, requested))
    }
    @Test fun pairModeAndRangeChangesRejectWriteWithoutChangingStream() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val audio = app.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
        val env = AndroidMediaEnvironment(app)
        env.pair = PairAddress("00:11:22:33:44:55")
        val observed = env.readPhoneVolume()!!
        assertFalse(env.writePhoneVolume(observed, -1))
        assertFalse(env.writePhoneVolume(observed, observed.volume.maximum + 1))
        env.pair = PairAddress("00:11:22:33:44:66")
        assertFalse(env.writePhoneVolume(observed, 0))
        env.pair = PairAddress("00:11:22:33:44:55")
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        assertNull(env.readPhoneVolume()); assertFalse(env.writePhoneVolume(observed, 0))
        assertEquals(observed.volume.current, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    @Test @Config(sdk = [35]) fun permissionRevocationRejectsPreviouslyReadSnapshot() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val env = AndroidMediaEnvironment(app)
        env.pair = PairAddress("00:11:22:33:44:55")
        val observed = env.readPhoneVolume()!!
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertNull(env.readPhoneVolume()); assertFalse(env.writePhoneVolume(observed, 0))
    }
}
