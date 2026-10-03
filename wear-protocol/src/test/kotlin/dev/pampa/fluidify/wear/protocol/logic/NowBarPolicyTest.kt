package dev.pampa.fluidify.wear.protocol.logic

import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.PlaybackSource
import dev.pampa.fluidify.wear.protocol.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class NowBarPolicyTest {

    private fun snapshot(systemControls: Boolean?, track: Boolean = true) = PlaybackSnapshot(
        seq = 1,
        sentAtEpochMs = 0,
        source = PlaybackSource.PHONE,
        track = if (track) TrackInfo("spotify:track:1", "Song") else null,
        isPlaying = true,
        systemMediaControls = systemControls,
    )

    @Test
    fun autoStaysOutOfTheWayOfTheSystemsControls() {
        assertEquals(NowBarEntry.NONE, NowBarPolicy.entry(NowBarMode.AUTO, snapshot(true), watchPlaying = false, mirror = false))
    }

    @Test
    fun autoStepsInWhenTheSystemShowsNothing() {
        assertEquals(NowBarEntry.ONGOING, NowBarPolicy.entry(NowBarMode.AUTO, snapshot(false), watchPlaying = false, mirror = false))
    }

    @Test
    fun anOlderPhoneThatDoesNotSayCountsAsShown() {
        assertEquals(NowBarEntry.NONE, NowBarPolicy.entry(NowBarMode.AUTO, snapshot(null), watchPlaying = false, mirror = false))
    }

    @Test
    fun alwaysAndNeverMeanWhatTheySay() {
        assertEquals(NowBarEntry.ONGOING, NowBarPolicy.entry(NowBarMode.ALWAYS, snapshot(true), watchPlaying = false, mirror = false))
        assertEquals(NowBarEntry.NONE, NowBarPolicy.entry(NowBarMode.NEVER, snapshot(false), watchPlaying = false, mirror = false))
        assertEquals(NowBarEntry.NONE, NowBarPolicy.entry(NowBarMode.NEVER, snapshot(false), watchPlaying = false, mirror = true))
    }

    @Test
    fun theWatchsOwnPlaybackHasItsOwnEntry() {
        for (mode in NowBarMode.entries) {
            assertEquals(NowBarEntry.NONE, NowBarPolicy.entry(mode, snapshot(false), watchPlaying = true, mirror = true))
        }
    }

    @Test
    fun theMirrorReplacesTheOngoingActivity() {
        assertEquals(NowBarEntry.MIRROR, NowBarPolicy.entry(NowBarMode.AUTO, snapshot(true), watchPlaying = false, mirror = true))
    }

    @Test
    fun nothingPlayingNoEntry() {
        assertEquals(NowBarEntry.NONE, NowBarPolicy.entry(NowBarMode.ALWAYS, snapshot(false, track = false), watchPlaying = false, mirror = false))
    }
}
