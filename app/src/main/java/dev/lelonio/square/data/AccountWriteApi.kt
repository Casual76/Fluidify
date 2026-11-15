package dev.lelonio.square.data

import dev.lelonio.square.auth.TokenStore
import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A mutation carries one account, network revision and fixed credential through the HTTP queue. */
internal suspend fun accountWriteApi(
    accounts: AccountLocalDataCoordinator,
    access: AccountLocalDataCoordinator.Access,
    tokens: TokenStore,
    scopes: String,
    permit: SpotifyNetworkPolicy.Permit = SpotifyNetworkPolicy.capture(),
    nativeToken: (String) -> String? = NativeBridge::webToken,
    requireNativeScope: Boolean = true,
): SpotifyApi {
    val tokenGeneration = tokens.generation
    val native = withContext(Dispatchers.IO) {
        permit.dispatchWithAdmission(admission = { enter -> accounts.use(access) {
            accounts.requireOnline(access)
            enter()
        } }, requireNativeScope = requireNativeScope) { nativeToken(scopes) }
    }
    currentCoroutineContext().ensureActive()
    accounts.requireOnline(access)
    permit.check()
    val credential = native ?: tokens.validAccessToken(permit, expectedGeneration = tokenGeneration)
    currentCoroutineContext().ensureActive()
    accounts.requireOnline(access)
    permit.check()
    return playlistWriteApi(credential, permit) { accounts.requireOnline(access) }
}
