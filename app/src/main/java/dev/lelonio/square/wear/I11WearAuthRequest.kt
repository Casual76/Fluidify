package dev.lelonio.square.wear

import dev.pampa.fluidify.wear.protocol.AuthErrors
import dev.pampa.fluidify.wear.protocol.AuthGrant
import dev.pampa.fluidify.wear.protocol.AuthRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** The reply keeps the request's wire correlation; bootstrap never borrows another login's token. */
internal suspend fun i11WearAuthGrant(
    request: AuthRequest,
    session: I11WearRequestSession?,
    bootstrap: suspend (Long) -> Boolean,
    token: suspend () -> String,
    tokenCurrent: () -> Boolean,
    clientId: String,
    now: () -> Long = System::currentTimeMillis,
    budgetMs: Long = 16_000,
): AuthGrant {
    fun refused() = AuthGrant(request.id, ok = false, error = AuthErrors.UNAVAILABLE,
        accountSession = request.accountSession)
    if (session == null) return refused()
    return try {
        withTimeoutOrNull(budgetMs) {
            val online = session.onlineAccess(7_000, bootstrap) ?: return@withTimeoutOrNull refused()
            if (!tokenCurrent()) return@withTimeoutOrNull refused()
            val credential = token()
            currentCoroutineContext().ensureActive()
            if (!session.isOnlineCurrent() || !tokenCurrent()) return@withTimeoutOrNull refused()
            AuthGrant(request.id, ok = true, accessToken = credential, clientId = clientId,
                username = online.account.id, expiresAtEpochMs = now() + 50 * 60_000L,
                accountSession = request.accountSession)
        } ?: refused()
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        refused() // A domain/session cancellation must receive a negative reply.
    } catch (_: Exception) { refused() }
}
