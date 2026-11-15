package dev.lelonio.square.wear

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lelonio.square.data.Gateway
import dev.lelonio.square.data.LikedTracks
import dev.lelonio.square.data.PathfinderKeys
import dev.lelonio.square.data.SpotifyNetworkPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Requires root's extraction of the shared cache publication; no provider calls allowed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class I11LikedCacheTest {
    @Test fun confirmedWearUnlikeAndLikeUpdateBothPositiveAndNegativeCaches() = runTest {
        SpotifyNetworkPolicy.setManual(true)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val cache = LikedTracks(Gateway(PathfinderKeys(context)))
            val uri = "spotify:track:synthetic-i11"
            cache.recordConfirmed(uri, true)
            assertEquals(true, cache.isLiked(uri))
            cache.recordConfirmed(uri, false)
            assertEquals(false, cache.isLiked(uri))
            cache.recordConfirmed(uri, true)
            assertEquals(true, cache.isLiked(uri))
            assertTrue(uri in cache.state.value)
        } finally { SpotifyNetworkPolicy.setManual(false) }
    }
}
