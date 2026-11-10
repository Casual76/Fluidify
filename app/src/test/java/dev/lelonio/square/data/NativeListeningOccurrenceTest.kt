// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.data

import android.app.Application
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.playback.ListeningRecorder
import dev.lelonio.square.playback.PlaybackOccurrence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NativeListeningOccurrenceTest {
    private val uri = "spotify:track:one"

    @Test fun nativeEndBelowDurationSeparatesRepeatOneAndConsecutiveDuplicateUris() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        val history = ListeningRepository(context, db)
        history.connect(HistoryAccount("native"))
        val source = PlaybackOccurrence()
        source.select(0, uri)
        var ready = true
        var index = 0
        var remote = false
        val item = MediaItem.Builder().setMediaId(uri).build()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getCurrentMediaItemIndex" -> index
                "getDuration" -> 60_000L
                "isPlaying" -> ready
                "getPlaybackState" -> if (ready) Player.STATE_READY else Player.STATE_BUFFERING
                "addListener", "removeListener" -> null
                else -> error(method.name)
            }
        } as Player
        val recorder = ListeningRecorder(history, this, { player }, { currentTime + 1_000 },
            { currentTime }, { remote }, { source })
        try {
            runCurrent()
            advanceTimeBy(59_750); runCurrent() // never reaches Media3's 60,000ms inference threshold
            source.nativeEvent("end_of_track", uri, index, false)
            ready = false
            source.nativeEvent("loading", uri, index, false)
            advanceTimeBy(1_000); runCurrent()
            ready = true
            source.nativeEvent("playing", uri, index, false)
            advanceTimeBy(31_000); runCurrent()
            source.nativeEvent("end_of_track", uri, index, false)
            index = 1 // duplicate URI in the next queue slot
            source.nativeEvent("playing", uri, index, false)
            advanceTimeBy(31_000); runCurrent()
            remote = true
            advanceTimeBy(10_000); runCurrent()
            recorder.stop(); runCurrent(); recorder.awaitCheckpoints()
            val rows = history.records(history.session.value.access!!, 0, 200_000).records
            assertEquals(3, rows.size)
            assertEquals(3, rows.map { it.id }.distinct().size)
            assertTrue(rows.all { it.qualified })
            assertTrue(rows.sumOf { it.listenedMs!! } <= 122_750)
        } finally { recorder.stop(); runCurrent(); recorder.awaitCheckpoints(); db.close() }
    }

    @Test fun nativeResumeSeekPauseAndPrefetchKeepIdentityButNewSelectionDoesNot() {
        val source = PlaybackOccurrence()
        source.select(0, uri)
        val first = source.id
        listOf("position", "paused", "playing", "loading", "playing").forEach {
            source.nativeEvent(it, uri, 0, false)
            assertEquals(first, source.id)
        }
        source.nativeEvent("loading", "spotify:track:next", 1, false)
        assertEquals(first, source.id)
        source.select(0, uri)
        assertNotEquals(first, source.id)
        source.nativeEvent("playing", uri, 0, true)
        assertNull(source.id)
        source.nativeEvent("playing", uri, 0, false)
        assertNotEquals(first, source.id)
    }
    @Test fun freshNativeSameUriTwentyPlusTwentyAreTwoUnqualifiedOccurrences() =
        measuredNativeRequests(reindexOnly = false)

    @Test fun continuingShuffleInsertRemoveMoveReindexesRemainOneQualifiedFortySeconds() =
        measuredNativeRequests(reindexOnly = true)

    @Test fun freshLoadCannotOverwriteRestoredOwnedSameUriCheckpoint() =
        measuredNativeRequests(reindexOnly = false, restoredFresh = true)

    private fun measuredNativeRequests(reindexOnly: Boolean, restoredFresh: Boolean = false) = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        val history = ListeningRepository(context, db).apply { connect(HistoryAccount("native-r2")) }
        val source = PlaybackOccurrence()
        var index = 2
        var playing = true
        val item = MediaItem.Builder().setMediaId(uri).build()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, _ ->
            when (m.name) {
                "getCurrentMediaItem" -> item
                "getCurrentMediaItemIndex" -> index
                "getDuration" -> 60_000L
                "isPlaying" -> playing
                "getPlaybackState" -> Player.STATE_READY
                "addListener", "removeListener" -> null
                else -> error(m.name)
            }
        } as Player
        // Same mapper used by LibrespotPlayer.applyEvent; native emits this string through JNI.
        source.nativeEvent("playback_request", "pump:session:1", index, false)
        source.nativeEvent("playing", uri, index, false)
        val first = source.id
        val recorder = ListeningRecorder(history, this, { player }, { currentTime + 1000 },
            { currentTime }, { false }, { source })
        try {
            runCurrent(); advanceTimeBy(20_000); runCurrent()
            if (reindexOnly) {
                // Shuffle current 2 -> 0, then insert/remove/move preceding entries.
                for (newIndex in listOf(0, 1, 0, 3)) {
                    index = newIndex
                    source.nativeEvent("playing", uri, index, false)
                    assertEquals(first, source.id)
                }
                playing = false; source.nativeEvent("paused", uri, index, false)
                source.notifyListeners() // LibrespotPlayer publishes after state invalidation
                advanceTimeBy(5000); runCurrent()
                source.nativeEvent("loading", "spotify:track:prefetch", 4, false)
                source.nativeEvent("position", uri, index, false)
                playing = true; source.nativeEvent("playing", uri, index, false)
                source.notifyListeners()
            } else {
                if (restoredFresh) source.select(index, uri, restoredId = first)
                // No new explicit selection, outgoing end or URI/index change.
                source.nativeEvent("playback_request", "pump:session:2", index, false)
                source.nativeEvent("playing", uri, index, false)
                assertNotEquals(first, source.id)
            }
            val current = source.id
            source.nativeEvent("playback_request", if (reindexOnly) "pump:session:1" else "pump:session:2", index, false)
            assertEquals(current, source.id)
            advanceTimeBy(20_000); runCurrent()
            recorder.stop(); runCurrent(); recorder.awaitCheckpoints()
            val rows = history.records(history.session.value.access!!, 0, 200_000).records
            assertEquals(if (reindexOnly) 1 else 2, rows.size)
            assertEquals(40_000L, rows.sumOf { it.listenedMs!! })
            assertTrue(rows.all { it.qualified == reindexOnly })
            if (!reindexOnly) {
                assertEquals(20_000L, rows.single { it.id == "local:$first" }.listenedMs)
                assertEquals(20_000L, rows.single { it.id != "local:$first" }.listenedMs)
            }
        } finally { recorder.stop(); runCurrent(); recorder.awaitCheckpoints(); db.close() }
    }

    @Test fun explicitSelectionBindsOneNativeRequestAndRestoredTokenFailsClosedOnFreshLoad() {
        val source = PlaybackOccurrence()
        source.select(0, uri)
        val selected = source.id
        source.nativeEvent("playback_request", "pump:session:1", 0, false)
        source.nativeEvent("playing", uri, 0, false)
        assertEquals(selected, source.id)
        source.select(0, uri)
        val next = source.id
        source.nativeEvent("playback_request", "pump:session:2", 0, false)
        source.nativeEvent("playing", uri, 0, false)
        assertEquals(next, source.id)
        assertNotEquals(selected, next)
        // A native counter reused in a different engine/session cannot share identity.
        source.nativeEvent("playback_request", "new-pump:new-session:2", 0, false)
        source.nativeEvent("playing", uri, 0, false)
        assertNotEquals(next, source.id)
        source.select(0, uri, restoredId = "owned-old-checkpoint")
        source.nativeEvent("playback_request", "new-pump:new-session:3", 0, false)
        assertNull(source.id)
        source.nativeEvent("playing", uri, 0, false)
        assertNotEquals("owned-old-checkpoint", source.id)
        source.nativeEvent("playback_request", "new-pump:new-session:4", 0, true)
        source.nativeEvent("playing", uri, 0, true)
        assertNull(source.id)
    }

}
