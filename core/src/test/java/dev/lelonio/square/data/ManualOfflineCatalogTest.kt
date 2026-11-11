package dev.lelonio.square.data

import dev.lelonio.square.download.DownloadExtras
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Prepared source only. Replaces the actual Catalog JNI dispatch, never loads native or Spotify. */
class ManualOfflineCatalogTest {
    private val original = Catalog.nativeRequest
    private val requests = mutableListOf<String>()
    private val root = Files.createTempDirectory("manual-catalog-").toFile()

    @Before fun setUp() {
        SpotifyNetworkPolicy.setManual(false)
        DownloadExtras.attach(root) { true }
        Catalog.nativeRequest = { operation, _ ->
            requests += operation
            when (operation) {
                "context", "tracks", "playlists" -> "[]"
                "lyrics" -> """{"lyrics":{"syncType":"LINE_SYNCED","lines":[{"startTimeMs":"0","words":"kept"}]}}"""
                "artist" -> """{"name":"Synthetic artist","biography":"kept biography"}"""
                "canvas" -> """{"url":"https://synthetic.invalid/canvas","isVideo":true}"""
                else -> "null"
            }
        }
    }

    @After fun tearDown() {
        Catalog.nativeRequest = original
        SpotifyNetworkPolicy.setManual(false)
        root.deleteRecursively()
    }

    @Test fun allCatalogueAdmissionsAreBlockedAndTurningOffAllowsFreshReads() = runBlocking {
        SpotifyNetworkPolicy.setManual(true)
        for (read in listOf<suspend () -> Unit>(
            { Catalog.contextTrackUris("spotify:playlist:synthetic"); Unit },
            { Catalog.tracks(listOf("spotify:track:synthetic")); Unit },
            { Catalog.playlists(); Unit },
        )) {
            try { read(); fail("Catalogue dispatched in manual offline") }
            catch (_: SpotifyNetworkPolicy.Offline) { }
        }
        assertNull(Catalog.lyrics("spotify:track:missing"))
        assertTrue(Catalog.artist("spotify:artist:missing").isFailure)
        assertNull(Catalog.canvas("spotify:track:missing").getOrNull())
        assertNull(Catalog.playlistCover("spotify:playlist:missing"))
        assertNull(Catalog.playlistName("spotify:playlist:missing"))
        assertTrue(requests.isEmpty())
        SpotifyNetworkPolicy.setManual(false)
        assertEquals(emptyList<String>(), Catalog.contextTrackUris("spotify:playlist:synthetic"))
        assertEquals(listOf("context"), requests)
    }

    @Test fun offlineExtrasUseDiskWithoutFirstTryingSpotifyOrAnUncachedCanvasUrl() = runBlocking {
        Catalog.lyrics("spotify:track:t")
        Catalog.artist("spotify:artist:a")
        Catalog.canvas("spotify:track:t")
        requests.clear()
        SpotifyNetworkPolicy.setManual(true)
        assertEquals("kept", Catalog.lyrics("spotify:track:t")!!.lines.single().text)
        assertEquals("kept biography", Catalog.artist("spotify:artist:a").getOrThrow().biography)
        assertNull(Catalog.canvas("spotify:track:t").getOrThrow())
        assertTrue(requests.isEmpty())
    }

    @Test fun responseRevokedInsideTheNativeSinkCannotBePublishedAfterOnOff() = runBlocking {
        Catalog.nativeRequest = { _, _ ->
            requests += "admitted"
            SpotifyNetworkPolicy.setManual(true)
            SpotifyNetworkPolicy.setManual(false)
            "[]"
        }
        try { Catalog.playlists(); fail("Revoked response escaped") }
        catch (_: SpotifyNetworkPolicy.Offline) { }
        assertEquals(listOf("admitted"), requests)
    }
}
