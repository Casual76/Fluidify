package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Home's twenty distinct recent tracks, projected from the account's Room archive.
 * No independent play writer or persisted shelf: recording/deletion have one authority.
 */
class RecentStore(
    context: Context,
    private val history: ListeningRepository =
        (context.applicationContext as dev.lelonio.square.SquareApplication).listeningHistory,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    private val mutable = MutableStateFlow<List<CatalogTrack>>(emptyList())
    val tracks: StateFlow<List<CatalogTrack>> = mutable.asStateFlow()
    private var suppressed: Pair<HistoryAccess?, Long>? = null
    private val observer: AutoCloseable
    private val job: kotlinx.coroutines.Job

    init {
        // Synchronous revocation: a queued old-account read cannot keep Home populated.
        observer = history.observeSession { mutable.value = emptyList() }
        job = scope.launch {
            combine(history.session, history.archiveChanges) { session, revision ->
                session.access to revision
            }.collectLatest { (access, revision) ->
                if (access == null) return@collectLatest
                try {
                    val result = history.recentArchiveTracks(access, revision)
                    history.publishIfCurrent(access, revision) {
                        if (suppressed != (access to revision)) mutable.value = result
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Local read failed/revoked; never keep a shelf from an invalid account.
                    history.publishIfCurrent(access, revision) { mutable.value = emptyList() }
                }
            }
        }
    }

    /** Compatibility with existing UI callers; PlaybackService/ListeningRecorder is the sole writer. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun record(track: CatalogTrack) = Unit

    /** Logout compatibility: hide this projection without deleting retained account history. */
    fun clear() = synchronized(history) {
        suppressed = history.session.value.access to history.archiveChanges.value
        mutable.value = emptyList()
    }

    override fun close() {
        job.cancel()
        observer.close()
        clear()
    }
}
