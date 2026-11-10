package dev.lelonio.square.download

import dev.lelonio.square.data.SpotifyNetworkPolicy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManualOfflineExtrasTest {
    @Test fun completedFilesStayReadableAndNewCdnTransfersWaitUntilOnline() = runBlocking {
        val root = Files.createTempDirectory("manual-extras-").toFile()
        val original = DownloadExtras.fetch
        var requests = 0
        try {
            DownloadExtras.attach(root) { true }
            DownloadExtras.fetch = { _, target, _ -> requests++; target.writeText("synthetic bytes") }
            SpotifyNetworkPolicy.setManual(false)
            val kept = DownloadExtras.keep("https://synthetic.invalid/kept", "video")!!
            SpotifyNetworkPolicy.setManual(true)
            assertEquals(kept, DownloadExtras.keep("https://synthetic.invalid/kept", "video"))
            assertNull(DownloadExtras.keep("https://synthetic.invalid/new", "video"))
            assertEquals(1, requests)
            assertEquals("synthetic bytes", kept.readText())
            SpotifyNetworkPolicy.setManual(false)
            assertNotNull(DownloadExtras.keep("https://synthetic.invalid/new", "video"))
            assertEquals(2, requests)
        } finally {
            DownloadExtras.fetch = original
            SpotifyNetworkPolicy.setManual(false)
            root.deleteRecursively()
        }
    }
}
