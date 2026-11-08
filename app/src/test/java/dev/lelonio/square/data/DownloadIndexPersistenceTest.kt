package dev.lelonio.square.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
    /** Deliberately run later-scheduled disk writers before earlier ones. */
    private class ReversedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeLast().run() }
    }

    @Test fun ownerAndCompletionSurviveReversedWriterSchedulingAndReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = ReversedDispatcher()
        val store = DownloadStore(context, dispatcher) { file, text ->
            writeDownloadIndexAtomically(file, text)
        }
        val loading = launch { store.load() }
        runCurrent()
        dispatcher.drain()
        loading.join()
        val track = CatalogTrack("spotify:track:latest", "Latest", "Artist")
        store.setOwner("owner:a", listOf(track), DownloadStore.OwnerLabel("A"))
        store.onCompleted(track.uri, DownloadStore.FileRecord("id", "OGG_320", 42, 320, 123))
        store.setOwner("owner:b", listOf(track), DownloadStore.OwnerLabel("B"))
        dispatcher.drain()

        val reopened = DownloadStore(context, dispatcher) { file, text ->
            writeDownloadIndexAtomically(file, text)
        }
        val reopening = launch { reopened.load() }
        runCurrent()
        dispatcher.drain()
        reopening.join()
        assertEquals(setOf("owner:a", "owner:b"), reopened.owners.value.keys)
        assertEquals(42L, reopened.files.value.getValue(track.uri).bytes)
        assertEquals(OwnerState.Complete, reopened.ownerState("owner:b"))
        assertEquals("Latest", reopened.trackOf(track.uri)?.name)
    }

    @Test fun failedFinishPreservesPreviousReadableIndex() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = ReversedDispatcher()
        var reject = false
        val store = DownloadStore(context, dispatcher) { file, text ->
            if (reject) writeDownloadIndexAtomically(file, text) { _, _ ->
                throw IOException("injected failure before atomic publication")
            } else writeDownloadIndexAtomically(file, text)
        }
        val loading = launch { store.load() }
        runCurrent(); dispatcher.drain(); loading.join()
        store.setOwner("previous", emptyList(), DownloadStore.OwnerLabel("Previous"))
        dispatcher.drain()
        reject = true
        store.setOwner("uncommitted", emptyList(), null)
        dispatcher.drain()
        val reopened = DownloadStore(context, dispatcher) { file, text ->
            writeDownloadIndexAtomically(file, text)
        }
        val reopening = launch { reopened.load() }
        runCurrent(); dispatcher.drain(); reopening.join()
        assertEquals(setOf("previous"), reopened.owners.value.keys)
        assertEquals("Previous", reopened.labelOf("previous")?.name)
    }

    @Test fun stagingWriteFailureDoesNotRemoveValidIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "index-write-failure").apply { mkdirs() }
        val file = File(directory, "index.json")
        writeDownloadIndexAtomically(file, "{\"version\":1}")
        // A nonempty directory forces opening the staging output to fail.
        val staged = File(directory, "index.json.tmp").apply { mkdirs() }
        File(staged, "blocker").writeText("occupied")
        try {
            writeDownloadIndexAtomically(file, "replacement")
            fail("Expected failed staging write")
        } catch (_: IOException) {
            assertEquals("{\"version\":1}", file.readText())
        }
    }
}
