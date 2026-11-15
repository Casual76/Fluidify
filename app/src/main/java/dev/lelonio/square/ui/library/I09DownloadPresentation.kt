package dev.lelonio.square.ui.library

import dev.lelonio.square.data.OwnerState

internal enum class I09DownloadPhase { NONE, COMPLETE, PENDING, PARTIAL, READING, INCOMPLETE }

/** Source completeness and file completeness are independent; Partial(n,n) is never Complete. */
internal fun i09DownloadPhase(
    state: OwnerState, sourceIncomplete: Boolean, reading: Boolean, ownerPresent: Boolean = false,
): I09DownloadPhase =
    when {
        sourceIncomplete && reading -> I09DownloadPhase.READING
        sourceIncomplete -> I09DownloadPhase.INCOMPLETE
        state is OwnerState.Complete -> I09DownloadPhase.COMPLETE
        state is OwnerState.Running -> I09DownloadPhase.PENDING
        state is OwnerState.Partial || ownerPresent -> I09DownloadPhase.PARTIAL
        else -> I09DownloadPhase.NONE
    }

internal data class I09DownloadFailures(val count: Int, val retryPending: Boolean)

internal fun i09DownloadFailures(
    ownerTracks: List<String>, failures: Map<String, dev.lelonio.square.data.DownloadStore.Failure>,
    completed: Set<String>, online: Boolean,
): I09DownloadFailures {
    val missing = ownerTracks.distinct().filterNot { it in completed }.mapNotNull { failures[it] }
    return I09DownloadFailures(missing.size, online && missing.any {
        it.attempts < dev.lelonio.square.data.DownloadStore.MAX_ATTEMPTS
    })
}
