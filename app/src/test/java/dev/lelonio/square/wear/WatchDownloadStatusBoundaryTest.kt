package dev.lelonio.square.wear

import dev.pampa.fluidify.wear.protocol.*
import org.junit.Assert.*
import org.junit.Test

class WatchDownloadStatusBoundaryTest {
    @Test fun statusIngressRejectsLegacyAndLateAAndRestoresOnlyAuthorizedOfflineInventory() {
        var account: PendingWatchDownloads.Scope? = PendingWatchDownloads.Scope("a", "a-1")
        val disk = mutableMapOf<String, String>()
        fun boundary() = WatchDownloadStatusBoundary({ account }, { captured, block -> if (captured == account) block() },
            { disk[it] }, { key, value -> disk[key] = value })
        val remote = boundary()
        fun send(namespace: String?, session: String?, title: String) = remote.onStatus(WearCodec.encode(
            WatchDownloads.serializer(), WatchDownloads(owners = listOf(WatchDownloadOwner("spotify:playlist:p", title)),
                accountSession = session, accountNamespace = namespace)))
        send(null, null, "legacy")
        assertNull(remote.status.value)
        send("a", "a-1", "A")
        assertEquals("A", remote.status.value!!.owners.single().title)
        account = PendingWatchDownloads.Scope("b", "b-1")
        remote.accountChanged()
        assertNull(remote.status.value)
        send("a", "a-1", "late A")
        assertNull(remote.status.value)
        send("b", "b-1", "B")
        account = PendingWatchDownloads.Scope("a", "a-2")
        val restarted = boundary()
        restarted.accountChanged() // Local authority, no online lookup or transport.
        assertEquals("A", restarted.status.value!!.owners.single().title)
        assertEquals("a-2", restarted.status.value!!.accountSession)
        account = null
        restarted.accountChanged()
        assertNull(restarted.status.value)
        assertEquals(setOf("a", "b"), disk.keys)
    }
}
