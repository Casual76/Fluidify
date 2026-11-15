@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.lelonio.square.playback

import android.os.Bundle
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.ListenableFuture
import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.HistoryAccess
import dev.lelonio.square.ui.EXTRA_PLAY_NEXT
import dev.lelonio.square.ui.EXTRA_QUEUE_CYCLE_ANCHOR
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException

/** Prepared only. Real MediaBrowseTree callbacks with synthetic account authority; no native/network. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class I06MediaBrowseIngressTest {
    private fun track(uri: String, title: String = uri) = CatalogTrack(uri, title, "Artist", durationMs = 10_000)
    private fun item(uri: String, title: String = uri) = track(uri, title).toQueueItem()
    private val sink = object : SessionLikedTracks {
        override suspend fun isLiked(uri: String): Boolean? = error("not used")
        override suspend fun set(uri: String, liked: Boolean) = error("not used")
    }
    private inner class Harness(scope: CoroutineScope, val fixture: AccountLocalDataFixture,
        ensure: suspend (String?) -> Unit = {},
        tracks: suspend (String) -> List<CatalogTrack> = { emptyList() },
    ) : AutoCloseable {
        val context = dev.lelonio.square.data.accountDataTestContext()
        val player = ExoPlayer.Builder(context).build()
        val tree = MediaBrowseTree(context, scope, sink, fixture.accounts, ensure) { _, node -> tracks(node) }
        var preparations = 0
        var plays = 0
        private val transport = object : androidx.media3.common.ForwardingPlayer(player) {
            override fun prepare() { preparations++ } // Do not open a synthetic URI in host tests.
            override fun play() { plays++; super.play() }
        }
        val session = MediaLibrarySession.Builder(context, transport, tree).build()
        val controller = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            context.packageName, Process.myPid(), Process.myUid(), 1, 1, true, Bundle.EMPTY)
        fun scoped(uri: String) = MediaItem.Builder()
            .setMediaId("sq/account/${fixture.accounts.capture()!!.requestSession}/sq/t/sq/recent/$uri")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Chosen").build()).build()
        fun set(items: List<MediaItem>, index: Int = 0, position: Long = 0) =
            tree.onSetMediaItems(session, controller, items.toMutableList(), index, position)
        fun add(items: List<MediaItem>) = tree.onAddMediaItems(session, controller, items.toMutableList())
        fun widget(action: String, epoch: Long = PlaybackService.mediaAccountEpoch,
            who: MediaSession.ControllerInfo = controller) = tree.onCustomCommand(session, who,
                SessionCommand(I06_WIDGET_TRANSPORT, Bundle.EMPTY), Bundle().apply {
                    putString(I06_WIDGET_ACTION, action); putLong(I06_WIDGET_ACCOUNT_EPOCH, epoch)
                })
        override fun close() { VideoPlaybackQueue.clear(); session.release(); player.release() }
    }
    private fun failure(future: ListenableFuture<*>): Throwable {
        try { future.get(); fail("Request should fail without changing playback") }
        catch (expected: ExecutionException) { return expected.cause!! }
        error("unreachable")
    }

    @Test fun browseProducedDuplicateRowSelectsItsOwnOccurrenceAndRejectsChangedShelf() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        var shelf = listOf(track("spotify:track:a"), track("spotify:track:b"),
            track("spotify:track:a"), track("spotify:track:c"))
        Harness(this, fixture, tracks = { shelf }).use { h ->
            val parent = "sq/account/${fixture.accounts.capture()!!.requestSession}/spotify:playlist:duplicates"
            val children = h.tree.onGetChildren(h.session, h.controller, parent, 0, 20, null)
            runCurrent()
            val rows = children.get().value!!
            assertNotEquals(rows[0].mediaId, rows[2].mediaId)
            val selected = h.set(listOf(rows[2]), position = 321)
            runCurrent()
            assertEquals(2, selected.get().startIndex)
            assertEquals(321L, selected.get().startPositionMs)
            shelf = shelf.drop(1)
            val stale = h.set(listOf(rows[2]))
            runCurrent()
            assertTrue(failure(stale) is IllegalArgumentException)
        }
    }

    @Test fun scopedLocalRecentResolvesBeforeBackendChoiceAndKeepsSelectedTrackIndexAndPosition() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        val backends = mutableListOf<String?>()
        Harness(this, fixture, { backends += it }, { listOf(track("spotify:track:a"),
            track("local:track:1"), track("spotify:track:b"), track("local:track:2")) }).use { h ->
            val request = h.set(listOf(h.scoped("local:track:2")), position = 5_123)
            runCurrent()
            val result = request.get()
            assertEquals(listOf("local:track:2"), backends)
            assertEquals(listOf("local:track:1", "local:track:2"), result.mediaItems.map { it.mediaId })
            assertEquals(1, result.startIndex); assertEquals(5_123L, result.startPositionMs)
            assertEquals("local:track:2", result.mediaItems[result.startIndex].localConfiguration!!.uri.toString())
            assertEquals(listOf(false, true), result.mediaItems.map {
                it.mediaMetadata.extras?.getBoolean(EXTRA_QUEUE_CYCLE_ANCHOR) == true })
        }
    }

    @Test fun scopedSpotifyRecentSelectsSpotifyAndRemapsOnlyItsOriginalOccurrence() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        val backends = mutableListOf<String?>()
        Harness(this, fixture, { backends += it }, { listOf(track("local:track:1"),
            track("spotify:track:a"), track("local:track:2"), track("spotify:track:b")) }).use { h ->
            val request = h.set(listOf(h.scoped("spotify:track:b")))
            runCurrent(); val result = request.get()
            assertEquals(listOf("spotify:track:b"), backends)
            assertEquals(listOf("spotify:track:a", "spotify:track:b"), result.mediaItems.map { it.mediaId })
            assertEquals(1, result.startIndex)
        }
    }

    @Test fun rawMixedQueueWithDuplicateUrisKeepsTheChosenOccurrenceAndCycleAnchor() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        Harness(this, fixture).use { h ->
            val request = h.set(listOf(item("spotify:track:a"), item("local:track:1", "first"),
                item("spotify:track:a"), item("local:track:1", "second")), 3, 123)
            runCurrent(); val result = request.get()
            assertEquals(1, result.startIndex)
            assertEquals("second", result.mediaItems[result.startIndex].mediaMetadata.title.toString())
            assertEquals(listOf(false, true), result.mediaItems.map {
                it.mediaMetadata.extras?.getBoolean(EXTRA_QUEUE_CYCLE_ANCHOR) == true })
        }
    }

    @Test fun selectedTrackRemovedFromRecentShelfStillDoesNotSubstituteTheFirstRemainingSong() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        val backends = mutableListOf<String?>()
        Harness(this, fixture, { backends += it }, { listOf(track("spotify:track:unrelated")) }).use { h ->
            val request = h.set(listOf(h.scoped("local:track:42")))
            runCurrent(); val result = request.get()
            assertEquals(listOf("local:track:42"), backends)
            assertEquals(listOf("local:track:42"), result.mediaItems.map { it.mediaId })
            assertEquals(0, result.startIndex)
        }
    }

    @Test fun localAdditionToSpotifyFailsTheWholeBlockWithoutSwitchingOrPlayingAnything() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        var swaps = 0
        Harness(this, fixture, { swaps++ }).use { h ->
            h.player.setMediaItems(listOf(item("spotify:track:current"), item("spotify:track:context")))
            h.player.seekTo(0, 7_000)
            val rejected = h.add(listOf(item("local:track:42")))
            runCurrent()
            assertTrue(failure(rejected) is I06QueueSourceMismatch)
            assertEquals(0, swaps); assertEquals(2, h.player.mediaItemCount)
            assertEquals("spotify:track:current", h.player.currentMediaItem!!.mediaId)
            assertEquals(7_000L, h.player.currentPosition); assertFalse(h.player.playWhenReady)
            assertEquals(0, h.preparations); assertEquals(0, h.plays)
            assertEquals(h.context.getString(dev.lelonio.square.R.string.wf_i06_queue_source_mismatch),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        }
    }

    @Test fun mixedEnqueueCannotPartiallyInsertOnlyTheSpotifyPart() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        Harness(this, fixture).use { h ->
            h.player.setMediaItem(item("spotify:track:current"))
            val rejected = h.add(listOf(item("spotify:track:first"), item("local:track:42")))
            runCurrent()
            assertTrue(failure(rejected) is I06QueueSourceMismatch)
            assertEquals(1, h.player.mediaItemCount)
        }
    }

    @Test fun emptyQueueChoosesLocalBackendAndSuppliesAnActualPlaybackUri() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        val backends = mutableListOf<String?>()
        Harness(this, fixture, { backends += it }).use { h ->
            val request = h.add(listOf(h.scoped("local:track:42")))
            runCurrent()
            assertTrue(request.get().isEmpty()) // Delivered directly once, after successful admission.
            assertEquals(listOf("local:track:42"), backends)
            assertEquals(1, h.player.mediaItemCount)
            val added = h.player.currentMediaItem!!
            assertEquals("local:track:42", added.mediaId)
            assertEquals("local:track:42", added.localConfiguration!!.uri.toString())
            assertTrue(added.mediaMetadata.extras!!.getBoolean(EXTRA_QUEUE_CYCLE_ANCHOR))
            assertEquals(1, h.preparations); assertEquals(1, h.plays)
        }
    }

    @Test fun completeCollectionSurvivesCallbackThenCoreInsertsAllAfterEarlierManualItems() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        Harness(this, fixture).use { h ->
            val initial = listOf(item("spotify:track:current"), item("spotify:track:context"))
            h.player.setMediaItems(initial)
            val queue = PlayQueue().apply { replaceFromMediaItems(initial, 0) }
            queue.addFromMediaItems(2, listOf(track("spotify:track:manual").toQueueItem(playNext = true)))
            val block = listOf("one", "two", "three").map { track("spotify:track:$it").toQueueItem(playNext = true)
                .i06ForRequest(fixture.accounts.capture()!!.requestSession) }
            val request = h.add(block)
            runCurrent(); val accepted = request.get()
            assertEquals(3, accepted.size)
            assertTrue(accepted.all { it.mediaMetadata.extras!!.getBoolean(EXTRA_PLAY_NEXT) })
            queue.addFromMediaItems(queue.items.size, accepted)
            assertEquals(listOf("current", "manual", "one", "two", "three", "context"),
                queue.items.map { it.uri.substringAfterLast(':') })
            assertEquals(0, queue.currentIndex)
            assertFalse(h.player.playWhenReady)
        }
    }

    @Test fun slowBrowseExpansionCannotOverwriteALaterSelection() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        val barrier = CompletableDeferred<Unit>()
        val backends = mutableListOf<String?>()
        Harness(this, fixture, { backends += it }, { barrier.await(); listOf(track("local:track:42")) }).use { h ->
            val old = h.set(listOf(h.scoped("local:track:42")))
            runCurrent()
            val latest = h.set(listOf(item("spotify:track:latest")))
            runCurrent(); assertEquals("spotify:track:latest", latest.get().mediaItems.single().mediaId)
            barrier.complete(Unit); runCurrent()
            assertTrue(failure(old) is kotlinx.coroutines.CancellationException)
            assertEquals(listOf("spotify:track:latest"), backends)
        }
    }

    @Test fun staleBrowseHandleAndOldActivityRequestCannotBorrowNewAccount() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        var swaps = 0
        Harness(this, fixture, { swaps++ }).use { h ->
            val handle = h.scoped("local:track:42")
            val item = item("spotify:track:old").i06ForRequest(fixture.accounts.capture()!!.requestSession)
            fixture.logout(); fixture.connect("account-b")
            val browse = h.set(listOf(handle))
            runCurrent(); assertTrue(failure(browse) is kotlinx.coroutines.CancellationException)
            val activity = h.add(listOf(item))
            runCurrent(); assertTrue(failure(activity) is kotlinx.coroutines.CancellationException)
            assertEquals(0, swaps); assertEquals(0, h.player.mediaItemCount)
        }
    }


    @Test fun enqueueDuringVideoGoesOnceToTheDisplayedParkedQueueWithoutReplacingTheVideo() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        Harness(this, fixture).use { h ->
            val parked = ExoPlayer.Builder(h.context).build()
            try {
                parked.setMediaItem(item("spotify:track:current"))
                h.player.setMediaItem(item("spotify:track:video"))
                h.player.seekTo(0, 8_000)
                var changed = 0
                VideoPlaybackQueue.show(VideoQueueOwner(parked, PlaybackService.mediaAccountEpoch,
                    { _, _ -> error("enqueue must not select another track") }, { changed++ }))
                val request = h.add(listOf(track("spotify:track:one").toQueueItem(playNext = true),
                    track("spotify:track:two").toQueueItem(playNext = true)))
                runCurrent()
                assertTrue(request.get().isEmpty())
                assertEquals(listOf("spotify:track:current", "spotify:track:one", "spotify:track:two"),
                    (0 until parked.mediaItemCount).map { parked.getMediaItemAt(it).mediaId })
                assertEquals(1, changed); assertEquals(1, h.player.mediaItemCount)
                assertEquals("spotify:track:video", h.player.currentMediaItem!!.mediaId)
                assertEquals(8_000L, h.player.currentPosition)
                assertFalse(parked.playWhenReady)
            } finally { parked.release() }
        }
    }

    @Test fun invalidOriginalIndexFailsBeforeAnyBackendMutation() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        var swaps = 0
        Harness(this, fixture, { swaps++ }).use { h ->
            val request = h.set(listOf(item("local:track:1"), item("spotify:track:a")), 9)
            runCurrent()
            assertTrue(failure(request) is IllegalArgumentException)
            assertEquals(0, swaps)
        }
    }

    @Test fun emptySourceSwitchDoesNotResurrectTheOldParkedQueueBeforeTheRequestedAddition() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        lateinit var h: Harness
        h = Harness(this, fixture, { h.player.setMediaItem(item("spotify:track:old-parked")) })
        h.use {
            val request = h.add(listOf(item("local:track:42")))
            runCurrent()
            assertTrue(request.get().isEmpty())
            assertEquals(1, h.player.mediaItemCount)
            assertEquals("local:track:42", h.player.currentMediaItem!!.mediaId)
            assertEquals(1, h.preparations); assertEquals(1, h.plays)
        }
    }

    @Test fun emptyEnqueueDoesNotChooseAPlayerOrStartPlayback() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        var swaps = 0
        Harness(this, fixture, { swaps++ }).use { h ->
            val request = h.add(emptyList())
            runCurrent()
            assertTrue(request.get().isEmpty()); assertEquals(0, swaps)
            assertEquals(0, h.player.mediaItemCount); assertFalse(h.player.playWhenReady)
        }
    }

    @Test fun aBackendSwapAlreadyWaitingCannotFinishAfterTheLatestBrowseSelection() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        val barrier = CompletableDeferred<Unit>()
        val swaps = mutableListOf<String?>()
        Harness(this, fixture, { uri ->
            if (uri == "local:track:42") barrier.await()
            swaps += uri
        }).use { h ->
            val old = h.set(listOf(item("local:track:42")))
            runCurrent()
            val latest = h.set(listOf(item("spotify:track:latest")))
            runCurrent(); assertFalse(latest.isDone)
            barrier.complete(Unit); runCurrent()
            assertTrue(failure(old) is kotlinx.coroutines.CancellationException)
            assertEquals(listOf("local:track:42", "spotify:track:latest"), swaps)
            assertEquals("spotify:track:latest", latest.get().mediaItems.single().mediaId)
        }
    }

    @Test fun widgetCommandIsPrivateToOwnUidEvenWithATrustedClaimedApplicationPackage() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        Harness(this, fixture).use { h ->
            h.player.setMediaItems(listOf(item("spotify:track:one"), item("spotify:track:two")))
            val command = SessionCommand(I06_WIDGET_TRANSPORT, Bundle.EMPTY)
            val foreign = MediaSession.ControllerInfo.createTestOnlyControllerInfo(h.context.packageName,
                Process.myPid(), Process.myUid() + 100, 1, 1, true, Bundle.EMPTY)
            assertTrue(h.tree.onConnect(h.session, h.controller).availableSessionCommands.contains(command))
            assertFalse(h.tree.onConnect(h.session, foreign).availableSessionCommands.contains(command))
            val reply = h.widget("next", who = foreign)
            runCurrent()
            assertEquals(SessionError.ERROR_PERMISSION_DENIED, reply.get().resultCode)
            assertEquals("spotify:track:one", h.player.currentMediaItem!!.mediaId)
        }
    }

    @Test fun widgetAcknowledgementFollowsActualServicePlayerMutation() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        Harness(this, fixture).use { h ->
            h.player.setMediaItems(listOf(item("spotify:track:one"), item("spotify:track:two")))
            val next = h.widget("next")
            runCurrent()
            assertEquals(SessionResult.RESULT_SUCCESS, next.get().resultCode)
            assertEquals("spotify:track:two", h.player.currentMediaItem!!.mediaId)
            h.player.playWhenReady = true
            val pause = h.widget("toggle")
            runCurrent()
            assertEquals(SessionResult.RESULT_SUCCESS, pause.get().resultCode)
            assertFalse(h.player.playWhenReady)
        }
    }

    @Test fun widgetUnavailableCommandAndStaleAccountReturnRealFailureWithoutMutation() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        Harness(this, fixture).use { h ->
            h.player.setMediaItem(item("spotify:track:one"))
            val unavailable = h.widget("next")
            runCurrent()
            assertEquals(SessionError.ERROR_NOT_SUPPORTED, unavailable.get().resultCode)
            val stale = h.widget("toggle", epoch = PlaybackService.mediaAccountEpoch - 1)
            runCurrent()
            assertEquals(SessionError.ERROR_PERMISSION_DENIED, stale.get().resultCode)
            assertEquals("spotify:track:one", h.player.currentMediaItem!!.mediaId)
            assertFalse(h.player.playWhenReady)
        }
    }

    @Test fun sameCredentialLineageSurvivesAccessPromotionButLogoutAndReturnCannotRenewIt() = runTest {
        val fixture = AccountLocalDataFixture().also { it.connect() }
        val original = fixture.accounts.capture()!!
        fixture.history = HistoryAccess(original.account, 99)
        fixture.accounts.historyChanged()
        val local = fixture.accounts.capture()!!
        assertNotSame(original, local)
        assertSame(local, i06CurrentPlaybackAccess(fixture.accounts, original, 7, 7))
        assertTrue(fixture.accounts.verifyNativeAccount())
        assertSame(fixture.accounts.capture(), i06CurrentPlaybackAccess(fixture.accounts, original, 7, 7))
        assertNull(i06CurrentPlaybackAccess(fixture.accounts, original, 7, 8))
        fixture.logout(); fixture.connect()
        assertNull(i06CurrentPlaybackAccess(fixture.accounts, original, 7, 7))
    }
}
