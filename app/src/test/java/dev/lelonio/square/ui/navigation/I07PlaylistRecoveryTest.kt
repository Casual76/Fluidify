package dev.lelonio.square.ui.navigation

import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.ui.MainViewModel.PlaylistState
import org.junit.Assert.*
import org.junit.Test

/** PREPARED ONLY. The production selection consumer; no Android, service or network. */
class I07PlaylistRecoveryTest {
    @Test fun automaticOfflineOpenTerminatesAndOnlyActiveEntryRecoversOnce() {
        val entries = PlaylistEntries()
        val a = CatalogPlaylist("spotify:album:a", "A")
        val b = CatalogPlaylist("spotify:album:b", "B")
        var online = false
        val reads = mutableListOf<String>()
        lateinit var selection: PlaylistEntrySelection
        selection = PlaylistEntrySelection(entries, { "account-A" }, {}, {}, { it }, open = {
            if (!online) {
                entries.activeState.value = PlaylistState(uri = it.uri, loading = false, error = "Unavailable")
                selection.awaitAvailability()
            } else {
                reads += it.uri
                entries.activeState.value = PlaylistState(uri = it.uri, loading = false)
                entries.completed(entries.pending())
            }
        })
        selection.prepare(a); assertTrue(selection.select("a", a.uri, "account-A"))
        assertFalse(entries.activeState.value.loading)
        assertEquals("Unavailable", entries.activeState.value.error)
        selection.prepare(b); selection.select("b", b.uri, "account-A")
        online = true
        assertTrue(selection.recoverActive())
        assertEquals(listOf(b.uri), reads)
        assertFalse(selection.recoverActive())
        selection.select("a", a.uri, "account-A")
        assertEquals(listOf(b.uri, a.uri), reads)
        selection.select("a", a.uri, "account-A")
        assertEquals(2, reads.size) // completed same entry does not start a second read
    }

    @Test fun sameEntryMayRetryTerminalErrorButRevokeBlanksHeldEntryAndRejectsOldAuthority() {
        val entries = PlaylistEntries()
        var authority = "A"
        var stopped = 0
        var opened = 0
        val descriptor = CatalogPlaylist("spotify:playlist:a", "A")
        val selection = PlaylistEntrySelection(entries, { authority }, { stopped++ }, {}, { it }, {
            opened++
            entries.activeState.value = PlaylistState(uri = it.uri, loading = false, error = "temporary")
        })
        selection.prepare(descriptor); selection.select("a", descriptor.uri, "A")
        selection.select("a", descriptor.uri, "A")
        assertEquals(2, opened)
        selection.awaitAvailability()
        val held = entries.activeState
        selection.revoke(); authority = "B"
        assertEquals(2, stopped)
        held.value = PlaylistState(uri = descriptor.uri, error = "late old account response")
        assertNull(held.value.uri)
        assertFalse(selection.recoverActive())
        assertFalse(selection.select("a", descriptor.uri, "A"))
        assertEquals(2, opened)
    }
}
