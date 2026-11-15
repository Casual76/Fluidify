package dev.lelonio.square.auth

import android.app.Application
import android.content.Context
import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.data.accountDataTestContext
import java.util.UUID
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PREPARED, NOT EXECUTED. Real production stores/authority, synthetic profiles and delayed jobs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class I10WebApiIdentityTest {
    private val native = AccountLocalDataFixture()
    private val context = accountDataTestContext()
    private val ownerPrefs = context.getSharedPreferences("i10-web", Context.MODE_PRIVATE)
    private val tokenPrefs = context.getSharedPreferences("i10-token", Context.MODE_PRIVATE)
    private val refreshEntered = CompletableDeferred<Unit>()
    private val refreshAnswer = CompletableDeferred<SpotifyOAuth.Tokens>()
    private val key = UUID.randomUUID().toString()
    private val web = WebApiAccount(ownerPrefs, native.authority) { client, guard ->
        TokenStore(tokenPrefs, key, client, { _, _ ->
            refreshEntered.complete(Unit)
            withContext(NonCancellable) { refreshAnswer.await() }
        }, { 100L }, captureAccessGuard = guard)
    }
    private fun token(id: String, fresh: Boolean = true) =
        SpotifyOAuth.Tokens("candidate-$id", "refresh-$id", if (fresh) 1_000_000 else 0)
    private suspend fun connect(id: String = "account-a", fresh: Boolean = true): Boolean =
        web.authorizeIfCurrent(identify = { id }) { token(id, fresh) }

    @After fun reset() { SpotifyNetworkPolicy.setManual(false); native.logout() }

    @Test fun differentBrowserProfileIsRejectedBeforeStorageAndPersonalReaders() = runBlocking {
        native.connect("account-a"); web.setClientId("client")
        val playbackBefore = native.authority.binding()
        var reads = 0
        var identifiedToken: String? = null
        val result = runCatching {
            if (web.authorizeIfCurrent(identify = { identifiedToken = it; "account-b" }) { token("account-b") }) reads++
        }
        assertTrue(result.exceptionOrNull() is WebApiAccount.IdentityMismatch)
        assertEquals("candidate-account-b", identifiedToken)
        assertEquals(0, reads)
        assertFalse(web.isReady)
        assertFalse(web.tokens.isLoggedIn)
        assertEquals(playbackBefore, native.authority.binding())
        assertEquals("account-a", native.accounts.captureOnline()!!.account.id)
        assertNull(tokenPrefs.getString("access_token", null))
    }

    @Test fun matchingProfileIsVerifiedBeforeGrantBecomesUsable() = runBlocking {
        native.connect(); web.setClientId("client")
        assertTrue(web.authorizeIfCurrent(identify = {
            assertFalse(web.isReady)
            assertFalse(web.tokens.isLoggedIn)
            "account-a"
        }) { token("account-a") })
        assertTrue(web.isReady)
        assertEquals("candidate-account-a", web.tokens.validAccessToken())
    }

    @Test fun legacyTokensCannotReadPersonalDataBeforeIdentityVerification() = runBlocking {
        native.connect(); web.setClientId("client")
        web.tokens.save(token("unknown-old-profile"))
        assertFalse(web.isReady)
        assertEquals(WebApiAccount.IdentityProblem.VERIFY_AGAIN, web.identityProblem)
        assertTrue(runCatching { web.tokens.validAccessToken() }.exceptionOrNull() is WebApiAccount.IdentityMismatch)
        assertNull(I10WebReadProof.capture(native.accounts, web.tokens))
    }

    @Test fun lateOAuthAfterNativeSwitchCannotStartProfileRead() = runBlocking {
        native.connect(); web.setClientId("client")
        val entered = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<SpotifyOAuth.Tokens>()
        var profiles = 0
        val old = async {
            web.authorizeIfCurrent(identify = { profiles++; "account-a" }) {
                entered.complete(Unit); withContext(NonCancellable) { answer.await() }
            }
        }
        entered.await(); native.connect("account-b")
        assertTrue(connect("account-b"))
        answer.complete(token("account-a"))
        assertFalse(old.await())
        assertEquals(0, profiles)
        assertEquals("candidate-account-b", web.tokens.validAccessToken())
    }

    @Test fun lateProfileCannotEraseOrReplaceNewlyVerifiedGrant() = runBlocking {
        native.connect(); web.setClientId("client")
        val entered = CompletableDeferred<Unit>()
        val profile = CompletableDeferred<String>()
        val old = async {
            web.authorizeIfCurrent(identify = {
                entered.complete(Unit); withContext(NonCancellable) { profile.await() }
            }) { token("account-a") }
        }
        entered.await(); native.connect("account-b"); assertTrue(connect("account-b"))
        profile.complete("account-a")
        assertFalse(old.await())
        assertTrue(web.isReady)
        assertEquals("candidate-account-b", web.tokens.validAccessToken())
    }

    @Test fun lateMismatchedProfileFromOldClientCannotDisconnectNewClientGrant() = runBlocking {
        native.connect(); web.setClientId("old-client")
        val entered = CompletableDeferred<Unit>()
        val profile = CompletableDeferred<String>()
        val old = async {
            web.authorizeIfCurrent(identify = { entered.complete(Unit); profile.await() }) { token("old") }
        }
        entered.await(); web.setClientId("new-client"); assertTrue(connect())
        profile.complete("account-b")
        assertFalse(old.await())
        assertTrue(web.isReady)
        assertEquals("new-client", web.clientId.value)
        assertEquals("candidate-account-a", web.tokens.validAccessToken())
    }

    @Test fun profileVerificationFailureLeavesNoAcceptedCredentialAndPreservesPlaybackIdentity() = runBlocking {
        native.connect(); web.setClientId("client")
        val before = native.authority.binding()
        assertTrue(runCatching {
            web.authorizeIfCurrent(identify = { throw IOException("synthetic profile failure") }) { token("account-a") }
        }.isFailure)
        assertFalse(web.isReady); assertFalse(web.tokens.isLoggedIn)
        assertEquals(before, native.authority.binding())
    }

    @Test fun cancelledUncooperativeBrowserCannotCommitItsLateAnswer() = runBlocking {
        native.connect(); web.setClientId("client")
        val entered = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<SpotifyOAuth.Tokens>()
        var profiles = 0
        val old = async {
            web.authorizeIfCurrent(identify = { profiles++; "account-a" }) {
                entered.complete(Unit); withContext(NonCancellable) { answer.await() }
            }
        }
        entered.await(); old.cancel(); answer.complete(token("account-a")); old.join()
        assertFalse(web.isReady); assertFalse(web.tokens.isLoggedIn); assertEquals(0, profiles)
    }

    @Test fun cancelActualReceiverReleasesBrowserPortAndNextAttemptCanConnect() = runBlocking {
        native.connect(); web.setClientId("client")
        val entered = CompletableDeferred<Int>()
        val old = async(Dispatchers.IO) {
            web.authorizeIfCurrent(identify = { "account-a" }) {
                withOAuthLoopbackReceiver(0, "Synthetic") { receiver ->
                    entered.complete(receiver.boundPort)
                    receiver.awaitRequest()
                    token("account-a")
                }
            }
        }
        val port = entered.await()
        withTimeout(2_000) { old.cancelAndJoin() }
        assertFalse(web.isReady)
        assertTrue(web.authorizeIfCurrent(identify = { "account-a" }) {
            withOAuthLoopbackReceiver(port, "Retry") { token("account-a") }
        })
        assertEquals("candidate-account-a", web.tokens.validAccessToken())
    }

    @Test fun lateRefreshAndPinnedGenerationCannotCrossNativeAccounts() = runBlocking {
        native.connect(); web.setClientId("client"); assertTrue(connect(fresh = false))
        val expected = web.tokens.generation
        val old = async { web.tokens.validAccessToken(expectedGeneration = expected) }
        refreshEntered.await()
        native.connect("account-b"); assertTrue(connect("account-b"))
        refreshAnswer.complete(token("late-account-a"))
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { web.tokens.validAccessToken(expectedGeneration = expected) }.exceptionOrNull() is CancellationException)
        assertEquals("candidate-account-b", web.tokens.validAccessToken())
    }

    @Test fun nativeSwitchAloneRevokesRefreshAndFastTokenUseWithoutTokenGenerationChange() = runBlocking {
        native.connect(); web.setClientId("client"); assertTrue(connect(fresh = false))
        val expected = web.tokens.generation
        val old = async { web.tokens.validAccessToken(expectedGeneration = expected) }
        refreshEntered.await(); native.connect("account-b")
        val nativeB = native.authority.binding()
        refreshAnswer.complete(token("late-account-a"))
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals(expected, web.tokens.generation)
        assertEquals("candidate-account-a", tokenPrefs.getString("access_token", null))
        assertFalse(web.isReady)
        assertEquals(WebApiAccount.IdentityProblem.DIFFERENT_ACCOUNT, web.identityProblem)
        assertTrue(runCatching { web.tokens.validAccessToken() }.exceptionOrNull() is WebApiAccount.IdentityMismatch)
        assertEquals(nativeB, native.authority.binding())
    }

    @Test fun personalResultCannotPublishAfterNativeSwitchOrGrantReplacement() = runBlocking {
        native.connect(); web.setClientId("client"); assertTrue(connect())
        val proofA = I10WebReadProof.capture(native.accounts, web.tokens)!!
        var published = ""
        proofA.publish { published = "account-a" }
        native.connect("account-b"); assertTrue(connect("account-b"))
        assertTrue(runCatching { proofA.publish { published = "late-account-a" } }.isFailure)
        val proofB = I10WebReadProof.capture(native.accounts, web.tokens)!!
        assertTrue(connect("account-b")) // newer grant for the same person also revokes a read
        assertTrue(runCatching { proofB.publish { published = "old-grant-b" } }.isFailure)
        I10WebReadProof.capture(native.accounts, web.tokens)!!.publish { published = "current-b" }
        assertEquals("current-b", published)
    }

    @Test fun manualRevocationDoesNotReviveAnOldPersonalReadAfterReturningOnline() = runBlocking {
        native.connect(); web.setClientId("client"); assertTrue(connect())
        val old = I10WebReadProof.capture(native.accounts, web.tokens)!!
        SpotifyNetworkPolicy.setManual(true)
        assertNull(I10WebReadProof.capture(native.accounts, web.tokens))
        SpotifyNetworkPolicy.setManual(false)
        assertTrue(runCatching { old.ensureCurrent() }.isFailure)
        I10WebReadProof.capture(native.accounts, web.tokens)!!.ensureCurrent()
    }

    @Test fun delayedPersonalPageCannotRequestNextPageOrOverwriteNewAccountProjection() = runBlocking {
        native.connect(); web.setClientId("client"); assertTrue(connect())
        val proof = I10WebReadProof.capture(native.accounts, web.tokens)!!
        val entered = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<String>()
        var secondPages = 0
        var published = ""
        val old = async {
            val result = withContext(NonCancellable) { entered.complete(Unit); answer.await() }
            proof.ensureCurrent()
            secondPages++
            proof.publish { published = result }
        }
        entered.await(); native.connect("account-b"); assertTrue(connect("account-b"))
        I10WebReadProof.capture(native.accounts, web.tokens)!!.publish { published = "current-b" }
        answer.complete("late-personal-a")
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, secondPages)
        assertEquals("current-b", published)
    }

    @Test fun physicalWebAttemptChecksNativeIdentityBeforeDispatchAndBeforeReturningBody() = runBlocking {
        native.connect(); web.setClientId("client"); assertTrue(connect())
        val request = Request.Builder().url("https://example.invalid/synthetic-personal")
            .tag(I10WebRequestGuard::class.java, I10WebRequestGuard(web.tokens.requestGuard())).build()
        var dispatches = 0
        fun chain(beforeResponse: () -> Unit = {}): Interceptor.Chain = Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader, arrayOf(Interceptor.Chain::class.java),
        ) { _, method, _ -> when (method.name) {
            "request" -> request
            "proceed" -> {
                dispatches++; beforeResponse()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("synthetic-personal-a".toResponseBody()).build()
            }
            else -> error("Unexpected chain call ${method.name}")
        } } as Interceptor.Chain
        val interceptor = I10WebRequestGuard.NetworkInterceptor()
        // Response arrived during a revocation. It cannot be handed to a DTO reader.
        assertTrue(runCatching { interceptor.intercept(chain { native.logout() }) }.exceptionOrNull() is IOException)
        assertEquals(1, dispatches)
        // An internal follow-up/retry using the same tagged request is rejected before dispatch.
        assertTrue(runCatching { interceptor.intercept(chain()) }.exceptionOrNull() is IOException)
        assertEquals(1, dispatches)
    }
}
