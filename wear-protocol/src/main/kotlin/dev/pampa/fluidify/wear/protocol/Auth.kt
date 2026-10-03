package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.Serializable

/**
 * Signing the watch in without a second login, and without sharing what keeps
 * the phone signed in.
 *
 * Spotify's refresh token rotates on every use: if the phone and the watch both
 * held it, whichever refreshed second would find it dead and be signed out. So
 * the watch never sees it. It asks ([AuthRequest]) and the phone answers with a
 * short-lived access token ([AuthGrant]), refreshed on the phone's side under
 * the phone's own lock. The watch's engine uses that token once, to log in as a
 * device of its own, and is issued a credential of its own by Spotify's access
 * point — which is what it uses from then on. A refused credential is the only
 * reason it ever asks again.
 *
 * Messages only, both ways: a DataItem persists and can travel through Google's
 * servers, and a token has no business doing either.
 */
@Serializable
data class AuthRequest(
    val id: Long,
    val reason: AuthReason = AuthReason.FIRST_LOGIN,
)

@Serializable
enum class AuthReason {
    /** The watch has never signed in. */
    FIRST_LOGIN,

    /** Spotify refused the credential the watch was keeping. */
    CREDENTIAL_REFUSED,
}

@Serializable
data class AuthGrant(
    val id: Long,
    val ok: Boolean,
    /** Good for about an hour; the watch only needs it for one handshake. */
    val accessToken: String? = null,
    /** The client the token was minted for; the engine must log in as the same one. */
    val clientId: String? = null,
    /** The account's username, so the watch can say who it is signed in as. */
    val username: String? = null,
    val expiresAtEpochMs: Long = 0,
    /** Why not, when [ok] is false: signed out, no network, Premium needed. */
    val error: String? = null,
)

/**
 * Who the phone is signed in as, kept as a DataItem at [WearPaths.ACCOUNT].
 *
 * The one piece of account state allowed to persist: it carries no secret, and
 * it is how a watch that was off when the phone signed out still learns of it
 * the next time it connects, and drops its own credential.
 */
@Serializable
data class AccountState(
    val signedIn: Boolean,
    val username: String? = null,
    val changedAtEpochMs: Long = 0,
)

object AuthErrors {
    const val SIGNED_OUT = "signed-out"
    const val UNAVAILABLE = "unavailable"
}
