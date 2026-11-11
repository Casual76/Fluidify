package dev.lelonio.square.data

import dev.lelonio.square.auth.LocalSpotifyAuthority
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local authority survives restart; only a separate live verification can admit online work. */
class AccountLocalDataCoordinator internal constructor(
    private val historyAccess: () -> HistoryAccess?,
    private val nativeEpoch: () -> Long?,
    private val nativeUsername: suspend () -> String,
    private val authority: LocalSpotifyAuthority,
    private val nativeReady: () -> Boolean,
) {
    constructor(history: ListeningRepository, authority: LocalSpotifyAuthority,
        nativeEpoch: () -> Long?, nativeReady: () -> Boolean) : this(
        { history.session.value.access }, nativeEpoch, { Catalog.username() }, authority, nativeReady,
    )

    enum class Origin { LOCAL, ONLINE }
    class Access internal constructor(
        internal val authority: AccountLocalDataCoordinator,
        internal val binding: LocalSpotifyAuthority.Binding,
        val origin: Origin,
        internal val history: HistoryAccess? = null,
        internal val epoch: Long? = null,
    ) {
        val account = HistoryAccount(binding.canonical)
        internal val namespace = localAccountKey(account)
        /** Opaque current-process correlation, never an online/provider proof. */
        val requestSession: String = UUID.randomUUID().toString()
    }

    private val mutable = MutableStateFlow<Access?>(null)
    val access = mutable.asStateFlow()
    private val observers = mutableListOf<(Access?) -> Unit>()

    init {
        authority.observe { authority.locked { changeLocked(null) } }
        authority.locked {
            authority.binding()?.let { changeLocked(Access(this, it, Origin.LOCAL)) }
        }
    }

    /** No offline path calls this function or manufactures HistoryAccess. */
    suspend fun verifyNativeAccount(): Boolean {
        val captured = authority.locked {
            validLocked()
            Triple(historyAccess(), nativeEpoch(), authority.verification())
        }
        val history = captured.first ?: run { return false }
        val epoch = captured.second ?: run { return false }
        val proof = captured.third ?: run { return false }
        if (!nativeReady()) { return false }
        val canonical = nativeUsername()
        return authority.locked {
            if (!authority.isCurrent(proof)) { return@locked false }
            if (!nativeReady()) { return@locked false }
            if (historyAccess() != history) { return@locked false }
            if (nativeEpoch() != epoch) { return@locked false }
            if (canonical.isBlank() || canonical != history.account.id || canonical != proof.identity.canonical) {
                authority.invalidate()
                return@locked false
            }
            val old = validLocked()
            if (old != null && old.origin == Origin.ONLINE && old.history == history && old.epoch == epoch) {
                return@locked true
            }
            val binding = authority.confirm(proof, canonical) ?: run { return@locked false }
            changeLocked(Access(this, binding, Origin.ONLINE, history, epoch))
            true
        }
    }

    /** Persistent revocation, before logout, interactive authentication or credential mutations. */
    fun revoke() = authority.invalidate()

    fun historyChanged() = authority.locked { validLocked(); Unit }
    fun capture(): Access? = authority.locked { validLocked() }
    fun captureOnline(): Access? = authority.locked { validLocked()?.takeIf(::onlineLocked) }
    fun isCurrent(access: Access) = authority.locked { validLocked() === access }
    fun isOnlineCurrent(access: Access) = authority.locked { validLocked() === access && onlineLocked(access) }

    /** Missing/stale wire sessions are denied, including requests queued before a process death. */
    fun captureRequest(session: String?): Access? = authority.locked {
        validLocked()?.takeIf { session != null && it.requestSession == session }
    }

    internal fun observe(observer: (Access?) -> Unit): AutoCloseable = authority.locked {
        observers += observer
        observer(validLocked())
        AutoCloseable { authority.locked { observers.remove(observer); Unit } }
    }

    internal fun <T> use(access: Access, block: () -> T): T = authority.locked {
        if (access.authority !== this || validLocked() !== access) throw revoked()
        block()
    }
    fun requireOnline(access: Access) = authority.locked {
        if (validLocked() !== access || !onlineLocked(access)) throw revoked()
    }
    internal fun <T> locked(block: () -> T): T = authority.locked(block)

    private fun onlineLocked(value: Access) = value.origin == Origin.ONLINE && nativeReady() &&
        historyAccess() == value.history && nativeEpoch() == value.epoch

    private fun validLocked(): Access? {
        val current = mutable.value ?: return null
        if (authority.binding() != current.binding) {
            changeLocked(null)
            return null
        }
        val history = historyAccess()
        if (history != null && history.account != current.account) {
            authority.invalidate()
            return null
        }
        if (current.origin == Origin.ONLINE &&
            (history != current.history || nativeEpoch() != current.epoch)) {
            if (history == null || nativeEpoch() != current.epoch) {
                authority.invalidate()
                return null
            }
            // History recording/delete generations do not erase local files or enable recording.
            changeLocked(Access(this, current.binding, Origin.LOCAL))
            return mutable.value
        }
        return current
    }

    private fun changeLocked(value: Access?) {
        mutable.value = value
        observers.toList().forEach { it(value) }
    }
    private fun revoked() = CancellationException("Spotify local account changed or online authority absent")
}

internal fun localAccountKey(account: HistoryAccount): String = MessageDigest.getInstance("SHA-256")
    .digest("${account.service}\u0000${account.id}".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
