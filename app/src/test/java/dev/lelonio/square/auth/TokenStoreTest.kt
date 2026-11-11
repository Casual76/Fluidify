package dev.lelonio.square.auth

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TokenStoreTest {
    private fun tokens(name: String, fresh: Boolean = true) =
        SpotifyOAuth.Tokens(name, "refresh-$name", if (fresh) 1_000_000 else 0)

    private class Fixture {
        val prefs = memoryPreferences()
        val key = UUID.randomUUID().toString()
        var client = "client-A"
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<SpotifyOAuth.Tokens>()
        fun store(authority: LocalSpotifyAuthority? = null) = TokenStore(prefs, key, { client }, { _, capturedClient ->
            assertEquals("client-A", capturedClient)
            calls++
            entered.complete(Unit)
            answer.await()
        }, { 100L }, authority)
    }

    @Test fun lateSuccessAfterClearCannotReturnOrRestoreTokens() = runBlocking {
        val f = Fixture(); val store = f.store(); store.save(tokens("A", false))
        val old = async { store.validAccessToken() }
        f.entered.await(); store.clear(); f.answer.complete(tokens("late-A"))
        expectCancelled(old)
        assertFalse(store.isLoggedIn)
        assertNull(f.prefs.getString("access_token", null))
    }

    @Test fun lateSuccessAfterClientChangeIsRejected() = runBlocking {
        val f = Fixture(); val store = f.store(); store.save(tokens("A", false))
        val old = async { store.validAccessToken() }
        f.entered.await()
        store.invalidate { f.client = "client-B" }
        f.answer.complete(tokens("late-A")); expectCancelled(old)
        assertFalse(store.isLoggedIn)
    }

    @Test fun changedClientIsCheckedEvenWithoutExplicitInvalidation() = runBlocking {
        val f = Fixture(); val store = f.store(); store.save(tokens("A", false))
        val old = async { store.validAccessToken() }
        f.entered.await(); f.client = "client-B"
        f.answer.complete(tokens("late-A")); expectCancelled(old)
        assertEquals("A", f.prefs.getString("access_token", null))
    }

    @Test fun lateSuccessCannotOverwriteNewAuthenticationInAnotherInstance() = runBlocking {
        val f = Fixture(); val oldStore = f.store(); val newStore = f.store()
        oldStore.save(tokens("A", false))
        val old = async { oldStore.validAccessToken() }
        f.entered.await(); newStore.save(tokens("B"))
        f.answer.complete(tokens("late-A")); expectCancelled(old)
        assertEquals("B", newStore.validAccessToken())
    }

    @Test fun lateExpiredCannotClearNewAuthentication() = runBlocking {
        val f = Fixture(); val store = f.store(); store.save(tokens("A", false))
        val old = async { store.validAccessToken() }
        f.entered.await(); f.store().save(tokens("B"))
        f.answer.completeExceptionally(SpotifyOAuth.SessionExpired("old revoked"))
        expectCancelled(old)
        assertEquals("B", store.validAccessToken())
    }

    @Test fun lateExpiredAfterClearIsCancellation() = runBlocking {
        val f = Fixture(); val store = f.store(); store.save(tokens("A", false))
        val old = async { store.validAccessToken() }
        f.entered.await(); store.clear()
        f.answer.completeExceptionally(SpotifyOAuth.SessionExpired("old revoked"))
        expectCancelled(old); assertFalse(store.isLoggedIn)
    }

    @Test fun lateExpiredAfterClientChangeCannotClearReplacementTokens() = runBlocking {
        val f = Fixture(); val store = f.store(); store.save(tokens("A", false))
        val old = async { store.validAccessToken() }
        f.entered.await()
        store.invalidate { f.client = "client-B" }
        store.save(tokens("B"))
        f.answer.completeExceptionally(SpotifyOAuth.SessionExpired("old client revoked"))
        expectCancelled(old)
        assertEquals("B", store.validAccessToken())
    }

    @Test fun currentExpiredSessionIsCleared() = runBlocking {
        val f = Fixture(); val store = f.store(); store.save(tokens("A", false))
        val result = async { runCatching { store.validAccessToken() } }
        f.entered.await()
        f.answer.completeExceptionally(SpotifyOAuth.SessionExpired("revoked"))
        assertTrue(result.await().exceptionOrNull() is SpotifyOAuth.SessionExpired)
        assertFalse(store.isLoggedIn)
    }

    @Test fun concurrentRefreshAcrossInstancesMakesOneRequest() = runBlocking {
        val f = Fixture(); val one = f.store(); val two = f.store()
        one.save(tokens("A", false))
        val first = async { one.validAccessToken() }
        f.entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { two.validAccessToken() }
        f.answer.complete(tokens("fresh"))
        assertEquals("fresh", first.await()); assertEquals("fresh", second.await())
        assertEquals(1, f.calls)
    }

    @Test fun abandonedAuthorizationCannotSaveAndNewSaveReplacesRefreshToken() {
        val f = Fixture(); val store = f.store(); val generation = store.generation
        store.clear()
        assertFalse(store.saveIfCurrent(generation, tokens("A")))
        store.save(tokens("A"))
        store.save(SpotifyOAuth.Tokens("B", null, 1_000_000))
        assertFalse(f.prefs.contains("refresh_token"))
    }

    @Test fun currentInteractiveAuthorizationSavesBeforePublishing() = runBlocking {
        val f = Fixture(); val store = f.store()
        var connected = false; var loads = 0
        val result = async {
            if (store.authorizeIfCurrent { f.entered.complete(Unit); f.answer.await() }) {
                connected = true; loads++
            }
        }
        f.entered.await(); f.answer.complete(tokens("authorized")); result.await()
        assertTrue(connected); assertEquals(1, loads)
        assertEquals("authorized", store.validAccessToken())
    }

    @Test fun clearDuringInteractiveAuthorizationCannotRestoreOrPublishSession() = runBlocking {
        val f = Fixture(); val store = f.store()
        var connected = false; var loads = 0
        val result = async {
            if (store.authorizeIfCurrent { f.entered.complete(Unit); f.answer.await() }) {
                connected = true; loads++
            }
        }
        f.entered.await()
        // Both logout and Web API disconnect clear this same store.
        store.clear(); f.answer.complete(tokens("abandoned")); result.await()
        assertFalse(store.isLoggedIn); assertFalse(connected); assertEquals(0, loads)
        assertNull(f.prefs.getString("access_token", null))
    }

    @Test fun clientChangeDuringInteractiveAuthorizationRejectsOldAnswer() = runBlocking {
        val f = Fixture(); val store = f.store()
        val result = async { store.authorizeIfCurrent { f.entered.complete(Unit); f.answer.await() } }
        f.entered.await(); store.invalidate { f.client = "client-B" }
        f.answer.complete(tokens("old-client"))
        assertFalse(result.await()); assertFalse(store.isLoggedIn)
    }

    @Test fun interactiveAuthorizationChecksClientEvenWithoutInvalidation() = runBlocking {
        val f = Fixture(); val store = f.store()
        val result = async { store.authorizeIfCurrent { f.entered.complete(Unit); f.answer.await() } }
        f.entered.await(); f.client = "client-B"; f.answer.complete(tokens("old-client"))
        assertFalse(result.await()); assertFalse(store.isLoggedIn)
    }

    @Test fun oldInteractiveAuthorizationCannotOverwriteReplacementSession() = runBlocking {
        val f = Fixture(); val store = f.store()
        val result = async { store.authorizeIfCurrent { f.entered.complete(Unit); f.answer.await() } }
        f.entered.await(); f.store().save(tokens("replacement")); f.answer.complete(tokens("old"))
        assertFalse(result.await()); assertEquals("replacement", store.validAccessToken())
    }

    @Test fun cancelledInteractiveAuthorizationCannotCommitAnUncooperativeAnswer() = runBlocking {
        val f = Fixture(); val store = f.store()
        var published = false
        val result = async {
            if (store.authorizeIfCurrent {
                f.entered.complete(Unit)
                withContext(NonCancellable) { f.answer.await() }
            }) published = true
        }
        f.entered.await(); result.cancel(); f.answer.complete(tokens("late"))
        expectCancelled(result)
        assertFalse(store.isLoggedIn); assertFalse(published)
    }

    @Test fun primaryReplacementAndClearDurablyRevokeLocalBindingBeforeTokenWrite() {
        val f = Fixture()
        var state: String? = null
        val authority = LocalSpotifyAuthority({ state }, { state = it }, {
            LocalSpotifyAuthority.KeptIdentity("account-a", "synthetic")
        })
        val store = f.store(authority)
        store.save(tokens("A"))
        assertNull(authority.binding())
        authority.confirm(authority.verification()!!, "account-a")
        val old = authority.verification()!!
        store.save(tokens("B"))
        assertNull(authority.binding())
        assertNull(authority.confirm(old, "account-a"))
        store.clear()
        assertFalse(store.isLoggedIn)
        assertNull(LocalSpotifyAuthority({ state }, { state = it }, {
            LocalSpotifyAuthority.KeptIdentity("account-a", "synthetic")
        }).binding())
    }

    @Test fun failedDurableRevocationPreventsTokenReplacement() {
        val f = Fixture()
        var state: String? = null
        var reject = false
        val authority = LocalSpotifyAuthority({ state }, {
            if (reject) throw java.io.IOException("injected tombstone failure") else state = it
        }, { LocalSpotifyAuthority.KeptIdentity("account-a", "synthetic") })
        val store = f.store(authority)
        store.save(tokens("A"))
        reject = true
        try { store.save(tokens("B")); fail("Expected failed durable revocation") }
        catch (_: java.io.IOException) { }
        assertEquals("A", f.prefs.getString("access_token", null))
    }

    @Test fun ordinaryTokenRefreshDoesNotRevokeTheConfiguredNativeIdentity() = runBlocking {
        val f = Fixture()
        var state: String? = null
        val authority = LocalSpotifyAuthority({ state }, { state = it }, {
            LocalSpotifyAuthority.KeptIdentity("account-a", "synthetic")
        })
        val store = f.store(authority)
        store.save(tokens("A", false))
        authority.confirm(authority.verification()!!, "account-a")
        val before = authority.binding()
        val refresh = async { store.validAccessToken() }
        f.entered.await(); f.answer.complete(tokens("fresh-A")); refresh.await()
        assertEquals(before, authority.binding())
    }

    private suspend fun <T> expectCancelled(result: Deferred<T>) {
        try { result.await(); fail("old token returned") } catch (_: CancellationException) { }
    }
}

/** SharedPreferences seam only: no Android runtime, keystore, network or crypto. */
private fun memoryPreferences(): SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    lateinit var prefs: SharedPreferences
    prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "contains" -> values.containsKey(args!![0])
            "getString", "getLong" -> values[args!![0]] ?: args[1]
            "getAll" -> values.toMap()
            "edit" -> {
                val writes = mutableMapOf<String, Any?>(); var clear = false
                lateinit var editor: SharedPreferences.Editor
                editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { _, call, input ->
                    when (call.name) {
                        "clear" -> { clear = true; writes.clear(); editor }
                        "putString", "putLong" -> { writes[input!![0] as String] = input[1]; editor }
                        "commit", "apply" -> {
                            if (clear) values.clear()
                            writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                            if (call.name == "commit") true else null
                        }
                        else -> error(call.name)
                    }
                } as SharedPreferences.Editor
                editor
            }
            else -> error(method.name)
        }
    } as SharedPreferences
    return prefs
}
