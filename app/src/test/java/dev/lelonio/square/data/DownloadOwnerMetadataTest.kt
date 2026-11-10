package dev.lelonio.square.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
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

/** Prepared regression suite; NOT EXECUTED by the downloads writer. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadOwnerMetadataTest {
    private val t = CatalogTrack("spotify:track:t", "T", "Artist", addedAt = "2024-01-01T00:00:00Z")
    private val u = CatalogTrack("spotify:track:u", "U", "Artist", addedAt = "2024-02-01T00:00:00Z")
    private val later = "2025-03-01T00:00:00Z"
    private val record = DownloadStore.FileRecord("aabb", "OGG_320", 42, 320, 123)

    /** Every test owns a synthetic directory, never the application's real index. */
    private fun context(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(base.cacheDir.toPath(), "owner-metadata-").toFile()
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
    }

    private suspend fun TestScope.store(
        context: Context,
        persist: (File, String) -> Unit = { file, text -> writeDownloadIndexAtomically(file, text) },
    ) = DownloadStore(context, StandardTestDispatcher(testScheduler), persist, testAccountAuthority())

    @Test fun otherOwnerAndSingleCannotOverwriteContextDatesAfterReopen() = runTest {
        val context = context()
        val store = store(context)
        store.load()
        store.setOwner(store.captureAccess()!!, "A", listOf(t, u), DownloadStore.OwnerLabel("Playlist A"))
        store.onCompleted(store.captureAccess()!!, t.uri, record)
        store.onFailed(store.captureAccess()!!, u.uri, "synthetic failure")
        store.setOwner(store.captureAccess()!!, "B", listOf(t.copy(name = "Updated T", addedAt = later)), null)
        store.addSingle(store.captureAccess()!!, t.copy(name = "Shared title", addedAt = later))
        assertEquals(listOf(t.addedAt, u.addedAt), store.tracksForOwner("A").map { it.addedAt })
        assertEquals(listOf(later), store.tracksForOwner("B").map { it.addedAt })
        assertNull(store.trackOf(t.uri)?.addedAt)
        assertNull(store.tracksForOwner(DownloadStore.SINGLES).single().addedAt)
        assertEquals("Shared title", store.tracksForOwner("A").first().name)
        // Pending entries remain present in offline detail reconstruction.
        assertEquals(listOf(t.uri, u.uri), store.tracksForOwner("A").map { it.uri })
        runCurrent()

        val reopened = store(context)
        reopened.load()
        assertEquals(listOf(t.addedAt, u.addedAt), reopened.tracksForOwner("A").map { it.addedAt })
        assertEquals(listOf(later), reopened.tracksForOwner("B").map { it.addedAt })
        assertEquals(record, reopened.files.value[t.uri])
        assertEquals("synthetic failure", reopened.failures.value[u.uri]?.reason)
        assertEquals("Playlist A", reopened.labelOf("A")?.name)
        assertEquals(listOf("A", "B", DownloadStore.SINGLES), reopened.owners.value.keys.toList())
    }

    @Test fun duplicateOccurrencesReorderAndRemovalKeepTheirOwnDates() = runTest {
        val context = context()
        val store = store(context)
        store.load()
        store.setOwner(store.captureAccess()!!, "A", listOf(t, t.copy(addedAt = later), u), null)
        assertEquals(listOf(t.addedAt, later, u.addedAt), store.tracksForOwner("A").map { it.addedAt })
        runCurrent()
        val reopened = store(context)
        reopened.load()
        assertEquals(listOf(t.addedAt, later, u.addedAt), reopened.tracksForOwner("A").map { it.addedAt })
        reopened.setOwner(reopened.captureAccess()!!, "A", listOf(u, t.copy(addedAt = later)), null)
        assertEquals(listOf(u.uri, t.uri), reopened.tracksForOwner("A").map { it.uri })
        assertEquals(listOf(u.addedAt, later), reopened.tracksForOwner("A").map { it.addedAt })
        reopened.removeOwner(reopened.captureAccess()!!, "A")
        assertTrue(reopened.tracksForOwner("A").isEmpty())
        runCurrent()
        assertFalse(File(reopened.root, "account-index-v1.json").readText().contains("ownerOccurrences\":{\"A\""))
    }

    @Test fun legacyDataIsNeverImplicitlyAssignedOnLoad() = runTest {
        val context = context()
        val root = File(context.filesDir, DownloadStore.DIR_NAME).apply { mkdirs() }
        File(root, "index.json").writeText("""{"version":1,"owners":{"A":["spotify:track:t"]}}""")
        val store = store(context)
        store.load()
        assertTrue(store.legacy.value.pending)
        assertTrue(store.owners.value.isEmpty())
        assertNull(store.trackOf(t.uri))
    }

    /** Holds the real production mutex so BOTH callers reach the contested lock. */
    private fun mutex(store: DownloadStore): Mutex =
        DownloadStore::class.java.getDeclaredField("writeLock").let {
            it.isAccessible = true
            it.get(store) as Mutex
        }

    @Test fun contendedSingleAddsAndCompletionRetainBothMembershipsAndAudio() = runTest {
        val context = context()
        val store = store(context)
        store.load()
        val lock = mutex(store)
        lock.lock()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { store.addSingle(store.captureAccess()!!, t) }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { store.addSingle(store.captureAccess()!!, u) }
        val completion = launch(start = CoroutineStart.UNDISPATCHED) { store.onCompleted(store.captureAccess()!!, t.uri, record) }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertFalse(completion.isCompleted)
        lock.unlock()
        first.join(); second.join(); completion.join()
        runCurrent()
        val reopened = store(context)
        reopened.load()
        assertEquals(listOf(t.uri, u.uri), reopened.owners.value[DownloadStore.SINGLES])
        assertEquals(record, reopened.files.value[t.uri])
        assertEquals(listOf(null, null), reopened.tracksForOwner(DownloadStore.SINGLES).map { it.addedAt })
    }

    @Test fun contendedRemoveThenAddDoesNotResurrectRemovedSingle() = runTest {
        val context = context()
        val store = store(context)
        store.load()
        store.addSingle(store.captureAccess()!!, t)
        store.addSingle(store.captureAccess()!!, u)
        runCurrent()
        val v = CatalogTrack("spotify:track:v", "V", "Artist")
        val lock = mutex(store)
        lock.lock()
        val removal = launch(start = CoroutineStart.UNDISPATCHED) { store.removeSingle(store.captureAccess()!!, t.uri) }
        val addition = launch(start = CoroutineStart.UNDISPATCHED) { store.addSingle(store.captureAccess()!!, v) }
        runCurrent()
        assertFalse(removal.isCompleted)
        runCurrent()
        assertFalse(addition.isCompleted)
        lock.unlock()
        removal.join(); addition.join()
        assertEquals(listOf(u.uri, v.uri), store.tracksForOwner(DownloadStore.SINGLES).map { it.uri })
        runCurrent()
        val reopened = store(context)
        reopened.load()
        assertEquals(listOf(u.uri, v.uri), reopened.owners.value[DownloadStore.SINGLES])
    }

    @Test fun cancelledContendedMutationCannotPublishMembershipOrContext() = runTest {
        val context = context()
        val store = store(context)
        store.load()
        store.setOwner(store.captureAccess()!!, "A", listOf(t), null)
        runCurrent()
        val lock = mutex(store)
        lock.lock()
        val addition = launch(start = CoroutineStart.UNDISPATCHED) { store.addSingle(store.captureAccess()!!, u) }
        runCurrent()
        assertFalse(addition.isCompleted)
        addition.cancelAndJoin()
        lock.unlock()
        assertFalse(store.owners.value.containsKey(DownloadStore.SINGLES))
        assertNull(store.trackOf(u.uri))
        assertEquals(t.addedAt, store.tracksForOwner("A").single().addedAt)
        runCurrent()
        val reopened = store(context)
        reopened.load()
        assertFalse(reopened.owners.value.containsKey(DownloadStore.SINGLES))
    }

    @Test fun unknownNewContextDateNeverBorrowsKnownDateFromOtherOwner() = runTest {
        val context = context()
        val store = store(context)
        store.load()
        store.setOwner(store.captureAccess()!!, "A", listOf(t), null)
        store.setOwner(store.captureAccess()!!, "B", listOf(t.copy(addedAt = null)), null)
        assertEquals(t.addedAt, store.tracksForOwner("A").single().addedAt)
        assertNull(store.tracksForOwner("B").single().addedAt)
        runCurrent()
        val reopened = store(context)
        reopened.load()
        assertEquals(t.addedAt, reopened.tracksForOwner("A").single().addedAt)
        assertNull(reopened.tracksForOwner("B").single().addedAt)
    }

    @Test fun failedPublicationKeepsOldIndexMemoryAndAudioThenRetryCommits() = runTest {
        val context = context()
        var reject = false
        val store = store(context) { file, text ->
            if (reject) writeDownloadIndexAtomically(file, text) { _, _ -> throw IOException("atomic failure") }
            else writeDownloadIndexAtomically(file, text)
        }
        store.setOwner(store.captureAccess()!!, "A", listOf(t, t.copy(addedAt = later)), DownloadStore.OwnerLabel("A"))
        store.onCompleted(store.captureAccess()!!, t.uri, record)
        val audio = File(store.root, "audio/aa/bb").apply { parentFile!!.mkdirs(); writeText("audio") }
        val previous = File(store.root, "account-index-v1.json").readText()
        reject = true
        try { store.addSingle(store.captureAccess()!!, u); fail("Expected failed commit") }
        catch (_: IOException) { }
        assertEquals(previous, File(store.root, "account-index-v1.json").readText())
        assertFalse(store.owners.value.containsKey(DownloadStore.SINGLES))
        assertEquals("audio", audio.readText())
        reject = false
        store.addSingle(store.captureAccess()!!, u)
        val reopened = store(context)
        reopened.load()
        assertEquals(listOf(t.addedAt, later), reopened.tracksForOwner("A").map { it.addedAt })
        assertEquals(listOf(u.uri), reopened.owners.value[DownloadStore.SINGLES])
        assertEquals(record, reopened.files.value[t.uri])
    }
}
