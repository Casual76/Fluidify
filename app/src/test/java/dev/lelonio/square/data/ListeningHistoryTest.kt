// SPDX-License-Identifier: GPL-3.0-only
// Behavioral regressions adapted from Lelonio/Square 8598e13347b9c20153898f2481083c3ffcd24fb2.
package dev.lelonio.square.data

import org.junit.Assert.*
import org.junit.Test

class ListeningHistoryTest {
    private val song = CatalogTrack("spotify:track:one", "One", "Artist", durationMs = 60_000)
    @Test fun pauseBufferingResumeAndSeekDoNotCreateAnotherPlay() {
        val s = ListeningSampler()
        val first = s.sample(song, true, 10_000, 0)!!
        assertEquals(1_000L, s.sample(song, true, 11_000, 1_000)!!.listenedMs)
        s.sample(song, false, 12_000, 2_000)
        s.sample(song, false, 80_000, 70_000)
        s.sample(song, true, 81_000, 71_000)
        assertEquals(2_000L, s.sample(song, true, 82_000, 72_000)!!.listenedMs)
        s.discontinuity(72_000)
        assertEquals(2_000L, s.sample(song, true, 83_000, 73_000)!!.listenedMs)
        assertEquals(first.id, s.sample(song, true, 84_000, 74_000)!!.id)
        assertEquals(3_000L, s.sample(song, true, 120_000, 110_000)!!.listenedMs)
    }
    @Test fun repeatSameUriHasDistinctOccurrenceIds() {
        val s = ListeningSampler()
        s.sample(song, true, 0, 0)
        val first = s.finish(1_000, 1_000)!!
        val second = s.sample(song, true, 1_000, 1_000)!!
        assertNotEquals(first.id, second.id)
        assertEquals(1_000L, first.listenedMs)
        assertEquals(0L, second.listenedMs)
    }
    @Test fun openingPausedQueueDoesNotStartListenAndQualificationSurvivesPause() {
        val s = ListeningSampler()
        assertNull(s.sample(song, false, 0, 0))
        s.sample(song, true, 1_000, 1_000)
        var e: ListeningEvent? = null
        for (i in 2..31) e = s.sample(song, true, i * 1_000L, i * 1_000L)
        assertTrue(e!!.qualified)
        val id = e!!.id
        assertTrue(s.sample(song, false, 32_000, 32_000)!!.qualified)
        assertEquals(id, s.sample(song, true, 80_000, 80_000)!!.id)
    }
    @Test fun thresholdMatchesUpstream() {
        assertEquals(30_000L, ListeningSampler.threshold(0))
        assertEquals(30_000L, ListeningSampler.threshold(60_000))
        assertEquals(240_000L, ListeningSampler.threshold(900_000))
    }
    @Test fun remotePlaybackAndRestartDoNotAddDowntime() {
        val s = ListeningSampler()
        s.sample(song, true, 0, 0)
        s.sample(song, true, 1_000, 1_000)
        val stopped = s.sample(null, false, 1_000, 1_000)!!
        assertNull(s.sample(null, false, 90_000, 90_000))
        val restarted = ListeningSampler().sample(song, true, 100_000, 100_000)!!
        assertEquals(1_000L, stopped.listenedMs)
        assertEquals(0L, restarted.listenedMs)
        assertNotEquals(stopped.id, restarted.id)
    }
}
