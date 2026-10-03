package dev.lelonio.square.ui

/*
 * Where a queue item came from, carried in its MediaItem extras.
 *
 * Moved out of MainActivity into :core unchanged, values and package alike:
 * the player that reads them lives here now, and the watch builds queue items
 * too. Every file that imported them from dev.lelonio.square.ui still does.
 */

/** Key for the context URI carried in a media item's metadata extras. */
const val EXTRA_CONTEXT_URI = "dev.lelonio.square.CONTEXT_URI"

/** Whether that queue is the context in its own order; see SquareApp's `onPlay`. */
const val EXTRA_CONTEXT_ORDERED = "dev.lelonio.square.CONTEXT_ORDERED"

/**
 * Set by "add to queue": play this right after the current track rather than at
 * the end of the queue.
 */
const val EXTRA_PLAY_NEXT = "dev.lelonio.square.PLAY_NEXT"

/** What to show the listener: "Playlist · Estate 2025", "Ricerca". */
const val EXTRA_CONTEXT_LABEL = "dev.lelonio.square.CONTEXT_LABEL"

/** The first artist's own uri, so the player's second line can be opened. */
const val EXTRA_ARTIST_URI = "dev.lelonio.square.ARTIST_URI"

/** Every credited artist, in order, as two lists that line up. */
const val EXTRA_ARTIST_NAMES = "dev.lelonio.square.ARTIST_NAMES"
const val EXTRA_ARTIST_URIS = "dev.lelonio.square.ARTIST_URIS"
