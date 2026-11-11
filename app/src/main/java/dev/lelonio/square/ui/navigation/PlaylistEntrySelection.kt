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
    fun prepare(playlist: CatalogPlaylist) { requested = playlist }

    fun select(id: String?, uri: String? = null, expectedAuthority: String? = null): Boolean {
        val entry = if (id == null) null else {
            if (uri.isNullOrBlank() || expectedAuthority != authority()) return false
            val descriptor = requested?.takeIf { it.uri == uri } ?: CatalogPlaylist(uri, "")
            entries.entry(id, descriptor).takeIf { it.live } ?: return false
        }
        requested = null
        if (entries.activeId == entry?.id) return true
        stopRead()
        entries.select(entry)
        changed()
        if (entry != null) {
            entry.state.value = reconcile(entry.state.value)
            if (entry.needsRead) open(entry.descriptor)
        }
        return true
    }

    fun revoke() { requested = null; entries.revoke() }
}
