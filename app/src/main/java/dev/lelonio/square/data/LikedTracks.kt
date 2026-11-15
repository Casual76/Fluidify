package dev.lelonio.square.data

import dev.lelonio.square.auth.LocalSpotifyAuthority
import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Shared phone/Auto/Wear cache, including negative answers and pending reads, scoped to one login. */
class LikedTracks(
    private val gateway: Gateway,
    private val accounts: AccountLocalDataCoordinator? = null,
    private val lookup: suspend (List<String>) -> List<Boolean>? = gateway::inLibrary,
) {
    val known = MutableStateFlow<Set<String>>(emptySet())
    val state: StateFlow<Set<String>> get() = known.asStateFlow()
    private val gate = Any()
    private val asked = mutableMapOf<String, Any>()
    private val notSaved = mutableSetOf<String>()
    private var binding: LocalSpotifyAuthority.Binding? = accounts?.capture()?.binding
    private val observation = accounts?.observe { access -> synchronized(gate) {
        if (binding != access?.binding) {
            binding = access?.binding
            asked.clear()
            notSaved.clear()
            known.value = emptySet()
        }
    } }

    private fun <T> use(owner: AccountLocalDataCoordinator.Access?, block: () -> T): T =
        if (accounts == null) synchronized(gate) { block() }
        else accounts.use(checkNotNull(owner)) { synchronized(gate) { block() } }

    private enum class Answer { YES, NO, PENDING, READ }

    suspend fun isLiked(uri: String): Boolean? {
        if (!uri.startsWith("spotify:track:")) return null
        val owner = accounts?.capture()
        if (accounts != null && owner == null) return null
        val ticket = Any()
        when (use(owner) {
            when {
                uri in known.value -> Answer.YES
                uri in notSaved -> Answer.NO
                uri in asked -> Answer.PENDING
                else -> { asked[uri] = ticket; Answer.READ }
            }
        }) {
            Answer.YES -> return true
            Answer.NO -> return false
            Answer.PENDING -> return null
            Answer.READ -> Unit
        }
        try {
            if (accounts != null && !accounts.isOnlineCurrent(checkNotNull(owner))) return null
            val permit = SpotifyNetworkPolicy.capture()
            val answer = lookup(listOf(uri))?.firstOrNull()
            currentCoroutineContext().ensureActive()
            permit.check()
            if (accounts != null) accounts.requireOnline(checkNotNull(owner))
            return use(owner) {
                if (asked[uri] !== ticket) null
                else {
                    if (answer != null) confirmLocked(uri, answer)
                    answer
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        finally {
            // An obsolete question cannot erase B's pending lookup, even for the same URI.
            val release = {
                synchronized(gate) { if (asked[uri] === ticket) asked.remove(uri); Unit }
            }
            if (accounts == null) release()
            else accounts.locked {
                if (accounts.capture()?.binding == owner?.binding) release()
            }
        }
    }

    suspend fun set(uri: String, liked: Boolean) {
        val owner = accounts?.captureOnline()
        if (accounts != null && owner == null) throw CancellationException("Liked account unavailable")
        val permit = SpotifyNetworkPolicy.capture()
        withContext(Dispatchers.IO) {
            permit.dispatchWithAdmission(admission = { enter ->
                if (accounts == null) enter()
                else accounts.use(checkNotNull(owner)) { accounts.requireOnline(owner); enter() }
            }, requireNativeScope = accounts != null) { NativeBridge.setLiked(uri, liked) }
        }
        use(owner) { confirmLocked(uri, liked) }
    }

    /** The caller already admitted the write and its confirmation under the original account. */
    internal fun recordConfirmed(uri: String, liked: Boolean) {
        val owner = accounts?.capture()
        if (accounts != null && owner == null) return
        use(owner) { confirmLocked(uri, liked) }
    }

    private fun confirmLocked(uri: String, liked: Boolean) {
        if (liked) notSaved -= uri else notSaved += uri
        known.update { if (liked) it + uri else it - uri }
    }
}
