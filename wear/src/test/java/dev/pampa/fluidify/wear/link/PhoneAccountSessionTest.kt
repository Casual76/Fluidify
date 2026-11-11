package dev.pampa.fluidify.wear.link

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.pampa.fluidify.wear.library.PhoneLibrary
import dev.pampa.fluidify.wear.protocol.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prepared. No Wearable client method is called; seeded pages and identities are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PhoneAccountSessionTest {
    private fun context(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val folder = Files.createTempDirectory(base.cacheDir.toPath(), "phone-account-").toFile()
        val suffix = UUID.randomUUID().toString()
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$name-$suffix", mode)
        }
    }
    private fun account(namespace: String, session: String, at: Long) =
        AccountState(true, "synthetic-$namespace", at, session, namespace)

    @Test fun oldScopeIsDeniedAndNamespaceCachesSurviveAtoBAndReturn() {
        val context = context()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val link = PhoneLink(context, scope, WatchState(File(context.filesDir, "state.json")), ArtStore(context),
            connectedNodes = { error("No node lookup allowed") }, capabilityNodes = { error("No capability lookup allowed") })
        try {
            val sessions = link.accountSessions
            sessions.accept(account("a", "a-1", 1))
            val old = sessions.capture()!!
            val library = PhoneLibrary(context, link)
            val pageA = LibraryPage(listOf(LibraryShelf("A", listOf(LibraryItem("spotify:playlist:a", "A", kind = LibraryKind.PLAYLIST)))))
            library.seed(home = pageA)
            sessions.accept(account("b", "b-1", 2))
            assertNull(library.peekHome())
            assertNull(library.cachedHome())
            try { sessions.use(old) { fail("Old request admitted") }; fail("No rejection") }
            catch (_: CancellationException) { }
            library.seed(home = LibraryPage(listOf(LibraryShelf("B", emptyList()))))
            library.clear() // memory only; another namespace's file is retained
            sessions.accept(account("a", "a-2", 3))
            assertEquals(pageA, library.cachedHome())
            assertFalse(sessions.isCurrent(old))
            sessions.accept(account("b", "stale", 2))
            assertEquals("a-2", sessions.capture()?.session)
            sessions.clear()
            assertNull(library.cachedHome())
        } finally { scope.cancel() }
    }

    @Test fun rpcFileAndAuthCorrelationsRoundTripAndLegacyRequestsCarryNoAuthority() {
        val rpc = RpcRequest(1, RpcMethod.Downloads(listOf("spotify:track:a")), "session-a")
        assertEquals(rpc, WearCodec.decodeOrNull(RpcRequest.serializer(), WearCodec.encode(RpcRequest.serializer(), rpc)))
        val file = FileRequest("spotify:track:a", accountSession = "session-a")
        assertEquals(file, WearCodec.decodeOrNull(FileRequest.serializer(), WearCodec.encode(FileRequest.serializer(), file)))
        val legacy = WearCodec.decodeOrNull(RpcRequest.serializer(),
            WearCodec.encode(RpcRequest.serializer(), RpcRequest(2, RpcMethod.Home)))
        assertNotNull(legacy)
        assertNull(legacy!!.accountSession)
        val response = RpcResponse(1, true, accountSession = "session-a")
        assertEquals("session-a", WearCodec.decodeOrNull(RpcResponse.serializer(), WearCodec.encode(RpcResponse.serializer(), response))?.accountSession)
        val auth = AuthRequest(3, accountSession = "session-a")
        assertEquals(auth, WearCodec.decodeOrNull(AuthRequest.serializer(), WearCodec.encode(AuthRequest.serializer(), auth)))
        assertNull(WearCodec.decodeOrNull(AuthRequest.serializer(),
            WearCodec.encode(AuthRequest.serializer(), AuthRequest(4)))?.accountSession)
    }
}
