package dev.lelonio.square.data

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prepared, NOT EXECUTED. Requires I09-DownloadStore.kt.patch to be integrated by ROOT first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class I09DownloadRecoveryTest {
    private val owner = "spotify:playlist:a"
    private val a = CatalogTrack("spotify:track:a", "A", "Artist", addedAt = "2020-01-01T00:00:00Z")
    private val b = CatalogTrack("spotify:track:b", "B", "Artist", addedAt = "2026-01-01T00:00:00Z")
    private val c = CatalogTrack("spotify:track:c", "C", "Artist")
    private val record = DownloadStore.FileRecord("aabb", "OGG_320", 42, 320, 123)

    @Test fun retryOnlyMissingFilesKeepsSourceFenceOccurrencesCompletedFilesAndOtherFailures() = runTest {
        val authority = AccountLocalDataFixture()
        val access = authority.connect("a")
        val context = accountDataTestContext()
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler),
            { file, text -> writeDownloadIndexAtomically(file, text) }, authority.accounts)
        store.setOwner(access, "other", listOf(c), DownloadStore.OwnerLabel("Other"))
        val tracks = listOf(a, a.copy(addedAt = b.addedAt), b)
        val token = store.keepOwnerFromRead(access, owner, tracks, DownloadStore.OwnerLabel("A"), sourceComplete = false)
        store.onCompleted(access, a.uri, record)
        repeat(DownloadStore.MAX_ATTEMPTS) { store.onFailed(access, b.uri, "synthetic unavailable") }
        store.onFailed(access, c.uri, "other owner")
        val otherFailure = store.failures.value.getValue(c.uri)
        assertTrue(store.retryOwnerFailed(access, owner))
        assertEquals(tracks, store.tracksForOwner(owner))
        assertEquals(record, store.files.value[a.uri])
        assertEquals(mapOf(c.uri to otherFailure), store.failures.value)
        assertEquals(setOf(owner), store.incompleteOwners.value)
        assertFalse(store.retryOwnerFailed(access, owner))
        // Retry must not replace the owner: the pending source can still finish with its original fence.
        assertEquals(DownloadStore.OwnerRefreshResult.METADATA,
            store.refreshOwner(token, tracks, DownloadStore.OwnerLabel("A")))
        assertTrue(store.incompleteOwners.value.isEmpty())
        val reopened = DownloadStore(context, StandardTestDispatcher(testScheduler),
            { file, text -> writeDownloadIndexAtomically(file, text) }, authority.accounts)
        reopened.load()
        assertEquals(tracks, reopened.tracksForOwner(owner))
        assertEquals(record, reopened.files.value[a.uri])
        assertEquals(mapOf(c.uri to otherFailure), reopened.failures.value)
    }

    @Test fun removedOwnerCannotBeResurrectedByRetry() = runTest {
        val authority = AccountLocalDataFixture()
        val access = authority.connect("a")
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler),
            { file, text -> writeDownloadIndexAtomically(file, text) }, authority.accounts)
        store.setOwner(access, owner, listOf(b), DownloadStore.OwnerLabel("A"))
        store.onFailed(access, b.uri, "synthetic failure")
        store.removeOwner(access, owner)
        assertFalse(store.retryOwnerFailed(access, owner))
        assertFalse(store.owners.value.containsKey(owner))
    }

    @Test fun staleAccountRetryCannotClearNewAccountsFailures() = runTest {
        val authority = AccountLocalDataFixture()
        val old = authority.connect("a")
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler),
            { file, text -> writeDownloadIndexAtomically(file, text) }, authority.accounts)
        store.setOwner(old, owner, listOf(b), DownloadStore.OwnerLabel("A"))
        store.onFailed(old, b.uri, "A failed")
        val current = authority.connect("b")
        store.setOwner(current, owner, listOf(b), DownloadStore.OwnerLabel("B"))
        store.onFailed(current, b.uri, "B failed")
        val failures = store.failures.value
        assertTrue(runCatching { store.retryOwnerFailed(old, owner) }.isFailure)
        assertEquals(failures, store.failures.value)
        assertEquals(listOf(b), store.tracksForOwner(owner))
    }
}
