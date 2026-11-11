@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.lelonio.square.playback

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Real session connection/dispatch callbacks. No app startup, native engine or account network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaBrowseTreeTrustTest {
    private lateinit var app: Application
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var tree: MediaBrowseTree
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uri = "spotify:track:owned-cache-observation"
    private val like = "dev.lelonio.square.LIKE"
    private val read = "dev.lelonio.square.IS_LIKED"
    private val shuffle = "dev.lelonio.square.SHUFFLE"
    private val writes = mutableListOf<Pair<String, Boolean>>()
    private val reads = mutableListOf<String>()
    private val known = mutableSetOf(uri)

    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        player = ExoPlayer.Builder(app).build()
        player.setMediaItem(MediaItem.fromUri(uri).buildUpon().setMediaId(uri).build())
        tree = MediaBrowseTree(app, scope, object : SessionLikedTracks {
            override suspend fun isLiked(uri: String): Boolean {
                reads += uri
                return uri in known
            }
            override suspend fun set(uri: String, liked: Boolean) {
                writes += uri to liked
                if (liked) known += uri else known -= uri
            }
        })
        session = MediaLibrarySession.Builder(app, player, tree).build()
    }

    @After fun release() {
        if (::session.isInitialized) session.release()
        if (::player.isInitialized) player.release()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun controller(own: Boolean, trusted: Boolean = false, packageName: String = "third.party.controller") =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            packageName, Process.myPid(), Process.myUid() + if (own) 0 else 100,
            1, 1, trusted, Bundle.EMPTY,
        )

    private fun command(action: String) = SessionCommand(action, Bundle.EMPTY)

    @Test fun foreignConnectionKeepsTransportButDoesNotReceiveAccountCommands() {
        val result = tree.onConnect(session, controller(own = false))
        assertTrue(result.isAccepted)
        assertFalse(result.availableSessionCommands.contains(command(like)))
        assertFalse(result.availableSessionCommands.contains(command(read)))
        assertTrue(result.availableSessionCommands.contains(command(shuffle)))
        assertTrue(result.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE))
    }

    @Test fun trustedMediaControllerCannotBorrowAccountAuthority() {
        val result = tree.onConnect(session, controller(own = false, trusted = true))
        assertTrue(result.isAccepted)
        assertFalse(result.availableSessionCommands.contains(command(like)))
        assertFalse(result.availableSessionCommands.contains(command(read)))
    }

    @Test fun claimedApplicationPackageDoesNotReplaceUidCheck() {
        val foreign = controller(own = false, trusted = true, packageName = app.packageName)
        assertFalse(tree.onConnect(session, foreign).availableSessionCommands.contains(command(like)))
        assertEquals(SessionError.ERROR_PERMISSION_DENIED,
            tree.onCustomCommand(session, foreign, command(read), Bundle.EMPTY).get(3, TimeUnit.SECONDS).resultCode)
    }

    @Test fun directForeignDispatchCannotBypassTheAccountCommandBoundary() {
        val foreign = controller(own = false)
        for (action in listOf(like, read)) {
            val reply = tree.onCustomCommand(session, foreign, command(action), Bundle().apply {
                putString("uri", uri)
                putBoolean("remove", true)
            }).get(3, TimeUnit.SECONDS)
            assertEquals(action, SessionError.ERROR_PERMISSION_DENIED, reply.resultCode)
        }
        assertEquals(setOf(uri), known)
        assertTrue(reads.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test fun ownUidRetainsCommandsAndReachesTheLibraryRead() {
        val own = controller(own = true, packageName = app.packageName)
        val result = tree.onConnect(session, own)
        assertTrue(result.availableSessionCommands.contains(command(like)))
        assertTrue(result.availableSessionCommands.contains(command(read)))
        val reply = tree.onCustomCommand(session, own, command(read), Bundle().apply { putString("uri", uri) }).get(3, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, reply.resultCode)
        assertTrue(reply.extras.getBoolean("liked"))
        assertEquals(listOf(uri), reads)
        assertTrue(writes.isEmpty())
    }

    @Test fun ownUidWriteReachesTheLibrarySinkOnce() {
        val own = controller(own = true, packageName = app.packageName)
        val reply = tree.onCustomCommand(session, own, command(like), Bundle().apply {
            putString("uri", uri)
            putBoolean("remove", true)
        }).get(3, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, reply.resultCode)
        assertEquals(listOf(uri to false), writes)
        assertFalse(uri in known)
        assertTrue(reads.isEmpty())
    }

    @Test fun ordinaryForeignTransportStillReachesThePlayer() {
        val foreign = controller(own = false)
        assertFalse(player.shuffleModeEnabled)
        assertEquals(SessionResult.RESULT_SUCCESS,
            tree.onCustomCommand(session, foreign, command(shuffle), Bundle.EMPTY).get(3, TimeUnit.SECONDS).resultCode)
        assertTrue(player.shuffleModeEnabled)
    }
}
