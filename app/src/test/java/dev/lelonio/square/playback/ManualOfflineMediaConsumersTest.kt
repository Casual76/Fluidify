@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSpec
import dev.lelonio.square.data.*
import dev.lelonio.square.download.DownloadExtras
import dev.lelonio.square.backend.spotify.SpotifyVideo
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** PREPARED, NOT EXECUTED. Real Media3 metadata, HTTP, manifest parser and DataSource paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ManualOfflineMediaConsumersTest.Host::class)
class ManualOfflineMediaConsumersTest {
    class Host : Application(), CoreHost { override val qualityIsAutomatic = false }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }
    private val image = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aRxoAAAAASUVORK5CYII=")
    private fun player(q: PlayQueue) = LibrespotPlayer(RuntimeEnvironment.getApplication(), Looper.getMainLooper(), q,
        onSpeedAndPitch = { _, _ -> }, fadeOutThen = { it() }, fadeIn = {}, onPlaybackActive = {}, onDuckReverb = {},
        loadQueueCommand = { _, _, _, _, _, _, _ -> }, refreshRemoteState = {}, airPodsPauseCommand = {}, airPodsPlayCommand = {})
        .also { it.shutdownEngineOnRelease = false }
    private fun state(p: LibrespotPlayer) = LibrespotPlayer::class.java.getDeclaredMethod("getState").apply { isAccessible = true }.invoke(p)
    private fun waitFor(predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate()) { check(System.nanoTime() < until); shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
    }
    @Suppress("UNCHECKED_CAST")
    private fun covers(p: LibrespotPlayer) = LibrespotPlayer::class.java.getDeclaredField("covers").apply { isAccessible = true }.get(p) as Map<String, ByteArray>
    private fun track(url: String) = PlayQueue.Track("spotify:track:synthetic", "Downloaded", "Synthetic", durationMs = 180000, artworkUri = Uri.parse(url))

    @Test fun localCoverIsEmbeddedWhileManualAndRemoteArtworkUriIsWithdrawnWhenMissing() = runBlocking {
        val root = Files.createTempDirectory("offline-media-cover").toFile()
        val fetchField = DownloadExtras::class.java.getDeclaredField("fetch").apply { isAccessible = true }
        val original = fetchField.get(DownloadExtras)
        try { SyntheticMediaPeer(image).use { peer ->
            DownloadExtras.attach(root) { true }
            val seed: (String, java.io.File, Long) -> Unit = { _, file, _ -> file.writeBytes(image) }
            fetchField.set(DownloadExtras, seed)
            SpotifyNetworkPolicy.setManual(false)
            assertNotNull(DownloadExtras.keep(peer.url, "art"))
            val q = PlayQueue(); q.replace(listOf(track(peer.url)), 0)
            val p = player(q)
            try {
                SpotifyNetworkPolicy.setManual(true); state(p)
                waitFor { covers(p).isNotEmpty() }
                assertArrayEquals(image, p.currentMediaItem!!.mediaMetadata.artworkData)
                assertEquals("file", p.currentMediaItem!!.mediaMetadata.artworkUri?.scheme)
                assertEquals(0, peer.requests.get())
            } finally { p.release() }
            val empty = PlayQueue(); empty.replace(listOf(track(peer.url + "/missing")), 0)
            val missing = player(empty)
            try { state(missing); assertNull(missing.currentMediaItem!!.mediaMetadata.artworkUri); assertEquals(0, peer.requests.get()) }
            finally { missing.release() }
        }
        } finally { fetchField.set(DownloadExtras, original); root.deleteRecursively() }
    }

    @Test fun onlineCoverWorksButOldBodyCannotPublishAfterRapidToggle() {
        val root = Files.createTempDirectory("offline-media-late").toFile(); DownloadExtras.attach(root) { true }
        SyntheticMediaPeer(image, held = true).use { peer ->
            SpotifyNetworkPolicy.setManual(false)
            val q = PlayQueue(); q.replace(listOf(track(peer.url)), 0); val p = player(q)
            try {
                state(p); assertTrue(peer.received.await(5, TimeUnit.SECONDS))
                SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
                shadowOf(Looper.getMainLooper()).idle(); peer.release.countDown()
                val loading = LibrespotPlayer::class.java.getDeclaredField("coverLoading").apply { isAccessible = true }
                waitFor { loading.get(p) == null }
                assertTrue(covers(p).isEmpty()); assertEquals(1, peer.requests.get())
                state(p); waitFor { covers(p).isNotEmpty() }
                assertArrayEquals(image, p.currentMediaItem!!.mediaMetadata.artworkData)
                assertEquals(2, peer.requests.get())
            } finally { p.release() }
        }
        root.deleteRecursively()
    }

    @Test fun localChoiceSurvivesAQueuedRemoteRetirementAndFreshOnlineProjectionStillWorks() {
        val q = PlayQueue(); val p = player(q)
        val remote = RemotePlayback("synthetic-device", "Device", "spotify:track:remote", "Remote", "Artist", "Album", "", "", 0, 1000, true, false, false, false)
        try {
            SpotifyNetworkPolicy.setManual(false); p.showRemote(remote); assertTrue(p.isRemotePlayback)
            SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
            assertFalse(p.isRemotePlayback)
            p.setMediaItems(listOf(CatalogTrack("spotify:track:local", "Local", "Artist", durationMs = 1000).toQueueItem()), 0, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(p.isRemotePlayback); assertEquals("spotify:track:local", p.currentMediaItem?.mediaId)
            p.showRemote(remote); shadowOf(Looper.getMainLooper()).idle(); assertTrue(p.isRemotePlayback)
            p.setMediaItems(listOf(MediaItem.EMPTY), 0, 0)
            assertTrue("An invalid local selection must not take over remote ownership", p.isRemotePlayback)
        } finally { p.release() }
    }

    @Test fun actualMediaSourceFactoryAllowsDiskAndFencesRemoteRetryAndDrmBody() {
        val context = RuntimeEnvironment.getApplication()
        val file = Files.createTempFile("offline-canvas", ".mp4").toFile().apply { writeBytes(image) }
        SpotifyNetworkPolicy.setManual(true)
        val local = SpotifyMediaDataSource.factory(context).createDataSource()
        try { assertEquals(image.size.toLong(), local.open(DataSpec(Uri.fromFile(file)))); assertEquals(image.size, local.read(ByteArray(image.size), 0, image.size)) }
        finally { local.close(); file.delete() }
        SyntheticMediaPeer(image).use { peer ->
            SpotifyNetworkPolicy.setManual(false); val permit = SpotifyNetworkPolicy.capture()
            val owner = java.util.concurrent.atomic.AtomicBoolean(true)
            val remote = SpotifyMediaDataSource.factory(context, permit) { owner.get() }.createDataSource()
            val spec = DataSpec.Builder().setUri(peer.url).setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody("synthetic-license".toByteArray()).build()
            try { remote.open(spec); assertEquals(image.size, remote.read(ByteArray(image.size), 0, image.size)); assertTrue(peer.lastRequest.startsWith("POST")) }
            finally { remote.close() }
            owner.set(false)
            assertThrows(java.io.IOException::class.java) { remote.open(spec) }
            assertEquals(1, peer.requests.get())
            val freshOwner = SpotifyMediaDataSource.factory(context, permit).createDataSource()
            val range = DataSpec.Builder().setUri(peer.url).setPosition(2).setLength(2).build()
            try {
                assertEquals(2L, freshOwner.open(range))
                val bytes = ByteArray(2)
                assertEquals(2, freshOwner.read(bytes, 0, 2))
                assertArrayEquals(image.copyOfRange(2, 4), bytes)
                assertTrue(peer.lastRequest.contains("Range: bytes=2-3", ignoreCase = true))
            } finally { freshOwner.close() }
            SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
            assertThrows(SpotifyNetworkPolicy.Offline::class.java) { freshOwner.open(spec) }
            assertEquals(2, peer.requests.get())
        }
    }

    @Test fun manifestClientUsesOriginalPermitAcrossAsyncDispatchAndFreshOnlineParsingWorks() = runBlocking {
        SyntheticMediaPeer("{\"end_time_millis\":1000}".toByteArray()).use { peer ->
            SpotifyNetworkPolicy.setManual(false); val old = SpotifyNetworkPolicy.capture()
            SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
            assertTrue(runCatching { SpotifyVideo.manifestAt(peer.url, "synthetic", old) }.exceptionOrNull() is SpotifyNetworkPolicy.Offline)
            assertEquals(0, peer.requests.get())
            assertEquals(1000L, SpotifyVideo.manifestAt(peer.url, "synthetic", SpotifyNetworkPolicy.capture()).endTimeMillis)
            assertEquals(1, peer.requests.get())
        }
    }
}
