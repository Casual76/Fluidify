package dev.lelonio.square.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prepared only. The real disk cache is isolated; all account identities are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaylistCacheInvalidationTest {
    @Test fun oldQueuedInvalidationCannotDeleteNewAuthorityEntryForSameUri() = runTest {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(base.cacheDir.toPath(), "playlist-cache-").toFile()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir() = directory
        }
        try {
            val fixture = AccountLocalDataFixture()
            val access = fixture.connect()
            val cache = ContextCacheStore(context)
            val uri = "spotify:playlist:synthetic"
            cache.write(uri, listOf(CatalogTrack("spotify:track:a", "A", "Artist")), null)
            fixture.connect("account-b")
            val fresh = listOf(CatalogTrack("spotify:track:b", "B", "Artist"))
            cache.write(uri, fresh, null)
            try {
                cache.remove(uri, fixture.accounts, access)
                fail("Old authority deleted the cache")
            } catch (_: CancellationException) { }
            assertEquals(fresh, cache.read(uri)?.tracks)
            val current = checkNotNull(fixture.accounts.capture())
            cache.remove(uri, fixture.accounts, current)
            assertNull(cache.read(uri))
        } finally { directory.deleteRecursively() }
    }
}
