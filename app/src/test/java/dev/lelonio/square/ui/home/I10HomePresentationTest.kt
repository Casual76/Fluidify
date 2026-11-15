package dev.lelonio.square.ui.home

import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.requests.I07FeedAvailability
import dev.lelonio.square.ui.requests.I07FeedSection
import dev.lelonio.square.ui.requests.I07FeedStatus
import org.junit.Assert.*
import org.junit.Test

/** PREPARED, NOT EXECUTED. Tests the projection consumed by the actual Home list. */
class I10HomePresentationTest {
    private fun notice(feed: MainViewModel.FeedState, filter: I10HomeFilter = I10HomeFilter.ARTISTS,
        shelves: Boolean = false, recent: Boolean = false) =
        i10HomeNotice(filter, feed, shelves, hasPlaylists = false, hasRecent = recent)

    @Test fun emptyFilterDistinguishesAllOutcomesAndOnlyReadyIsTrueEmpty() {
        val base = MainViewModel.FeedState()
        assertEquals(I10HomeNotice.PENDING, notice(base))
        assertEquals(I10HomeNotice.SETUP, notice(base.copy(availability = I07FeedAvailability.NEEDS_SETUP)))
        assertEquals(I10HomeNotice.OFFLINE, notice(base.copy(availability = I07FeedAvailability.OFFLINE)))
        assertEquals(I10HomeNotice.LOADING, notice(base.copy(sections = mapOf(I07FeedSection.ARTISTS to I07FeedStatus.LOADING))))
        assertEquals(I10HomeNotice.FAILED, notice(base.copy(sections = mapOf(I07FeedSection.ARTISTS to I07FeedStatus.FAILED))))
        assertEquals(I10HomeNotice.EMPTY, notice(base.copy(sections = mapOf(I07FeedSection.ARTISTS to I07FeedStatus.READY))))
    }

    @Test fun readyReleasesCannotHidePendingOrFailedArtistDependency() {
        val feed = MainViewModel.FeedState(sections = mapOf(I07FeedSection.RELEASES to I07FeedStatus.READY))
        assertEquals(I10HomeNotice.PENDING, notice(feed, I10HomeFilter.RELEASES, recent = true))
        assertEquals(I10HomeNotice.FAILED, notice(feed.copy(sections = feed.sections +
            (I07FeedSection.FROM_ARTISTS to I07FeedStatus.FAILED)), I10HomeFilter.RELEASES))
    }

    @Test fun gatewayShelfRemainsForYouWithoutOptionalWebApiAndOtherFiltersHaveOwnState() {
        val feed = MainViewModel.FeedState(availability = I07FeedAvailability.NEEDS_SETUP)
        assertNull(notice(feed, I10HomeFilter.ALL, shelves = true))
        assertNull(notice(feed, I10HomeFilter.FOR_YOU, shelves = true))
        assertEquals(I10HomeNotice.SETUP, notice(feed, I10HomeFilter.ARTISTS, shelves = true))
    }

    @Test fun unrelatedFailureDoesNotReplaceSuccessfulPersonalTracks() {
        val feed = MainViewModel.FeedState(topTracks = listOf(CatalogTrack("fixture:a", "Song", "Artist")),
            sections = mapOf(I07FeedSection.RELEASES to I07FeedStatus.FAILED))
        assertNull(notice(feed, I10HomeFilter.FOR_YOU))
        assertEquals(I10HomeNotice.FAILED, notice(feed, I10HomeFilter.ALL))
    }
}
