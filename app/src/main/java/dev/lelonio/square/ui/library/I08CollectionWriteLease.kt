package dev.lelonio.square.ui.library

import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.lelonio.square.data.SpotifyNetworkPolicy

/** Original account and original network revision for one explicit collection write. */
internal class CollectionWriteLease private constructor(
    private val accounts: AccountLocalDataCoordinator,
    private val access: AccountLocalDataCoordinator.Access,
    private val network: SpotifyNetworkPolicy.Permit,
) {
    fun accountCurrent() = accounts.isOnlineCurrent(access)
    fun dispatch(write: () -> Unit) = network.dispatch {
        accounts.requireOnline(access)
        write()
        accounts.requireOnline(access)
    }
    fun confirm(commit: () -> Unit) = accounts.use(access) { network.admit(commit) }

    companion object {
        fun capture(accounts: AccountLocalDataCoordinator): CollectionWriteLease? {
            val access = accounts.captureOnline() ?: return null
            val network = try { SpotifyNetworkPolicy.capture() } catch (_: SpotifyNetworkPolicy.Offline) { return null }
            return CollectionWriteLease(accounts, access, network)
        }
    }
}
