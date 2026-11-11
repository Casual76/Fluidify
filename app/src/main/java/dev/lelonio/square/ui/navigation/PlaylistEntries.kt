package dev.lelonio.square.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.ui.MainViewModel.PlaylistState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User text stays in memory, including while Engine removes a covered page from composition. */
class PlaylistPageMemory {
    // Standalone screens are selected by default; Navigation sets this on committed selection.
    @Volatile internal var selected = true
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var searchReady by mutableStateOf(false)
    internal fun clear() { selected = false; query = ""; searching = false; searchReady = false }
}

/**
 * Entry state, NOT a URI cache or a second navigation stack. Navigation owns ordering and lifetime.
 * The existing reader works on [activeState]; every page renders its own [state]. Covered entries
 * are immutable to that reader. Revocation also blanks flows already held by a composed page.
 */
internal class PlaylistEntries {
    internal class Entry(val id: String, val descriptor: CatalogPlaylist) {
        // URI is present before the first composition, including process restoration. Otherwise
        // rememberSaveable inputs would change null -> URI and discard the restored viewport.
        private val mutable = MutableStateFlow(PlaylistState(uri = descriptor.uri, name = descriptor.name,
            artworkUrl = descriptor.artworkUrl, loading = true))
        @Volatile var live = true
        var needsRead = true
        val memory = PlaylistPageMemory().also { it.selected = false }
        val state: MutableStateFlow<PlaylistState> = object : MutableStateFlow<PlaylistState> by mutable {
            override var value: PlaylistState
                get() = mutable.value
                set(value) { synchronized(this@Entry) { if (live) mutable.value = value } }
            override fun tryEmit(value: PlaylistState): Boolean { this.value = value; return true }
            override suspend fun emit(value: PlaylistState) { this.value = value }
            override fun compareAndSet(expect: PlaylistState, update: PlaylistState): Boolean =
                synchronized(this@Entry) {
                    // A tombstone accepts a no-op, so MutableStateFlow.update cannot spin forever.
                    if (live) mutable.compareAndSet(expect, update) else mutable.value == expect
                }
        }
        fun revoke() = synchronized(this) {
            live = false
            mutable.value = PlaylistState()
            memory.clear()
        }
    }

    private val entries = mutableMapOf<String, Entry>()
    private val empty = MutableStateFlow(PlaylistState())
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    @Volatile private var selected: Entry? = null
    val activeState: MutableStateFlow<PlaylistState> get() = selected?.state ?: empty
    val activeId: String? get() = selected?.id

    @Synchronized fun entry(id: String, descriptor: CatalogPlaylist): Entry =
        entries.getOrPut(id) { Entry(id, descriptor) }

    @Synchronized fun select(entry: Entry?): Boolean {
        if (selected === entry) return false
        selected?.memory?.selected = false
        selected = entry?.takeIf { it.live }
        selected?.memory?.selected = true
        empty.value = PlaylistState()
        return true
    }

    @Synchronized fun state(id: String): StateFlow<PlaylistState> =
        checkNotNull(entries[id]).state.asStateFlow()

    @Synchronized fun memory(id: String): PlaylistPageMemory = checkNotNull(entries[id]).memory
    @Synchronized fun pending(): Entry? = selected?.also { it.needsRead = true }
    @Synchronized fun completed(entry: Entry?) { if (entry?.live == true) entry.needsRead = false }
    @Synchronized fun invalidate(uri: String) {
        entries.values.filter { it.descriptor.uri == uri }.forEach { it.needsRead = true }
    }
    @Synchronized fun release(id: String) {
        entries.remove(id)?.let { if (selected === it) selected = null; it.revoke() }
    }
    @Synchronized fun revoke() {
        entries.values.forEach { it.revoke() }
        // Keep tombstones until Navigation clears its leases: old entries cannot be rebound.
        selected = null
        empty.value = PlaylistState()
        mutableRevision.value++
    }
}
