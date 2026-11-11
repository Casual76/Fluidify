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
        val access = app.accountLocalData.captureRequest(request.accountSession)
            ?.takeIf { app.accountLocalData.isOnlineCurrent(it) }
        val grant = if (access == null) {
            AuthGrant(request.id, ok = false, error = AuthErrors.UNAVAILABLE)
        } else try {
            val token = app.tokenStore.validAccessToken()
            app.accountLocalData.requireOnline(access)
            AuthGrant(request.id, ok = true, accessToken = token, clientId = SpotifyOAuth.CLIENT_ID,
                username = access.account.id, expiresAtEpochMs = System.currentTimeMillis() + TOKEN_LIFETIME_MS,
                accountSession = access.requestSession)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { AuthGrant(request.id, ok = false, error = AuthErrors.UNAVAILABLE) }
        if (access != null && !app.accountLocalData.isOnlineCurrent(access)) return
        link.send(nodeId, WearPaths.AUTH_GRANT, WearCodec.encode(AuthGrant.serializer(), grant))
    }

    /** Who the phone is signed in as, for a watch that was away when it changed. */
    private val publication = kotlinx.coroutines.sync.Mutex()
    private var lastPublished: Triple<Boolean, String?, String?>? = null
    suspend fun publishAccount(signedIn: Boolean = app.accountLocalData.capture() != null) {
        publication.lock()
        try {
            val access = app.accountLocalData.capture()
            if (!signedIn && access != null) return // a delayed A logout cannot sign out B
            val account = AccountState(access != null, access?.account?.id, System.currentTimeMillis(),
                localDataSession = access?.requestSession, localDataNamespace = access?.namespace)
            if (!link.hasWatch()) return
            if (app.accountLocalData.capture() !== access) return
            val said = Triple(account.signedIn, account.username, account.localDataSession)
            if (said == lastPublished) return
            val request = PutDataRequest.create(WearPaths.ACCOUNT)
                .setData(WearCodec.encode(AccountState.serializer(), account)).setUrgent()
            if (link.put(request)) lastPublished = said
        } finally { publication.unlock() }
    }

    suspend fun onSignedOut() {
        if (app.accountLocalData.capture() != null) return
        publishAccount(signedIn = false)
        if (app.accountLocalData.capture() == null) link.broadcast(WearPaths.AUTH_LOGOUT, ByteArray(0))
    }

    private companion object {
        const val TAG = "WearAuth"

        /** Spotify's access tokens last an hour; say a little less. */
        const val TOKEN_LIFETIME_MS = 50 * 60_000L
    }
}
