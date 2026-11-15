package dev.lelonio.square.wear

import com.google.android.gms.wearable.PutDataRequest
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.auth.SpotifyOAuth
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
        val session = access?.let { I11WearRequestSession(app.accountLocalData, it) }
        val tokenGeneration = app.tokenStore.generation
        val grant = kotlinx.coroutines.withTimeoutOrNull(16_000) {
            app.wearBridge.withPlaybackUse {
                i11WearAuthGrant(request, session,
                    bootstrap = { app.wearBridge.engineReady(it, checkNotNull(session)::bootstrapCurrent) },
                    token = { app.tokenStore.validAccessToken(checkNotNull(session).networkPermit(), expectedGeneration = tokenGeneration) },
                    tokenCurrent = { app.tokenStore.generation == tokenGeneration },
                    clientId = SpotifyOAuth.CLIENT_ID)
            }
        } ?: AuthGrant(request.id, ok = false, error = AuthErrors.UNAVAILABLE,
            accountSession = request.accountSession)
        val reply = if (grant.ok && session?.isOnlineCurrent() != true) {
            AuthGrant(request.id, ok = false, error = AuthErrors.UNAVAILABLE,
                accountSession = request.accountSession)
        } else grant
        link.send(nodeId, WearPaths.AUTH_GRANT, WearCodec.encode(AuthGrant.serializer(), reply))
        if (session != null && !session.isCurrent()) app.wearBridge.refreshWatchAccount()
    }

    /** Who the phone is signed in as, for a watch that was away when it changed. */
    private val publication = kotlinx.coroutines.sync.Mutex()
    private var lastPublished: Triple<Boolean, String?, String?>? = null
    suspend fun publishAccount(signedIn: Boolean = app.accountLocalData.capture() != null, force: Boolean = false) {
        publication.lock()
        try {
            val access = app.accountLocalData.capture()
            if (!signedIn && access != null) return // a delayed A logout cannot sign out B
            val account = AccountState(access != null, access?.account?.id, System.currentTimeMillis(),
                localDataSession = access?.requestSession, localDataNamespace = access?.namespace)
            if (!link.hasWatch()) return
            if (app.accountLocalData.capture() !== access) return
            val said = Triple(account.signedIn, account.username, account.localDataSession)
            if (!force && said == lastPublished) return
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

}
