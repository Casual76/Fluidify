package dev.lelonio.square.data

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prepared only. Real account-scoped store, synthetic metadata and storage, no JNI or provider. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RootDownloadOwnerLabelsTest {
    private val uri = "spotify:album:kept"
    private val track = CatalogTrack("spotify:track:a", "A", "Artist")

    @Test fun aMetadataOnlyRefreshUpdatesTheObservedCollectionWithoutChangingItsTracks() = runTest {
        val fixture = AccountLocalDataFixture()
        val access = fixture.connect("a")
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler),
            { file, text -> writeDownloadIndexAtomically(file, text) }, fixture.accounts)
        store.setOwner(access, uri, listOf(track), DownloadStore.OwnerLabel("Before", "old-cover"))
        val membership = store.owners.value
        val seen = mutableListOf<Map<String, DownloadStore.OwnerLabel>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.ownerLabels.collect { seen += it } }
        val token = store.beginOwnerRefresh(access, uri)!!
        val changed = DownloadStore.OwnerLabel("After", "new-cover")
        assertEquals(DownloadStore.OwnerRefreshResult.METADATA, store.refreshOwner(token, listOf(track), changed))
        assertEquals(membership, store.owners.value)
        assertEquals(changed, seen.last()[uri])
        assertEquals(changed, store.labelOf(uri))
        store.removeOwner(access, uri)
        assertFalse(store.ownerLabels.value.containsKey(uri))
    }

    @Test fun revocationClearsMetadataSynchronouslyAndReturningAccountRecoversOnlyItsOwnLabel() = runTest {
        val fixture = AccountLocalDataFixture()
        val a = fixture.connect("a")
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler),
            { file, text -> writeDownloadIndexAtomically(file, text) }, fixture.accounts)
        store.setOwner(a, uri, listOf(track), DownloadStore.OwnerLabel("A's album"))
        fixture.logout()
        assertTrue(store.ownerLabels.value.isEmpty())
        val b = fixture.connect("b")
        assertTrue(store.ownerLabels.value.isEmpty())
        store.setOwner(b, uri, listOf(track), DownloadStore.OwnerLabel("B's album"))
        assertEquals("B's album", store.ownerLabels.value[uri]?.name)
        fixture.connect("a")
        assertEquals("A's album", store.ownerLabels.value[uri]?.name)
    }
}
