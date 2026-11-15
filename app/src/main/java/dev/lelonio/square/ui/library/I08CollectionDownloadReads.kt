package dev.lelonio.square.ui.library

import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.DownloadStore
import dev.lelonio.square.data.SpotifyNetworkPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shares the already-running source read with a keep requested after its first page arrived. */
internal class CollectionDownloadReads {
    internal class Read(
        val uri: String,
        val access: AccountLocalDataCoordinator.Access,
        private val network: SpotifyNetworkPolicy.Permit,
    ) {
        private val result = CompletableDeferred<List<CatalogTrack>>()
        fun matches(uri: String, access: AccountLocalDataCoordinator.Access) = this.uri == uri && this.access === access
        fun valid(store: DownloadStore) = store.isOnlineCurrent(access) && SpotifyNetworkPolicy.isCurrent(network.revision)
        fun complete(tracks: List<CatalogTrack>) { network.check(); result.complete(tracks.toList()) }
        fun fail(error: Throwable) { result.completeExceptionally(error) }
        suspend fun await(): List<CatalogTrack> = result.await().also { network.check() }
    }

    private val reads = mutableMapOf<String, Read>()
    @Synchronized fun begin(uri: String, access: AccountLocalDataCoordinator.Access): Read =
        Read(uri, access, SpotifyNetworkPolicy.capture()).also { reads[uri] = it }
    @Synchronized fun current(uri: String, access: AccountLocalDataCoordinator.Access): Read? = reads[uri]?.takeIf { it.matches(uri, access) }
    @Synchronized fun release(read: Read) { if (reads[read.uri] === read) reads.remove(read.uri) }

    /**
     * No extra read, retries or network dispatch. The explicit keep creates its OWN fence after
     * the source read began; it never relies on that read having found an owner at its start.
     * Failure/cancellation leaves the known files and incomplete marker for the next normal sync.
     */
    suspend fun keep(
        store: DownloadStore,
        access: AccountLocalDataCoordinator.Access,
        uri: String,
        tracks: List<CatalogTrack>,
        label: DownloadStore.OwnerLabel,
        sourceComplete: Boolean,
        source: Read?,
        queued: () -> Unit,
    ): DownloadStore.OwnerRefreshResult {
        require(source == null || source.matches(uri, access)) { "Source belongs to another collection or account" }
        require(sourceComplete || source != null) { "No complete source or active reader" }
        // A cached complete snapshot can be on screen while a newer source is being checked.
        // Join that read too: otherwise a new owner can miss its later full result again.
        val completeSnapshot = sourceComplete && source == null
        val refresh = store.keepOwnerFromRead(access, uri, tracks, label, completeSnapshot,
            consent = { completeSnapshot || source?.valid(store) == true })
        queued()
        if (completeSnapshot) return DownloadStore.OwnerRefreshResult.UNCHANGED
        val complete = checkNotNull(source).await()
        currentCoroutineContext().ensureActive()
        return store.refreshOwner(refresh, complete, label)
    }
}
