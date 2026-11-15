package dev.lelonio.square.ui.navigation

import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.ui.MainViewModel.PlaylistState

/** The production selection consumer shared by MainViewModel and synthetic-service regressions. */
internal class PlaylistEntrySelection(
    private val entries: PlaylistEntries,
    private val authority: () -> String?,
    private val stopRead: () -> Unit,
    private val changed: () -> Unit,
    private val reconcile: (PlaylistState) -> PlaylistState,
    private val open: (CatalogPlaylist) -> Unit,
) {
    @Volatile private var requested: CatalogPlaylist? = null
    @Volatile private var waitingId: String? = null
    fun prepare(playlist: CatalogPlaylist) { requested = playlist }

    fun select(id: String?, uri: String? = null, expectedAuthority: String? = null): Boolean {
        val entry = if (id == null) null else {
            if (uri.isNullOrBlank() || expectedAuthority != authority()) return false
            val descriptor = requested?.takeIf { it.uri == uri } ?: CatalogPlaylist(uri, "")
            entries.entry(id, descriptor).takeIf { it.live } ?: return false
        }
        requested = null
        if (entries.activeId == entry?.id) {
            if (entry != null && entry.needsRead && !entry.state.value.loading && !entry.state.value.loadingMore) {
                stopRead()
                open(entry.descriptor)
            }
            return true
        }
        stopRead()
        waitingId = null
        entries.select(entry)
        changed()
        if (entry != null) {
            entry.state.value = reconcile(entry.state.value)
            if (entry.needsRead) open(entry.descriptor)
        }
        return true
    }

    /** A denied online open remains an unfinished intention, with a terminal visible state. */
    fun awaitAvailability() { waitingId = entries.pending()?.id }

    /** Only the selected, still-live entry may recover; a covered page never steals selection. */
    fun recoverActive(): Boolean {
        val id = waitingId ?: return false
        if (entries.activeId != id) return false
        val entry = entries.pending() ?: return false
        if (!entry.live || entry.state.value.loading || entry.state.value.loadingMore) return false
        waitingId = null
        stopRead()
        open(entry.descriptor)
        return true
    }

    // May also run on the credential observer thread. The VM cancels its read job there;
    // generation mutation stays with the main-thread account collector.
    fun revoke() { requested = null; waitingId = null; entries.revoke() }
}
