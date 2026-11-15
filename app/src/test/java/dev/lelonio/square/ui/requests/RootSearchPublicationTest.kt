package dev.lelonio.square.ui.requests

import dev.lelonio.square.backend.SearchOutcome
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchResults
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic reader/admission: publication is tested independently of provider or Android callbacks. */
@OptIn(ExperimentalCoroutinesApi::class)
class RootSearchPublicationTest {
    @Test fun accountCanChangeAfterTheLastOnlineCheckWithoutPublishingTheOldResult() = runTest {
        var owner = "a"
        var checks = 0
        val track = CatalogTrack("spotify:track:private-a", "A", "Artist")
        var visible = SearchRequestCoordinator.Snapshot()
        val pipe = SearchRequestCoordinator(this, { SearchResults() }, { visible = it }, 0,
            onlineAllowed = {
                if (++checks == 2) { owner = "b"; visible = SearchRequestCoordinator.Snapshot() }
                true
            })
        pipe.submitWithAdmission("a query", false, { write -> if (owner == "a") write() }) {
            SearchOutcome.Success(SearchResults(tracks = listOf(track)))
        }
        advanceUntilIdle()
        assertEquals("b", owner)
        assertTrue(visible.results.tracks.isEmpty())
        assertTrue(visible.query.isEmpty())
        pipe.submitWithAdmission("b query", false, { write -> if (owner == "b") write() }) {
            SearchOutcome.Success(SearchResults(tracks = listOf(track.copy(uri = "spotify:track:b"))))
        }
        advanceUntilIdle()
        assertEquals("b query", visible.query)
        assertEquals("spotify:track:b", visible.results.tracks.single().uri)
    }

    @Test fun localIndexRefreshRetainsTheOriginalAccountAdmissionToo() = runTest {
        var owner = "a"
        var visible = SearchRequestCoordinator.Snapshot()
        val pipe = SearchRequestCoordinator(this, {
            SearchResults(tracks = listOf(CatalogTrack("spotify:track:$owner", owner, "Artist")))
        }, { visible = it }, 0)
        pipe.submitWithAdmission("a query", true, { write -> if (owner == "a") write() }) {
            error("offline search cannot call online")
        }
        assertEquals("spotify:track:a", visible.results.tracks.single().uri)
        owner = "b"
        visible = SearchRequestCoordinator.Snapshot()
        pipe.refreshLocal()
        assertTrue(visible.query.isEmpty())
        pipe.submitWithAdmission("b query", true, { write -> if (owner == "b") write() }) {
            error("offline search cannot call online")
        }
        assertEquals("spotify:track:b", visible.results.tracks.single().uri)
        advanceUntilIdle()
    }
}
