package dev.lelonio.square.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadIndexPersistenceTest {
    @Test fun concurrentMutationsAreDurableAndReopenIncludesEveryAcceptedWrite() = runTest {
        val context = accountDataTestContext()
        val accounts = testAccountAuthority()
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, accounts)
        val access = accounts.capture()!!
        val track = CatalogTrack("spotify:track:latest", "Latest", "Artist")
        store.setOwner(access, "a", listOf(track), null)
        val completion = launch { store.onCompleted(access, track.uri, DownloadStore.FileRecord("aabb", "OGG_320", 42, 320, 123)) }
        val owner = launch { store.setOwner(access, "b", listOf(track), DownloadStore.OwnerLabel("B")) }
        completion.join(); owner.join()
        val reopened = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, testAccountAuthority())
        reopened.load()
        assertEquals(setOf("a", "b"), reopened.owners.value.keys)
        assertEquals(42L, reopened.files.value.getValue(track.uri).bytes)
        assertEquals(OwnerState.Complete, reopened.ownerState("b"))
    }

    @Test fun failedFinishPreservesPreviousReadableIndexAndMemory() = runTest {
        val context = accountDataTestContext()
        val accounts = testAccountAuthority()
        var reject = false
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), { file, text ->
            if (reject) writeDownloadIndexAtomically(file, text) { _, _ -> throw IOException("before publication") }
            else writeIndex(file, text)
        }, accounts)
        val access = accounts.capture()!!
        store.setOwner(access, "previous", emptyList(), DownloadStore.OwnerLabel("Previous"))
        reject = true
        try { store.setOwner(access, "uncommitted", emptyList(), null); fail("Expected failed commit") }
        catch (_: IOException) { }
        assertEquals(setOf("previous"), store.owners.value.keys)
        val reopened = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, testAccountAuthority())
        reopened.load()
        assertEquals(setOf("previous"), reopened.owners.value.keys)
        assertEquals("Previous", reopened.labelOf("previous")?.name)
    }

    @Test fun stagingWriteFailureDoesNotRemoveValidIndex() {
        val directory = File(accountDataTestContext().filesDir, "staging").apply { mkdirs() }
        val file = File(directory, "index.json")
        writeIndex(file, "previous")
        val staged = File(directory, "index.json.tmp").apply { mkdirs() }
        File(staged, "blocker").writeText("occupied")
        try { writeIndex(file, "replacement"); fail("Expected failed staging write") }
        catch (_: IOException) { assertEquals("previous", file.readText()) }
    }
    private fun writeIndex(file: File, text: String) = writeDownloadIndexAtomically(file, text)
}
