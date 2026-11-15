package dev.lelonio.square.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class I11RemoteVolumeTest {
    @Before fun allowNetwork() { SpotifyNetworkPolicy.setManual(false) }
    @After fun restoreNetwork() { SpotifyNetworkPolicy.setManual(false) }

    @Test fun remoteRejectionIsFalseAndDoesNotChangeConfirmedDevices() {
        val devices = RemoteConnect.devices.value
        assertFalse(i11RemoteVolume("speaker-a", 300) { _, _ -> throw IOException("synthetic refusal") })
        assertSame(devices, RemoteConnect.devices.value)
    }
    @Test fun successfulDispatchKeepsTheRequestedDestinationAndClampsTheLevel() {
        var destination: String? = null
        var raw = -1
        assertTrue(i11RemoteVolume("speaker-b", 100_000) { id, level -> destination = id; raw = level })
        assertEquals("speaker-b", destination)
        assertEquals(65535, raw)
    }
    @Test fun manualOfflineDoesNotDispatchAndReturnsFalse() {
        SpotifyNetworkPolicy.setManual(true)
        var sent = false
        assertFalse(i11RemoteVolume("speaker", 1) { _, _ -> sent = true })
        assertFalse(sent)
    }
    @Test fun revocationDuringDispatchCannotReturnSuccess() {
        assertFalse(i11RemoteVolume("speaker", 1) { _, _ -> SpotifyNetworkPolicy.setManual(true) })
    }
    @Test fun cancellationIsNeverConvertedIntoSuccess() {
        try {
            i11RemoteVolume("speaker", 1) { _, _ -> throw CancellationException("caller stopped") }
            fail("cancellation must propagate")
        } catch (expected: CancellationException) { assertEquals("caller stopped", expected.message) }
    }

    @Test fun manualOnOffCannotReadmitTheVolumeGestureThatPredatesTheProhibition() {
        val original = SpotifyNetworkPolicy.capture()
        SpotifyNetworkPolicy.setManual(true)
        SpotifyNetworkPolicy.setManual(false)
        var sends = 0
        assertFalse(i11AdmittedRemoteVolume("speaker", 30, original, null) { _, _ -> sends++ })
        assertEquals(0, sends)
        assertTrue(i11AdmittedRemoteVolume("speaker", 30, SpotifyNetworkPolicy.capture(), null) { _, _ -> sends++ })
        assertEquals("only a fresh explicit gesture can dispatch", 1, sends)
    }
}
