package dev.lelonio.square.wear

import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.lelonio.square.data.SpotifyNetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One admitted request. Only the same durable LOCAL authority may become ONLINE. */
internal class I11WearRequestSession(
    private val accounts: AccountLocalDataCoordinator,
    private val initial: AccountLocalDataCoordinator.Access,
    private val requireNativeScope: Boolean = true,
) {
    @Volatile private var accepted = initial
    @Volatile private var network: SpotifyNetworkPolicy.Permit? = null

    private fun currentAccess(): AccountLocalDataCoordinator.Access? = accounts.locked {
        val now = accounts.capture() ?: return@locked null
        if (now === accepted) return@locked now
        if (accepted === initial && initial.origin == AccountLocalDataCoordinator.Origin.LOCAL &&
            now.origin == AccountLocalDataCoordinator.Origin.ONLINE && now.binding == initial.binding &&
            now.requestSession == initial.requestSession) {
            accepted = now
            now
        } else null
    }
    fun isCurrent(): Boolean = currentAccess() != null
    /** Used only while waking: permits the one same-authority verification, never a replacement login. */
    fun bootstrapCurrent(): Boolean = currentAccess() != null
    fun isOnlineCurrent(): Boolean = accounts.isOnlineCurrent(accepted) &&
        network?.let { SpotifyNetworkPolicy.isCurrent(it.revision) } == true
    fun access(): AccountLocalDataCoordinator.Access {
        return currentAccess() ?: throw AccountChanged()
    }

    suspend fun onlineAccess(budgetMs: Long, bootstrap: suspend (Long) -> Boolean): AccountLocalDataCoordinator.Access? {
        currentCoroutineContext().ensureActive()
        access()
        val permit = SpotifyNetworkPolicy.capture()
        if (accounts.isOnlineCurrent(accepted)) {
            network = permit
            return accepted
        }
        if (accepted.origin != AccountLocalDataCoordinator.Origin.LOCAL) return null
        val ready = withTimeoutOrNull(budgetMs) {
            if (!bootstrap(budgetMs)) return@withTimeoutOrNull null
            permit.check()
            // A live engine may precede the service's verification callback, or reconnect without a new UI visit.
            accounts.verifyNativeAccount()
            permit.check()
            // isConnected precedes canonical account verification. Wait for that verification too.
            accounts.access.first {
                currentCoroutineContext().ensureActive()
                permit.check()
                accounts.locked {
                    val candidate = currentAccess() ?: throw AccountChanged()
                    accounts.isOnlineCurrent(candidate)
                }
            }
        } ?: return null
        currentCoroutineContext().ensureActive()
        permit.check()
        accounts.locked {
            if (accounts.capture() !== ready || ready.binding != initial.binding ||
                ready.requestSession != initial.requestSession ||
                !accounts.isOnlineCurrent(ready)) throw AccountChanged()
            accepted = ready
            network = permit
        }
        return ready
    }

    /** Pin JNI's session under the account lock, then let logout/UI proceed during network I/O. */
    suspend fun <T> onlineIO(block: () -> T): T = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val current = access()
        val permit = checkNotNull(network)
        val caller = currentCoroutineContext()
        permit.dispatchWithAdmission(
            admission = { enter -> accounts.use(current) {
                accounts.requireOnline(current)
                enter()
            } },
            requireNativeScope = requireNativeScope,
        ) {
            caller.ensureActive()
            block()
        }
    }

    fun networkPermit(): SpotifyNetworkPolicy.Permit {
        accounts.requireOnline(access())
        return checkNotNull(network).also { it.check() }
    }

    fun <T> use(block: () -> T): T = accounts.use(access(), block)

    /** Binding the service can finish before its saved timeline is restored. Never acknowledge an empty play. */
    suspend fun awaitRestoredQueue(budgetMs: Long, hasQueue: () -> Boolean): Boolean =
        withTimeoutOrNull(budgetMs) {
            while (!hasQueue()) {
                access()
                delay(100)
            }
            access()
            true
        } ?: false

    class AccountChanged : IllegalStateException("Wear request account changed")
}
