package dev.lelonio.square.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** FI4-03 regressions prepared for root validation; NOT EXECUTED by writer. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadOwnerRefreshTest {
    private val old = "2020-01-01T00:00:00Z"
    private val recent = "2026-01-01T00:00:00Z"
    private val t = CatalogTrack("spotify:track:t", "T", "Artist", addedAt = old)
    private val u = CatalogTrack("spotify:track:u", "U", "Artist", addedAt = recent)
    private val record = DownloadStore.FileRecord("aabb", "OGG_320", 42, 320, 123)

    private fun context(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(base.cacheDir.toPath(), "owner-refresh-").toFile()
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
    }

    private suspend fun TestScope.store(context: Context, persist: (File, String) -> Unit =
        { file, text -> writeDownloadIndexAtomically(file, text) }) =
        DownloadStore(context, StandardTestDispatcher(testScheduler), persist, testAccountAuthority())

    private fun mutex(store: DownloadStore): Mutex =
        DownloadStore::class.java.getDeclaredField("writeLock").let {
            it.isAccessible = true
            it.get(store) as Mutex
        }

    @Test fun sameUrisRepairOnlyRefreshedOwnerAndOrderAfterReopen() = runTest {
        val context = context()
        val store = store(context)
        store.setOwner(store.captureAccess()!!, "A", listOf(t.copy(addedAt = null), u.copy(addedAt = null)), null)
        store.setOwner(store.captureAccess()!!, "B", listOf(t.copy(addedAt = null)), null)
        val token = store.beginOwnerRefresh(store.captureAccess()!!, "A")!!
        assertEquals(DownloadStore.OwnerRefreshResult.METADATA, store.refreshOwner(token, listOf(t, u), null))
        val reopened = store(context)
        reopened.load()
        val tracks = reopened.tracksForOwner("A")
        assertEquals(listOf(old, recent), tracks.map { it.addedAt })
        assertEquals(listOf(u.uri, t.uri), orderPlaylistTracksByAddedAt(tracks, false).map { it.uri })
        assertNull(reopened.tracksForOwner("B").single().addedAt)
        assertNull(reopened.trackOf(t.uri)?.addedAt)
    }

    @Test fun duplicateDatesPreserveAudioFailuresQueueAndOtherOwner() = runTest {
        val context = context()
        val store = store(context)
        store.load()
        store.setOwner(store.captureAccess()!!, "A", listOf(t, t.copy(addedAt = recent), u), DownloadStore.OwnerLabel("A"))
        store.setOwner(store.captureAccess()!!, "B", listOf(t), null)
        store.onCompleted(store.captureAccess()!!, t.uri, record)
        repeat(DownloadStore.MAX_ATTEMPTS) { store.onFailed(store.captureAccess()!!, u.uri, "unavailable") }
        store.onProgress(store.captureAccess()!!, u.uri, 0.4f)
        val audio = File(store.root, "audio/aa/bb").apply { parentFile!!.mkdirs(); writeText("audio") }
        val failures = store.failures.value
        val pending = store.pending(Long.MAX_VALUE)
        val progress = store.progress.value
        val retry = store.retryAt()
        val token = store.beginOwnerRefresh(store.captureAccess()!!, "A")!!
        assertEquals(DownloadStore.OwnerRefreshResult.METADATA,
            store.refreshOwner(token, listOf(t.copy(addedAt = recent), t, u.copy(addedAt = null)), null))
        assertEquals(listOf(recent, old, null), store.tracksForOwner("A").map { it.addedAt })
        assertEquals(record, store.files.value[t.uri])
        assertEquals(failures, store.failures.value)
        assertEquals(pending, store.pending(Long.MAX_VALUE))
        assertEquals(progress, store.progress.value)
        assertEquals(retry, store.retryAt())
        assertEquals(listOf(u.uri), store.givenUp())
        assertEquals("audio", audio.readText())
        runCurrent()
        val reopened = store(context)
        reopened.load()
        assertEquals(listOf(recent, old, null), reopened.tracksForOwner("A").map { it.addedAt })
        assertEquals(old, reopened.tracksForOwner("B").single().addedAt)
        assertEquals(failures, reopened.failures.value)
        assertEquals(record, reopened.files.value[t.uri])
        assertEquals("A", reopened.labelOf("A")?.name)
        // Explicit user retry still clears failures and offers unfinished audio.
        store.retryFailed(store.captureAccess()!!)
        assertTrue(store.failures.value.isEmpty())
        assertEquals(listOf(u.uri), store.pending(Long.MAX_VALUE))
    }

    @Test fun identicalMetadataDoesNotPersistAndConsumesConcurrentRead() = runTest {
        var saves = 0
        val store = store(context()) { file, text -> saves++; writeDownloadIndexAtomically(file, text) }
        store.load()
        store.setOwner(store.captureAccess()!!, "A", listOf(t), null)
        runCurrent()
        val previousSaves = saves
        val first = store.beginOwnerRefresh(store.captureAccess()!!, "A")!!
        val other = store.beginOwnerRefresh(store.captureAccess()!!, "A")!!
        assertEquals(DownloadStore.OwnerRefreshResult.UNCHANGED, store.refreshOwner(first, listOf(t), null))
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, store.refreshOwner(other, listOf(u), null))
        runCurrent()
        assertEquals(previousSaves, saves)
        assertEquals(listOf(t), store.tracksForOwner("A"))
    }

    @Test fun replacementWithIdenticalUrisInvalidatesFetchedDates() = runTest {
        val store = store(context())
        store.load()
        store.setOwner(store.captureAccess()!!, "A", listOf(t), null)
        val token = store.beginOwnerRefresh(store.captureAccess()!!, "A")!!
        store.setOwner(store.captureAccess()!!, "A", listOf(t), null)
        assertEquals(DownloadStore.OwnerRefreshResult.STALE,
            store.refreshOwner(token, listOf(t.copy(addedAt = recent)), null))
        assertEquals(old, store.tracksForOwner("A").single().addedAt)
    }

    @Test fun contendedRemovalAndRecreationCannotResurrectFetchedOwner() = runTest {
        val store = store(context())
        store.load()
        store.setOwner(store.captureAccess()!!, "A", listOf(t), null)
        val token = store.beginOwnerRefresh(store.captureAccess()!!, "A")!!
        val lock = mutex(store)
        lock.lock()
        val removal = launch(start = CoroutineStart.UNDISPATCHED) { store.removeOwner(store.captureAccess()!!, "A") }
        var result: DownloadStore.OwnerRefreshResult? = null
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
            result = store.refreshOwner(token, listOf(u), null)
        }
        runCurrent()
        assertFalse(refresh.isCompleted)
        lock.unlock()
        removal.join(); refresh.join()
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, result)
        assertFalse(store.owners.value.containsKey("A"))
        store.setOwner(store.captureAccess()!!, "A", listOf(t), null)
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, store.refreshOwner(token, listOf(u), null))
    }

    @Test fun membershipAndMetadataWinnersFenceOlderResponsesButOtherOwnersDoNot() = runTest {
        val store = store(context())
        store.load()
        store.setOwner(store.captureAccess()!!, "A", listOf(t), null)
        val staleMembership = store.beginOwnerRefresh(store.captureAccess()!!, "A")!!
        val winner = store.beginOwnerRefresh(store.captureAccess()!!, "A")!!
        store.setOwner(store.captureAccess()!!, "B", listOf(u), null)
        assertEquals(DownloadStore.OwnerRefreshResult.METADATA,
            store.refreshOwner(winner, listOf(t.copy(addedAt = recent)), null))
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, store.refreshOwner(staleMembership, listOf(u), null))
        val staleDates = store.beginOwnerRefresh(store.captureAccess()!!, "A")!!
        store.setOwner(store.captureAccess()!!, "A", listOf(u), null)
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, store.refreshOwner(staleDates, listOf(t), null))
        assertEquals(listOf(u), store.tracksForOwner("A"))
    }

    @Test fun queuedRefreshCannotOverwriteMembershipAcceptedAheadOfIt() = runTest {
        val store = store(context())
        store.load()
        store.setOwner(store.captureAccess()!!, "A", listOf(t), null)
        val token = store.beginOwnerRefresh(store.captureAccess()!!, "A")!!
        val lock = mutex(store)
        lock.lock()
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) { store.setOwner(store.captureAccess()!!, "A", listOf(u), null) }
        var result: DownloadStore.OwnerRefreshResult? = null
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
            result = store.refreshOwner(token, listOf(t.copy(addedAt = recent)), null)
        }
        runCurrent()
        assertFalse(refresh.isCompleted)
        lock.unlock()
        replacement.join(); refresh.join()
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, result)
        assertEquals(listOf(u), store.tracksForOwner("A"))
    }

    @Test fun membershipRefreshRetainsExplicitRetrySemanticsAndEmptyCannotClear() = runTest {
        val store = store(context())
        store.load()
        assertNull(store.beginOwnerRefresh(store.captureAccess()!!, "missing"))
        val create = store.beginOwnerRefresh(store.captureAccess()!!, DownloadStore.LIKED, allowCreate = true, consent = { true })!!
        store.addSingle(store.captureAccess()!!, t)
        store.onFailed(store.captureAccess()!!, t.uri, "old")
        assertFalse(store.failures.value.isEmpty())
        assertEquals(DownloadStore.OwnerRefreshResult.MEMBERSHIP, store.refreshOwner(create, listOf(t), null))
        assertTrue(store.failures.value.isEmpty())
        val token = store.beginOwnerRefresh(store.captureAccess()!!, DownloadStore.LIKED)!!
        assertEquals(DownloadStore.OwnerRefreshResult.UNCHANGED, store.refreshOwner(token, emptyList(), null))
        assertEquals(listOf(t.uri), store.owners.value[DownloadStore.LIKED])
        store.removeOwner(store.captureAccess()!!, "absent")
        store.removeOwner(store.captureAccess()!!, DownloadStore.LIKED)
        val absent = store.beginOwnerRefresh(store.captureAccess()!!, DownloadStore.LIKED, allowCreate = true, consent = { true })!!
        store.removeOwner(store.captureAccess()!!, DownloadStore.LIKED)
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, store.refreshOwner(absent, listOf(t), null))
    }

    @Test fun cancelledRefreshWaitingForLockDoesNotPublishOrConsumeFence() = runTest {
        val store = store(context())
        store.load()
        store.setOwner(store.captureAccess()!!, "A", listOf(t), null)
        val token = store.beginOwnerRefresh(store.captureAccess()!!, "A")!!
        val lock = mutex(store)
        lock.lock()
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
            store.refreshOwner(token, listOf(t.copy(addedAt = recent)), null)
        }
        runCurrent()
        refresh.cancelAndJoin()
        lock.unlock()
        assertEquals(old, store.tracksForOwner("A").single().addedAt)
        assertEquals(DownloadStore.OwnerRefreshResult.METADATA,
            store.refreshOwner(token, listOf(t.copy(addedAt = recent)), null))
    }

    @Test fun failedAtomicSaveRetainsOldDiskAndMemoryThenRetryPersistsRefreshedDates() = runTest {
        val context = context()
        var reject = false
        val store = store(context) { file, text ->
            if (reject) writeDownloadIndexAtomically(file, text) { _, _ -> throw IOException("atomic failure") }
            else writeDownloadIndexAtomically(file, text)
        }
        store.setOwner(store.captureAccess()!!, "A", listOf(t), null)
        val before = File(store.root, "account-index-v1.json").readText()
        reject = true
        try {
            store.refreshOwner(store.beginOwnerRefresh(store.captureAccess()!!, "A")!!, listOf(t.copy(addedAt = recent)), null)
            fail("Expected failed commit")
        } catch (_: IOException) { }
        assertEquals(before, File(store.root, "account-index-v1.json").readText())
        assertEquals(old, store.tracksForOwner("A").single().addedAt)
        reject = false
        assertEquals(DownloadStore.OwnerRefreshResult.METADATA,
            store.refreshOwner(store.beginOwnerRefresh(store.captureAccess()!!, "A")!!, listOf(t.copy(addedAt = recent)), null))
        val reopened = store(context)
        reopened.load()
        assertEquals(recent, reopened.tracksForOwner("A").single().addedAt)
    }
}
