package dev.lelonio.square.ui.home

import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.requests.I07FeedAvailability
import dev.lelonio.square.ui.requests.I07FeedSection
import dev.lelonio.square.ui.requests.I07FeedStatus

internal enum class I10HomeFilter { ALL, FOR_YOU, LIBRARY, RELEASES, ARTISTS }
internal enum class I10HomeNotice { LOADING, SETUP, OFFLINE, FAILED, PENDING, EMPTY }

/** Match the actual rendered rows; recent local tracks do not stand in for new releases/artists. */
internal fun i10HomeNotice(
    filter: I10HomeFilter,
    feed: MainViewModel.FeedState,
    hasShelves: Boolean,
    hasPlaylists: Boolean,
    hasRecent: Boolean,
): I10HomeNotice? {
    if (filter == I10HomeFilter.ALL && hasShelves) return null
    val sections = when (filter) {
        I10HomeFilter.ALL -> I07FeedSection.entries.toSet()
        I10HomeFilter.FOR_YOU -> setOf(I07FeedSection.TOP_TRACKS, I07FeedSection.ALL_TIME,
            I07FeedSection.FROM_ARTISTS, I07FeedSection.RECENT_CONTEXTS)
        I10HomeFilter.RELEASES -> setOf(I07FeedSection.RELEASES, I07FeedSection.FROM_ARTISTS)
        I10HomeFilter.ARTISTS -> setOf(I07FeedSection.ARTISTS)
        I10HomeFilter.LIBRARY -> emptySet()
    }
    val populated = when (filter) {
        I10HomeFilter.ALL -> hasPlaylists || hasRecent || feed.newReleases.isNotEmpty() ||
            feed.topArtists.isNotEmpty() || feed.topTracks.isNotEmpty() || feed.allTimeTracks.isNotEmpty() ||
            feed.fromYourArtists.isNotEmpty() || feed.jumpBackIn.isNotEmpty()
        I10HomeFilter.FOR_YOU -> hasShelves || hasRecent || feed.topTracks.isNotEmpty() ||
            feed.allTimeTracks.isNotEmpty() || feed.fromYourArtists.isNotEmpty() || feed.jumpBackIn.isNotEmpty()
        I10HomeFilter.RELEASES -> feed.newReleases.isNotEmpty() || feed.fromYourArtists.isNotEmpty()
        I10HomeFilter.ARTISTS -> feed.topArtists.isNotEmpty()
        I10HomeFilter.LIBRARY -> hasPlaylists || hasRecent
    }
    if (populated) return if (sections.any { feed.sections[it] == I07FeedStatus.FAILED } &&
        feed.availability == I07FeedAvailability.AVAILABLE) I10HomeNotice.FAILED else null
    if (sections.isEmpty()) return I10HomeNotice.EMPTY
    return when {
        feed.availability == I07FeedAvailability.OFFLINE -> I10HomeNotice.OFFLINE
        feed.availability == I07FeedAvailability.NEEDS_SETUP -> I10HomeNotice.SETUP
        sections.any { feed.sections[it] == I07FeedStatus.LOADING } -> I10HomeNotice.LOADING
        sections.any { feed.sections[it] == I07FeedStatus.FAILED } -> I10HomeNotice.FAILED
        sections.all { feed.sections[it] == I07FeedStatus.READY } -> I10HomeNotice.EMPTY
        else -> I10HomeNotice.PENDING
    }
}
