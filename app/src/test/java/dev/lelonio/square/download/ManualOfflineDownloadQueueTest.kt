package dev.lelonio.square.download

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import dev.lelonio.square.data.*
import dev.lelonio.square.playback.OfflineMode
import dev.lelonio.square.playback.PlaybackService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real store, settings and queue; only link, JNI and Android service-start sinks are synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ManualOfflineDownloadQueueTest {
    private val track = CatalogTrack("spotify:track:synthetic", "Kept", "Synthetic")
    private val sidecar = """{"trackId":"synthetic","format":"OGG_320","bytes":42,"downloadedAt":123}"""

    @After fun reset() {
        SpotifyNetworkPolicy.setManual(false)
        OfflineMode.setManual(false)
        OfflineMode.setNoSession(false)
        OfflineMode.setSlow(false)
    }

    @Test fun persistedColdStartNeedsNoCollectorAndBlocksWifiMobileAndSessionStarts() = runTest {
        val context = accountDataTestContext()
        context.getSharedPreferences("square_downloads", Context.MODE_PRIVATE).edit()
            .putBoolean("offline_mode", true).commit()
        val fixture = AccountLocalDataFixture()
        val access = fixture.connect()
        val settings = DownloadSettingsStore(context, fixture.accounts)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = DownloadStore(context, dispatcher, { f, s -> f.parentFile?.mkdirs(); f.writeText(s) }, fixture.accounts)
        store.addSingle(access, track)
        var requests = 0
        var link = DownloadQueue.Link.UNMETERED
        val queue = DownloadQueue(context, store, settings, backgroundScope,
            { _, _ -> requests++; sidecar }, {}, { true }, { link }, extrasOverride = { _, _ -> }, transferDispatcher = StandardTestDispatcher(testScheduler))
        try {
            queue.wake(); runCurrent()
            link = DownloadQueue.Link.METERED
            settings.setWifiOnly(false)
            queue.wake(); runCurrent()
            assertEquals(0, requests)
            assertEquals(listOf(track.uri), store.pending())
            assertEquals(listOf(track), store.tracksForOwner(DownloadStore.SINGLES))
            assertTrue(OfflineMode.active.value)
            var starts = 0
            val serviceSink = object : ContextWrapper(context) {
                override fun startService(intent: Intent): ComponentName {
                    starts++
                    return ComponentName(this, PlaybackService::class.java)
                }
            }
            PlaybackService.connect(serviceSink)
            assertEquals(1, starts) // Dispatch local boot even while Spotify networking is denied.
            settings.setOfflineMode(false)
            OfflineMode.setNoSession(true)
            fixture.nativeReady = false
            PlaybackService.connect(serviceSink)
            assertEquals(2, starts) // NO_SESSION is allowed to recover with manual OFF.
            queue.wake(); runCurrent()
            assertEquals(0, requests)
            fixture.nativeReady = true
            assertTrue(fixture.accounts.verifyNativeAccount())
            queue.wake(); runCurrent()
            assertEquals(1, requests)
            assertTrue(store.isDownloaded(track.uri))
        } finally { queue.stop() }
    }

    @Test fun queuedPumpIsInvalidatedAcrossOnOffAndFreshWakeCanResume() = runTest {
        val context = accountDataTestContext()
        val fixture = AccountLocalDataFixture()
        val access = fixture.connect()
        val settings = DownloadSettingsStore(context, fixture.accounts)
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler),
            { f, s -> f.parentFile?.mkdirs(); f.writeText(s) }, fixture.accounts)
        store.addSingle(access, track)
        var requests = 0
        val queue = DownloadQueue(context, store, settings, backgroundScope,
            { _, _ -> requests++; sidecar }, {}, { true }, { DownloadQueue.Link.UNMETERED }, extrasOverride = { _, _ -> }, transferDispatcher = StandardTestDispatcher(testScheduler))
        try {
            queue.wake() // Pump has not been scheduled yet.
            settings.setOfflineMode(true)
            settings.setOfflineMode(false)
            runCurrent()
            assertEquals(0, requests)
            assertEquals(listOf(track.uri), store.pending())
            queue.wake(); runCurrent()
            assertEquals(1, requests)
        } finally { queue.stop() }
    }

    @Test fun manualOffKeepsWifiAdmissionAndAutomaticSlowFallbackCanStillDownload() = runTest {
        val context = accountDataTestContext()
        val fixture = AccountLocalDataFixture()
        val access = fixture.connect()
        val settings = DownloadSettingsStore(context, fixture.accounts)
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler),
            { f, s -> f.parentFile?.mkdirs(); f.writeText(s) }, fixture.accounts)
        store.addSingle(access, track)
        var requests = 0
        val queue = DownloadQueue(context, store, settings, backgroundScope,
            { _, _ -> requests++; sidecar }, {}, { true }, { DownloadQueue.Link.METERED }, extrasOverride = { _, _ -> }, transferDispatcher = StandardTestDispatcher(testScheduler))
        try {
            OfflineMode.setSlow(true)
            queue.wake(); runCurrent()
            assertEquals(0, requests)
            assertEquals(DownloadQueue.Waiting.WIFI, queue.status.value.waiting)
            settings.setWifiOnly(false)
            advanceTimeBy(1_500); runCurrent()
            assertEquals(1, requests)
        } finally { queue.stop() }
    }

    @Test fun activeTransferIsCancelledUntilJniReturnsWithoutDeletingCompletedFilesOrOwners() = runBlocking {
        val context = accountDataTestContext()
        val fixture = AccountLocalDataFixture()
        val access = fixture.connect()
        val settings = DownloadSettingsStore(context, fixture.accounts)
        val store = DownloadStore(context, fixture.accounts)
        val completed = track.copy(uri = "spotify:track:already-kept")
        store.addSingle(access, completed)
        store.onCompleted(access, completed.uri, DownloadStore.FileRecord("kept", "OGG_320", 42, 320, 123))
        val physical = store.root.resolve("synthetic-completed.ogg").apply { writeText("completed") }
        store.addSingle(access, track)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = DownloadQueue(context, store, settings, scope,
            { _, _ ->
                if (requests.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    check(release.await(3, TimeUnit.SECONDS))
                }
                sidecar
            }, { cancelled.complete(Unit) }, { true }, { DownloadQueue.Link.UNMETERED }, extrasOverride = { _, _ -> })
        try {
            queue.wake()
            withTimeout(2_000) { entered.await() }
            settings.setOfflineMode(true)
            withTimeout(2_000) { cancelled.await() }
            val stopping = async { queue.stopAndJoin() }
            yield()
            assertFalse(stopping.isCompleted)
            assertTrue(physical.exists())
            assertTrue(store.isDownloaded(completed.uri))
            assertEquals(setOf(completed.uri, track.uri), store.owners.value[DownloadStore.SINGLES]!!.toSet())
            release.countDown()
            withTimeout(2_000) { stopping.await() }
            assertEquals(1, requests.get())
            assertTrue(store.failures.value.isEmpty())
            settings.setOfflineMode(false)
            queue.wake()
            withTimeout(2_000) { while (!store.isDownloaded(track.uri)) delay(10) }
            assertEquals(2, requests.get())
            assertEquals("completed", physical.readText())
        } finally {
            release.countDown()
            queue.stopAndJoin()
            scope.cancel()
        }
    }
}
