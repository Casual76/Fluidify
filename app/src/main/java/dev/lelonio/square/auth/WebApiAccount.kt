package dev.lelonio.square.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Optional Web API authority. A browser grant is never a playback account switch. */
class WebApiAccount internal constructor(
    private val prefs: SharedPreferences,
    private val authority: LocalSpotifyAuthority,
    tokenFactory: (() -> String, () -> (() -> Unit)) -> TokenStore,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
        LocalSpotifyAuthority.forContext(context),
        { client, guard -> TokenStore(context, FILE_NAME_TOKENS, client, guard) },
    )

    enum class IdentityProblem { VERIFY_AGAIN, DIFFERENT_ACCOUNT, NATIVE_ACCOUNT_REQUIRED }
    class IdentityMismatch : IllegalStateException("Web API belongs to a different Spotify account")
    class NativeAccountRequired : IllegalStateException("Sign in to playback before connecting Web API")

    private val _clientId = MutableStateFlow(prefs.getString(KEY_CLIENT_ID, null))
    val clientId: StateFlow<String?> = _clientId.asStateFlow()
    val tokens = tokenFactory({ _clientId.value ?: SpotifyOAuth.CLIENT_ID }, ::captureIdentityGuard)

    /** Legacy/unverified tokens stay gated; no account, download or Bluetooth data is migrated. */
    val identityProblem: IdentityProblem? get() {
        if (!tokens.isLoggedIn) return null
        val native = authority.binding() ?: return IdentityProblem.NATIVE_ACCOUNT_REQUIRED
        val owner = prefs.getString(KEY_ACCOUNT, null) ?: return IdentityProblem.VERIFY_AGAIN
        return if (owner == native.canonical) null else IdentityProblem.DIFFERENT_ACCOUNT
    }
    val isReady: Boolean get() = _clientId.value != null && tokens.isLoggedIn && identityProblem == null

    private fun captureIdentityGuard(): () -> Unit {
        val native = authority.binding() ?: throw NativeAccountRequired()
        val owner = prefs.getString(KEY_ACCOUNT, null)
        if (owner != native.canonical) throw IdentityMismatch()
        return {
            if (authority.binding() != native || prefs.getString(KEY_ACCOUNT, null) != owner) {
                throw CancellationException("Web API native account changed")
            }
        }
    }

    fun setClientId(value: String) {
        val trimmed = value.trim().ifEmpty { null }
        if (trimmed == _clientId.value) return
        tokens.invalidate {
            check(prefs.edit().putString(KEY_CLIENT_ID, trimmed).putString(KEY_ACCOUNT, null).commit())
            _clientId.value = trimmed
        }
    }

    fun disconnect() {
        check(tokens.invalidate { check(prefs.edit().putString(KEY_ACCOUNT, null).commit()) }) {
            "Could not discard the previous Web API grant"
        }
    }

    /** Candidate /me is read before saving any credential or publishing connected/personal data.
     * Native lineage, client and generation are rechecked after both asynchronous answers.
     */
    internal suspend fun authorizeIfCurrent(
        identify: suspend (String) -> String,
        authorize: suspend () -> SpotifyOAuth.Tokens,
    ): Boolean {
        val native = authority.binding() ?: throw NativeAccountRequired()
        val client = _clientId.value ?: error("Web API client missing")
        // A failed regrant must not leave an older grant presented as connected.
        disconnect()
        val expected = tokens.generation
        fun current() = authority.binding() == native && _clientId.value == client && tokens.generation == expected
        val answer = authorize()
        currentCoroutineContext().ensureActive()
        if (!current()) return false
        val candidate = identify(answer.accessToken)
        currentCoroutineContext().ensureActive()
        return authority.locked {
            if (!current()) return@locked false
            if (candidate.isBlank() || candidate != native.canonical) throw IdentityMismatch()
            tokens.saveIfCurrent(expected, answer) {
                check(prefs.edit().putString(KEY_ACCOUNT, candidate).commit())
            }
        }
    }

    private companion object {
        const val FILE_NAME = "spot_webapi"
        const val FILE_NAME_TOKENS = "spot_webapi_tokens"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_ACCOUNT = "verified_spotify_account"
    }
}
