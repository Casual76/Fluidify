package dev.lelonio.square.wear

import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.auth.SpotifyOAuth
import dev.lelonio.square.data.Catalog
import dev.pampa.fluidify.wear.protocol.AccountState
import dev.pampa.fluidify.wear.protocol.AuthErrors
import dev.pampa.fluidify.wear.protocol.AuthGrant
import dev.pampa.fluidify.wear.protocol.AuthRequest
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths

/**
 * Lends the watch what it needs to sign its own engine in, and tells it when
 * the phone signs out.
 *
 * What it lends is an access token, never the refresh token behind it: see
 * [AuthRequest] for why sharing that would sign one of the two devices out.
 * The token comes from the same [dev.lelonio.square.auth.TokenStore.validAccessToken]
 * the phone's own engine uses, under the same lock, so a refresh for the watch
 * can never race one for the phone.
 */
class WearAuthGranter(private val app: SquareApplication, private val link: WearLink) {

    suspend fun onRequest(nodeId: String, request: AuthRequest) {
        val grant = when {
            // The second: the engine's credential is still here but the app's own sign-in was
            // cleared, so no token can be minted — "unavailable" made the watch ask again forever.
            !app.spotifySignedIn || !app.tokenStore.isLoggedIn -> AuthGrant(request.id, ok = false, error = AuthErrors.SIGNED_OUT)
            else -> runCatching {
                val token = app.tokenStore.validAccessToken()
                AuthGrant(
                    id = request.id,
                    ok = true,
                    accessToken = token,
                    // The client every token in this app is minted for; the watch's engine
                    // must log in as the same one or the access point refuses it.
                    clientId = SpotifyOAuth.CLIENT_ID,
                    username = runCatching { Catalog.username() }.getOrNull(),
                    expiresAtEpochMs = System.currentTimeMillis() + TOKEN_LIFETIME_MS,
                )
            }.getOrElse { error ->
                Log.w(TAG, "no token for the watch: ${error.message}")
                AuthGrant(request.id, ok = false, error = AuthErrors.UNAVAILABLE)
            }
        }
        // A message, never a DataItem: those persist and may travel through the cloud.
        link.send(nodeId, WearPaths.AUTH_GRANT, WearCodec.encode(AuthGrant.serializer(), grant))
    }

    /** Who the phone is signed in as, for a watch that was away when it changed. */
    suspend fun publishAccount(signedIn: Boolean = app.spotifySignedIn) {
        val account = AccountState(
            signedIn = signedIn,
            username = if (signedIn) runCatching { Catalog.username() }.getOrNull() else null,
            changedAtEpochMs = System.currentTimeMillis(),
        )
        if (!link.hasWatch()) return
        // Written when it changes, not on every hello: the new timestamp alone made each one a
        // fresh item for the Data Layer to carry.
        val said = account.signedIn to account.username
        if (said == lastPublished) return
        val request = PutDataRequest.create(WearPaths.ACCOUNT)
            .setData(WearCodec.encode(AccountState.serializer(), account))
            .setUrgent()
        if (link.put(request)) lastPublished = said
    }

    @Volatile private var lastPublished: Pair<Boolean, String?>? = null

    /** The phone signed out: the watch drops its credential now if it can hear, or when it next connects. */
    suspend fun onSignedOut() {
        publishAccount(signedIn = false)
        link.broadcast(WearPaths.AUTH_LOGOUT, ByteArray(0))
    }

    private companion object {
        const val TAG = "WearAuth"

        /** Spotify's access tokens last an hour; say a little less. */
        const val TOKEN_LIFETIME_MS = 50 * 60_000L
    }
}
