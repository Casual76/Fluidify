package dev.lelonio.square.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A file owns its session generation and refresh mutex, including across store instances. */
class TokenStore internal constructor(
    private val prefs: SharedPreferences,
    storeKey: String,
    private val clientId: () -> String,
    private val refresh: suspend (String, String) -> SpotifyOAuth.Tokens,
    private val clock: () -> Long,
    private val localAuthority: LocalSpotifyAuthority? = null,
    private val refreshAdmitted: (suspend (String, String, dev.lelonio.square.data.SpotifyNetworkPolicy.Permit) -> SpotifyOAuth.Tokens)? = null,
    private val captureAccessGuard: (() -> (() -> Unit))? = null,
) {
    constructor(
        context: Context,
        fileName: String = FILE_NAME,
        clientId: () -> String = { SpotifyOAuth.CLIENT_ID },
        captureAccessGuard: (() -> (() -> Unit))? = null,
    ) : this(
        EncryptedSharedPreferences.create(
            context, fileName,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ),
        context.applicationContext.filesDir.absolutePath + "/" + fileName,
        clientId, { token, client -> SpotifyOAuth.refresh(token, client) }, System::currentTimeMillis,
        if (fileName == FILE_NAME) LocalSpotifyAuthority.forContext(context) else null,
        { token, client, permit -> SpotifyOAuth.refresh(token, client, permit) },
        captureAccessGuard,
    )

    private class Control {
        val refresh = Mutex()
        var generation = 0L
        val lost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
    private val control = synchronized(controls) { controls.getOrPut(storeKey) { Control() } }
    val sessionLost: SharedFlow<Unit> = control.lost.asSharedFlow()
    val generation: Long get() = synchronized(control) { control.generation }
    val isLoggedIn: Boolean get() = synchronized(control) {
        prefs.contains(KEY_REFRESH) || currentIfFresh() != null
    }
    val granted: Set<String> get() = synchronized(control) {
        prefs.getString(KEY_SCOPE, "").orEmpty().split(' ').filter { it.isNotEmpty() }.toSet()
    }

    /** New authentication replaces the whole session; refresh commits never call this. */
    fun save(tokens: SpotifyOAuth.Tokens) = withIdentityLock {
        changeIdentity {
            control.generation++
            write(tokens, replace = true)
        }
    }

    /** Authorisation callers can reject answers from an abandoned login. */
    fun saveIfCurrent(expected: Long, tokens: SpotifyOAuth.Tokens): Boolean =
        saveIfCurrent(expected, tokens) {}

    internal fun saveIfCurrent(expected: Long, tokens: SpotifyOAuth.Tokens, beforeSave: () -> Unit): Boolean = withIdentityLock {
        if (control.generation != expected) return@withIdentityLock false
        changeIdentity {
            beforeSave()
            control.generation++
            write(tokens, replace = true)
        }
        true
    }

    /** Interactive OAuth has the same late-answer hazard as refresh. Capture before waiting. */
    internal suspend fun authorizeIfCurrent(authorize: suspend () -> SpotifyOAuth.Tokens): Boolean {
        val request = synchronized(control) { control.generation to clientId() }
        val answer = authorize()
        currentCoroutineContext().ensureActive()
        return withIdentityLock {
            clientId() == request.second && saveIfCurrent(request.first, answer)
        }
    }

    fun clear(): Boolean = invalidate { }

    /** Mutate the client identity under the very same monitor as token checks/commits. */
    internal fun invalidate(changeClient: () -> Unit): Boolean = withIdentityLock {
        changeIdentity {
            control.generation++
            val result = prefs.edit().clear().commit()
            changeClient()
            result
        }
    }

    /** Pin a Web API call to the verified native identity and this token generation.
     * The external guard runs outside the token monitor; it never performs I/O.
     */
    internal fun requestGuard(expected: Long = generation): () -> Unit {
        val identity = captureAccessGuard?.invoke()
        val guard: () -> Unit = {
            synchronized(control) { checkGeneration(expected) }
            identity?.invoke()
            synchronized(control) { checkGeneration(expected) }
        }
        guard()
        return guard
    }

    suspend fun validAccessToken(
        networkPermit: dev.lelonio.square.data.SpotifyNetworkPolicy.Permit? = null,
        expectedGeneration: Long? = null,
    ): String {
        // An external request may predate service bootstrap as well as the refresh mutex.
        val expected = expectedGeneration ?: generation
        val guard = requestGuard(expected)
        val networkRevision = networkPermit?.revision ?: dev.lelonio.square.data.SpotifyNetworkPolicy.revision
        return control.refresh.withLock {
            val request = synchronized(control) {
                checkGeneration(expected)
                currentIfFresh()?.let { return@withLock it }
                Pair(prefs.getString(KEY_REFRESH, null) ?: error("not logged in"), clientId())
            }
            if (!dev.lelonio.square.data.SpotifyNetworkPolicy.isCurrent(networkRevision)) {
                throw dev.lelonio.square.data.SpotifyNetworkPolicy.Offline()
            }
            val permission = networkPermit ?: dev.lelonio.square.data.SpotifyNetworkPolicy.capture()
            guard()
            val tokens = try {
                // No JVM monitor is held during network suspension.
                refreshAdmitted?.invoke(request.first, request.second, permission)
                    ?: refresh(request.first, request.second)
            } catch (e: SpotifyOAuth.SessionExpired) {
                // A revoked request cannot turn a user network choice into logout.
                permission.check()
                guard()
                withIdentityLock {
                    checkGeneration(expected, request.second)
                    changeIdentity {
                        control.generation++
                        prefs.edit().clear().commit()
                        control.lost.tryEmit(Unit)
                    }
                }
                throw e
            }
            guard()
            val answer = synchronized(control) {
                checkGeneration(expected, request.second)
                // The admitted refresh may have rotated its credential. Keep that rotation
                // for the same account even if networking was revoked while it was in flight.
                write(tokens, replace = false)
                tokens.accessToken
            }
            permission.check()
            answer
        }.also { guard() }
    }

    /** Account observers may read tokens inline. All identity mutations take account before token. */
    private fun <T> withIdentityLock(block: () -> T): T =
        if (localAuthority != null) localAuthority.locked { synchronized(control) { block() } }
        else synchronized(control) { block() }

    private fun <T> changeIdentity(change: () -> T): T =
        if (localAuthority != null) localAuthority.invalidateBefore(change) else change()

    private fun checkGeneration(expected: Long, client: String? = null) {
        if (control.generation != expected || (client != null && clientId() != client)) {
            throw CancellationException("OAuth session changed")
        }
    }
    private fun write(tokens: SpotifyOAuth.Tokens, replace: Boolean) {
        check(prefs.edit().apply {
            if (replace) clear()
            putString(KEY_ACCESS, tokens.accessToken)
            tokens.refreshToken?.let { putString(KEY_REFRESH, it) }
            putLong(KEY_EXPIRES_AT, tokens.expiresAtMillis)
            if (tokens.scope.isNotEmpty()) putString(KEY_SCOPE, tokens.scope)
        }.commit()) { "Could not persist Spotify credentials" }
    }
    private fun currentIfFresh(): String? {
        val token = prefs.getString(KEY_ACCESS, null) ?: return null
        return token.takeIf { clock() < prefs.getLong(KEY_EXPIRES_AT, 0) - SKEW_MILLIS }
    }
    private companion object {
        const val FILE_NAME = "square_tokens"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_SCOPE = "scope"
        const val SKEW_MILLIS = 60_000L
        val controls = mutableMapOf<String, Control>()
    }
}
