package dev.lelonio.square.ui.navigation

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.ContextCacheStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PREPARED ONLY. Actual cache stores in isolated temporary storage, synthetic authority only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlaylistContextCachesTest {
    private fun context(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(base.cacheDir.toPath(), "playlist-cache-test").toFile()
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = directory
        }
    }

    @Test fun identicalUriCannotReuseAnotherAuthorityOrUnprovenLegacyTracks() = runTest {
        val context = context()
        val account = AccountLocalDataFixture()
        val accessA = account.connect()
        val caches = PlaylistContextCaches(context)
        val uri = "spotify:playlist:same-uri"
        val trackA = CatalogTrack("spotify:track:a", "Private synthetic A", "Artist")
        ContextCacheStore(context).write(uri, listOf(trackA), null)
        assertNull(caches.disk(accessA).read(uri)) // legacy data has no provenance
        caches.disk(accessA).write(uri, listOf(trackA), "a-snapshot")
        assertEquals(listOf(trackA), PlaylistContextCaches(context).disk(accessA).read(uri)?.tracks)
        val accessB = account.connect("account-b")
        assertNotEquals(caches.memoryKey(uri, accessA), caches.memoryKey(uri, accessB))
        assertNull(caches.disk(accessB).read(uri))
        // A tardy write is confined to the revoked namespace; it cannot seed B's page on restore.
        caches.disk(accessA).write(uri, listOf(trackA), "late-a")
        assertNull(PlaylistContextCaches(context).disk(accessB).read(uri))
    }

    @Test fun scopedCacheStillSeedsPlaylistMembershipAndOldCleanupCannotDeleteNewAuthority() = runTest {
        val context = context()
        val account = AccountLocalDataFixture()
        val accessA = account.connect()
        val caches = PlaylistContextCaches(context)
        val uri = "spotify:playlist:p"
        val a = CatalogTrack("spotify:track:a", "A", "Artist")
        val b = CatalogTrack("spotify:track:b", "B", "Artist")
        caches.disk(accessA).write(uri, listOf(a), null)
        val accessB = account.connect("account-b")
        caches.disk(accessB).write(uri, listOf(b), null)
        caches.disk(accessA).clear()
        assertEquals(setOf(b.uri), caches.disk(accessB).tracksInPlaylists())
        assertEquals(listOf(b), caches.disk(accessB).read(uri)?.tracks)
    }

    @Test fun collidingLegacyFilenameHashesCannotRenderTheOtherContext() = runTest {
        val account = AccountLocalDataFixture()
        val access = account.connect()
        val caches = PlaylistContextCaches(context())
        val one = "spotify:playlist:Aa"
        val two = "spotify:playlist:BB"
        assertEquals(one.hashCode(), two.hashCode())
        val tracks = listOf(CatalogTrack("spotify:track:a", "Other context", "Artist"))
        caches.disk(access).write(one, tracks, null)
        assertEquals(tracks, caches.read(one, access)?.tracks)
        assertNull(caches.read(two, access))
    }
}
