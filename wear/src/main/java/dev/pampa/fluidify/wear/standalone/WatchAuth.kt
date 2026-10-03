package dev.pampa.fluidify.wear.standalone

import android.content.Context
import android.util.Log
import dev.pampa.fluidify.wear.link.PhoneLink
import dev.pampa.fluidify.wear.protocol.AccountState
import dev.pampa.fluidify.wear.protocol.AuthErrors
import dev.pampa.fluidify.wear.protocol.AuthGrant
import dev.pampa.fluidify.wear.protocol.AuthReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Where the watch stands with Spotify on its own. */
enum class AuthState {
    /** Its engine has a credential of its own and can sign in without the phone. */
    SIGNED_IN,

    /** Never signed in; the phone will be asked the first time the watch plays on its own. */
    NOT_YET,

    /** The phone is signed out, so the watch is too. */
    SIGNED_OUT,
}

/**
 * The watch's sign-in, which is the phone's sign-in passed on.
 *
 * The watch never holds the phone's refresh token (see
 * [dev.pampa.fluidify.wear.protocol.AuthRequest] for why). What it keeps is the
 * credential Spotify's access point issued to the watch's own engine after one
 * handshake with a token the phone lent it: a file in [credentialsDir], the
 * same reusable blob the phone keeps for itself.
 *
 * Signing out on the phone signs the watch out too, whether the watch hears it
 * at once ([WearPaths.AUTH_LOGOUT]) or the next time it connects
 * ([WearPaths.ACCOUNT]).
 */
class WatchAuth(
    private val context: Context,
    private val link: PhoneLink,
    private val prefs: StandalonePrefs,
) {
    /** Where the engine keeps its credential; handed to it at start. */
    val credentialsDir: File get() = context.filesDir.resolve("librespot")

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** True when the engine can sign in by itself. Same file the phone checks for its own. */
    val hasCredential: Boolean
        get() = credentialsDir.resolve("reusable").resolve("credentials.json").isFile

    /**
     * Something for the engine to log in with: an empty token when it already
     * has its own credential, a fresh one from the phone otherwise. Null when
     * the phone could not be asked or said no.
     */
    suspend fun tokenForStart(reason: AuthReason = AuthReason.FIRST_LOGIN): AuthGrant? {
        if (hasCredential && reason == AuthReason.FIRST_LOGIN) {
            return AuthGrant(id = 0, ok = true, accessToken = "", clientId = prefs.clientId)
        }
        val grant = link.requestAuth(reason) ?: return null
        if (!grant.ok) {
            Log.i(TAG, "phone declined: ${grant.error}")
            if (grant.error == AuthErrors.SIGNED_OUT) signOut()
            return grant
        }
        grant.username?.let { prefs.username = it }
        grant.clientId?.let { prefs.clientId = it }
        return grant
    }

    /** The engine signed in: from now on it has a credential of its own. */
    fun onEngineSignedIn() {
        _state.value = read()
    }

    /** The phone said who is signed in; a change of account or a sign-out drops the watch's credential. */
    fun onAccount(account: AccountState) {
        val known = prefs.username
        when {
            !account.signedIn -> signOut()
            known != null && account.username != null && account.username != known -> signOut()
            else -> {
                account.username?.let { prefs.username = it }
                _state.value = read()
            }
        }
    }

    /** Forgets the credential. Downloads stay; they play again only for the same account. */
    fun signOut() {
        credentialsDir.deleteRecursively()
        prefs.username = null
        _state.value = AuthState.SIGNED_OUT
    }

    private fun read(): AuthState = when {
        hasCredential -> AuthState.SIGNED_IN
        prefs.username == null && credentialsDir.exists() -> AuthState.SIGNED_OUT
        else -> AuthState.NOT_YET
    }

    private companion object {
        const val TAG = "WatchAuth"
    }
}
