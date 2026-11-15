package dev.lelonio.square.auth

import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.lelonio.square.data.SpotifyNetworkPolicy

/** Publication and each next page belong to the same native capability AND Web API grant. */
internal class I10WebReadProof private constructor(
    private val accounts: AccountLocalDataCoordinator,
    private val access: AccountLocalDataCoordinator.Access,
    private val permit: SpotifyNetworkPolicy.Permit,
    private val tokenGuard: () -> Unit,
) {
    val accountId: String get() = access.account.id

    fun ensureCurrent() {
        accounts.requireOnline(access)
        permit.check()
        tokenGuard()
    }

    fun publish(block: () -> Unit) = accounts.use(access) {
        permit.admit { ensureCurrent(); block() }
    }

    companion object {
        fun capture(accounts: AccountLocalDataCoordinator, tokens: TokenStore): I10WebReadProof? {
            val access = accounts.captureOnline() ?: return null
            return try {
                I10WebReadProof(accounts, access, SpotifyNetworkPolicy.capture(), tokens.requestGuard())
                    .also { it.ensureCurrent() }
            } catch (_: Exception) { null }
        }
    }
}
