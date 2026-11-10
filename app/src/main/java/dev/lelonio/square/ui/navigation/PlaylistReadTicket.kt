package dev.lelonio.square.ui.navigation

import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.lelonio.square.ui.MainViewModel.PlaylistState
import dev.lelonio.square.ui.requests.RequestOwnership
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/** Captures the entry AND authority at dispatch. Never consults a later reader's globals. */
internal class PlaylistReadTicket(
    private val ownership: RequestOwnership.Ticket,
    private val accounts: AccountLocalDataCoordinator,
    val access: AccountLocalDataCoordinator.Access?,
    val version: Long,
    private val target: MutableStateFlow<PlaylistState>,
    private val onReady: () -> Unit = {},
) {
    fun bind(job: Job?) = ownership.bind(job)
    fun ensureCurrent() {
        ownership.ensureCurrent()
        access?.let(accounts::requireOnline)
    }
    /** Publishes a late title through the same captured target as the rest of the read. */
    fun publishName(name: String, reconcile: (PlaylistState, Long) -> PlaylistState) {
        update { reconcile(it.copy(name = name), version) }
    }

    fun update(change: (PlaylistState) -> PlaylistState) {
        fun commit() {
            ensureCurrent()
            val updated = change(target.value)
            target.value = updated
            // Readiness belongs to the content, not to optional sibling metadata requests.
            if (!updated.loading && !updated.loadingMore) onReady()
        }
        if (access == null) commit() else accounts.use(access) { commit() }
    }
}
