package dev.lelonio.square.io

import dev.lelonio.square.data.SpotifyNetworkPolicy
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** PREPARED ONLY. File bytes and original-lease compatibility entrance; no provider. */
class ManualOfflineMediaFetchTest {
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }

    @Test fun manualOfflineReadsRealLocalBytesAndRetainsTheSizeLimit() {
        val file = Files.createTempFile("manual-media-file", ".bin").toFile()
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            SpotifyNetworkPolicy.setManual(true)
            assertArrayEquals(file.readBytes(), HttpFetch.bytes(file.toURI().toString(), 4))
            assertNull(HttpFetch.bytes(file.toURI().toString(), 3))
        } finally { file.delete() }
    }

    @Test fun legacyFetchHookCannotBorrowANewPermitInsideAnOldTransfer() {
        SpotifyNetworkPolicy.setManual(false)
        val original = SpotifyNetworkPolicy.capture()
        SpotifyNetworkPolicy.setManual(true)
        SpotifyNetworkPolicy.setManual(false)
        var called = false
        assertThrows(SpotifyNetworkPolicy.Offline::class.java) {
            HttpFetch.withPermit(original) { called = true }
        }
        assertFalse(called)
        SpotifyNetworkPolicy.capture().admit { called = true }
        assertTrue(called)
    }
}
