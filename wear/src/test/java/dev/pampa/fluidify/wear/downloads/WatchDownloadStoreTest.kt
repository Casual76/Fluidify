package dev.pampa.fluidify.wear.downloads

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.fluidify.wear.WearApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The watch's download store, read and written the way the native store lays it out. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class WatchDownloadStoreTest {

    private lateinit var store: WatchDownloadStore
    private val track = "spotify:track:5sWHDYs0csV6RS48xBl0tH"
    private val other = "spotify:track:0000000000000000000001"

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<WearApp>()
        File(app.filesDir, "downloads").deleteRecursively()
        store = WatchDownloadStore(app)
    }

    private fun sidecar(uri: String) =
        """{"v":1,"uri":"$uri","trackId":"x","format":"OGG_VORBIS_160","name":"Song","durationMs":200000,""" +
            """"artists":[{"name":"Artist","uri":"spotify:artist:a"}],"album":"Album","coverUrl":"https://i.scdn.co/image/ab"}"""

    private fun arrive(uri: String) {
        val part = store.partFile(uri)!!
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(10))
        assertTrue(store.adopt(uri, sidecar(uri)))
    }

    @Test
    fun pathsAreTheNativeStoresShards() {
        // native/src/downloads.rs: <root>/audio/<xx>/<rest>, <root>/meta/<xx>/<rest>.json
        assertTrue(store.audioFile(track)!!.path.endsWith("downloads/audio/b3/9fe8081e1f4c54be38e8d6f9f12bb9"))
        assertTrue(store.metaFile(track)!!.path.endsWith("downloads/meta/b3/9fe8081e1f4c54be38e8d6f9f12bb9.json"))
        assertNull(store.audioFile("spotify:album:5sWHDYs0csV6RS48xBl0tH"))
    }

    @Test
    fun aTrackIsThereOnlyOnceItsSidecarIs() {
        store.keep("spotify:playlist:p", "Run")
        store.setTracks("spotify:playlist:p", listOf(track, other))
        assertEquals(listOf(track, other), store.pending())
        store.partFile(track)!!.apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        assertFalse("a .part is not a download", store.has(track))
        assertTrue(store.adopt(track, sidecar(track)))
        assertTrue(store.has(track))
        assertEquals(listOf(other), store.pending())
        assertEquals(160, store.kbpsOf(store.sidecar(track)))
    }

    @Test
    fun droppingAPlaylistKeepsTracksAnotherOneStillWants() {
        store.keep("spotify:playlist:a", "A")
        store.keep("spotify:album:b", "B")
        store.setTracks("spotify:playlist:a", listOf(track, other))
        store.setTracks("spotify:album:b", listOf(track))
        arrive(track)
        arrive(other)
        store.drop("spotify:playlist:a")
        assertTrue("still in B", store.has(track))
        assertFalse("only A had it", store.has(other))
    }

    @Test
    fun aTrackTakenOutOfThePlaylistLeavesTheDisk() {
        store.keep("spotify:playlist:a", "A")
        store.setTracks("spotify:playlist:a", listOf(track, other))
        arrive(track)
        arrive(other)
        store.setTracks("spotify:playlist:a", listOf(track))
        assertFalse(store.has(other))
    }

    @Test
    fun offlineTracksComeFromTheSidecars() {
        store.keep("spotify:playlist:a", "A")
        store.setTracks("spotify:playlist:a", listOf(track, other))
        arrive(track)
        val tracks = store.offlineTracks("spotify:playlist:a")
        assertEquals(1, tracks.size)
        assertEquals("Song", tracks[0].name)
        assertEquals("Artist", tracks[0].artist)
        assertEquals("spotify:artist:a", tracks[0].artistUri)
        assertEquals(200_000L, tracks[0].durationMs)
    }

    @Test
    fun theListSurvivesARestart() {
        store.keep("spotify:playlist:a", "A")
        store.setTracks("spotify:playlist:a", listOf(track))
        val again = WatchDownloadStore(ApplicationProvider.getApplicationContext())
        assertEquals(listOf(track), again.owners.value.single().tracks)
    }

    @Test
    fun formatNamesToKbps() {
        assertEquals(320, WatchDownloadStore.formatKbps("OGG_VORBIS_320"))
        assertEquals(96, WatchDownloadStore.formatKbps("OGG_VORBIS_96"))
        assertNull(WatchDownloadStore.formatKbps("MP3_160_ENC"))
        assertNull(WatchDownloadStore.formatKbps(null))
    }
}
