package dev.lelonio.square.ui.requests

import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.lelonio.square.data.SpotifyNetworkPolicy
import kotlinx.coroutines.CancellationException

/** One captured account capability AND network revision, never just a matching account name. */
internal class I07RequestProof(
    val owner: Any,
    private val check: () -> Unit,
    private val commit: (() -> Unit) -> Unit,
) {
    fun ensureCurrent() = check()
    fun publish(block: () -> Unit) = commit { check(); block() }

    companion object {
        fun online(
            accounts: AccountLocalDataCoordinator,
            access: AccountLocalDataCoordinator.Access,
            network: SpotifyNetworkPolicy.Permit,
            selectionCurrent: () -> Boolean = { true },
        ) = I07RequestProof(access, check = {
            accounts.requireOnline(access)
            network.check()
            if (!selectionCurrent()) throw CancellationException("Listening selection changed")
        }, commit = { block -> accounts.use(access) { network.admit(block) } })
    }
}
