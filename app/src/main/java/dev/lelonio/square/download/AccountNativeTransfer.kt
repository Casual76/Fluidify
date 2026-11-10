package dev.lelonio.square.download

import dev.lelonio.square.data.AccountLocalDataCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Registers blocking Wear staging with the same real-return requirement as the download pump. */
internal class AccountNativeTransfer(
    private val scope: CoroutineScope,
    private val accounts: AccountLocalDataCoordinator,
    private val cancelNative: (String) -> Unit,
) {
    private val gate = Any()
    private val active = mutableMapOf<Job, String>()
    private var joining = 0

    suspend fun <T> transfer(access: AccountLocalDataCoordinator.Access, uri: String, block: () -> T): T = kotlinx.coroutines.coroutineScope {
        val network = dev.lelonio.square.data.SpotifyNetworkPolicy.capture()
        val context = currentCoroutineContext()
        val job = checkNotNull(context[Job])
        synchronized(gate) {
            context.ensureActive()
            check(joining == 0) { "Account teardown in progress" }
            accounts.requireOnline(access)
            active[job] = uri
        }
        val canceller = scope.launch {
            while (true) {
                val present = synchronized(gate) { job in active }
                if (!present) break
                if (!job.isActive || !accounts.isOnlineCurrent(access) ||
                    !dev.lelonio.square.data.SpotifyNetworkPolicy.isCurrent(network.revision)) {
                    // Repeat: native clears its cancellation flag on entry.
                    runCatching { cancelNative(uri) }
                    job.cancel()
                }
                delay(25)
            }
        }
        try {
            network.check()
            val result = network.dispatch(block)
            network.check()
            context.ensureActive()
            accounts.requireOnline(access)
            result
        } catch (failure: Exception) {
            context.ensureActive()
            throw failure
        } finally {
            synchronized(gate) { active.remove(job) }
            // No late native cancellation may touch a successor after this function returns.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { canceller.cancelAndJoin() }
        }
    }

    suspend fun stopAndJoin() {
        val jobs = synchronized(gate) {
            joining++
            active.keys.toList().also { list -> list.forEach { it.cancel() } }
        }
        try { jobs.forEach { it.join() } }
        finally { synchronized(gate) { joining-- } }
    }
}
