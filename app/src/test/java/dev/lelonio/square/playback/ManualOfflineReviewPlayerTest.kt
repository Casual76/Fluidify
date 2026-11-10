@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import android.app.Application
import android.net.Uri
import android.os.Looper
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.RemotePlayback
import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.download.DownloadExtras
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ManualOfflineReviewPlayerTest.Host::class)
class ManualOfflineReviewPlayerTest {
    class Host : Application(), CoreHost { override val qualityIsAutomatic = false }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }

    private fun player(queue: PlayQueue) = LibrespotPlayer(RuntimeEnvironment.getApplication(), Looper.getMainLooper(), queue,
        onSpeedAndPitch = { _, _ -> }, fadeOutThen = { it() }, fadeIn = {},
        onPlaybackActive = {}, onDuckReverb = {}, loadQueueCommand = { _, _, _, _, _, _, _ -> },
        refreshRemoteState = {}, airPodsPauseCommand = {}, airPodsPlayCommand = {})
        .also { it.shutdownEngineOnRelease = false }

    @Test fun restoredMetadataWithoutLocalCoverMustNotOpenNetworkWhileManualOffline() {
        val root = Files.createTempDirectory("critic-b-cover-").toFile()
        DownloadExtras.attach(root) { true }
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val received = CountDownLatch(1)
        val peer = thread(isDaemon = true, name = "critic-b-cover-peer") {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (!input.readLine().isNullOrEmpty()) { }
                    requests.incrementAndGet(); received.countDown()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\ntest".toByteArray())
                }
            } catch (_: java.net.SocketException) { }
        }
        val queue = PlayQueue()
        val p = player(queue)
        try {
            SpotifyNetworkPolicy.setManual(true)
            queue.replace(listOf(PlayQueue.Track("spotify:track:synthetic", "Downloaded song", "Synthetic artist",
                durationMs = 180000, artworkUri = Uri.parse("http://127.0.0.1:${server.localPort}/not-cached-cover"))), 0)
            // Exercise the same state builder used by Media3; getState is protected.
            LibrespotPlayer::class.java.getDeclaredMethod("getState").apply { isAccessible = true }.invoke(p)
            received.await(2, TimeUnit.SECONDS)
            println("CRITIC_B cover requestsDuringConfirmedManualOffline=${requests.get()}")
            assertEquals("metadata-only local playback must not open Spotify cover transport", 0, requests.get())
        } finally { p.release(); server.close(); peer.join(1000); root.deleteRecursively() }
    }

    @Test fun choosingDownloadedQueueAfterRemotePlaybackMustReturnControlsToLocalPlayer() {
        SpotifyNetworkPolicy.setManual(false)
        val queue = PlayQueue()
        val p = player(queue)
        try {
            // The public consumer called by PlaybackService's RemoteConnect collector.
            p.showRemote(RemotePlayback("synthetic-other-device", "Other device", "spotify:track:remote", "Remote song",
                "Synthetic artist", "Album", "", "", 0, 180000, true, false, false, false))
            assertTrue(p.isRemotePlayback)
            SpotifyNetworkPolicy.setManual(true)
            p.setMediaItems(listOf(CatalogTrack("spotify:track:local", "Downloaded song", "Synthetic artist",
                durationMs = 180000).toQueueItem()), 0, 0)
            assertEquals("spotify:track:local", queue.items.single().uri)
            println("CRITIC_B local queue selected but isRemotePlayback=${p.isRemotePlayback}")
            assertFalse("manual offline selection must expose local controls, not retired remote ownership", p.isRemotePlayback)
        } finally { p.release() }
    }
}
