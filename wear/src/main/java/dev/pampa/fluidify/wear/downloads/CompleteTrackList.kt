package dev.pampa.fluidify.wear.downloads

import dev.pampa.fluidify.wear.protocol.ContextPage

/** Incomplete or changing pagination must never remove an existing offline track. */
object CompleteTrackList {
    suspend fun read(maxTracks: Int, pageSize: Int, fetch: suspend (Int, Int) -> ContextPage?): Pair<String?, List<String>>? {
        val tracks = mutableListOf<String>()
        var offset = 0
        var total: Int? = null
        var title: String? = null
        while (true) {
            val page = fetch(offset, pageSize) ?: return null
            if (page.unavailableReason != null) return null
            if (page.total < 0 || page.total > maxTracks || (total != null && page.total != total)) return null
            total = page.total
            title = title ?: page.title.takeIf { it.isNotEmpty() }
            tracks += page.tracks.map { it.uri }.filter { it.startsWith("spotify:track:") }
            offset += page.tracks.size
            if (offset == total) return title to tracks
            if (page.tracks.isEmpty() || offset > total) return null
        }
    }
}
