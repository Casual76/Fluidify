@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import android.app.Application
import android.os.Bundle
import android.os.Process
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.data.AccountLocalDataFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException

/** Prepared: real callback admission/scoped browse handles; no app startup, provider or native engine. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaBrowseAccountBoundaryTest {
    @Test fun queuedChildrenAndOldBrowseHandlesAreDeniedAcrossLogoutAndReturn() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fixture = AccountLocalDataFixture()
        fixture.connect()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val sink = object : SessionLikedTracks {
            override suspend fun isLiked(uri: String): Boolean? = error("not used")
            override suspend fun set(uri: String, liked: Boolean) = error("not used")
        }
        val tree = MediaBrowseTree(context, scope, sink, fixture.accounts)
        val player = ExoPlayer.Builder(context).build()
        val session = MediaLibrarySession.Builder(context, player, tree).build()
        val controller = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            context.packageName, Process.myPid(), Process.myUid(), 1, 1, true, Bundle.EMPTY)
        try {
            val rootA = tree.onGetLibraryRoot(session, controller, null).get().value!!.mediaId
            val old = tree.onGetChildren(session, controller, rootA, 0, 20, null)
            fixture.logout(); fixture.connect("account-b")
            runCurrent()
            assertTrue(old.isDone)
            try { old.get(); fail("Queued A request executed under B") }
            catch (expected: ExecutionException) { assertTrue(expected.cause is kotlinx.coroutines.CancellationException) }
            val rootB = tree.onGetLibraryRoot(session, controller, null).get().value!!.mediaId
            assertNotEquals(rootA, rootB)
            fixture.connect()
            val replay = tree.onGetChildren(session, controller, rootA, 0, 20, null)
            runCurrent()
            try { replay.get(); fail("Old A handle silently renewed on return") }
            catch (expected: ExecutionException) { assertTrue(expected.cause is kotlinx.coroutines.CancellationException) }
        } finally { session.release(); player.release(); scope.cancel() }
    }
}
