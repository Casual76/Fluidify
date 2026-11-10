package dev.lelonio.square.ui.requests

import android.app.Application
import dev.lelonio.square.auth.LocalSpotifyAuthority
import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.HistoryAccess
import dev.lelonio.square.data.HistoryAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prepared only: root owns compilation/execution. Identities, credentials and libraries are synthetic.
 *
 * [Home] is the pair of callbacks MainViewModel hands to [AccountAccessTransitions], reduced to
 * what the Home page shows. The coordinator and the authority underneath are the real ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AccountAccessTransitionsTest {

    @Test fun onlineVerificationOnTheCollectorsThreadKeepsTheHomeLoadItStarted() = runTest {
        val restart = restarted()
        val home = Home(restart.accounts, immediate())
        assertEquals(listOf("observer:LOCAL", "collector:LOCAL", "refresh:LOCAL"), home.trace)
        home.trace.clear()

        restart.sessionConnected()
        assertTrue(restart.accounts.verifyNativeAccount())

        // The order the device recorded, all on one thread: the collector is answered inside
        // each change, before the observer hears of it.
        assertEquals(
            listOf("collector:null", "observer:null", "collector:ONLINE", "refresh:ONLINE", "observer:ONLINE"),
            home.trace,
        )
        assertEquals("loading", home.state)
        assertTrue(home.load!!.isActive)
        assertSame(restart.accounts.captureOnline(), home.question)
        home.answer("library")
        assertEquals("ready:account-a:library", home.state)
    }

    @Test fun aChangeTheCollectorHearsLaterIsRevokedFirstAndLoadedOnce() = runTest {
        val restart = restarted()
        val home = Home(restart.accounts, posted())
        runCurrent()
        val local = home.load!!
        home.trace.clear()

        restart.sessionConnected()
        assertTrue(restart.accounts.verifyNativeAccount())

        // Another thread's change, as the main thread sees it: revoked at once, answered later.
        assertEquals(listOf("observer:null", "observer:ONLINE"), home.trace)
        assertTrue(local.isCancelled)
        assertEquals("connecting", home.state)
        assertNull(home.question)

        runCurrent()
        assertEquals(
            listOf("observer:null", "observer:ONLINE", "collector:ONLINE", "refresh:ONLINE"),
            home.trace,
        )
        assertEquals("loading", home.state)
        home.answer("library")
        runCurrent()
        assertEquals("ready:account-a:library", home.state)
    }

    @Test fun anotherAccountReceivesNeitherTheLoadNorTheAnswerOfThePreviousOne() = runTest {
        val fixture = AccountLocalDataFixture()
        val home = Home(fixture.accounts, immediate())
        val a = fixture.connect()
        val answerForA = home.libraries.getValue(a)
        val loadForA = home.load!!
        val searchForA = Job().also { home.search = it }

        fixture.logout()
        assertTrue(loadForA.isCancelled)
        assertTrue(searchForA.isCancelled)
        assertEquals("connecting", home.state)
        assertNull(home.question)

        val b = fixture.connect("account-b")
        answerForA.complete("library-a")
        assertEquals("loading", home.state)
        assertTrue(home.load!!.isActive)
        assertSame(b, home.question)
        home.answer("library-b")
        assertEquals("ready:account-b:library-b", home.state)
    }

    @Test fun losingOnlineProofOnTheCollectorsThreadFallsBackToTheLocalLoad() = runTest {
        val fixture = AccountLocalDataFixture()
        val home = Home(fixture.accounts, immediate())
        val online = fixture.connect()
        val onlineLoad = home.load!!
        val onlineSearch = Job().also { home.search = it }
        home.trace.clear()

        // A history generation alone: the same account and native session, no longer the verified pair.
        fixture.history = HistoryAccess(online.account, 99)
        fixture.accounts.historyChanged()

        assertEquals(listOf("collector:LOCAL", "refresh:LOCAL", "observer:LOCAL"), home.trace)
        assertTrue(onlineLoad.isCancelled)
        assertTrue(onlineSearch.isCancelled)
        assertTrue(home.load!!.isActive)
        assertNull(fixture.accounts.captureOnline())
        home.answer("downloads")
        assertEquals("ready:account-a:downloads", home.state)
    }

    @Test fun localAuthorityAloneLoadsTheLocalLibraryAndAsksNothingOnline() = runTest {
        val restart = restarted()
        val home = Home(restart.accounts, immediate())

        home.answer("downloads")

        assertEquals("ready:account-a:downloads", home.state)
        assertEquals(AccountLocalDataCoordinator.Origin.LOCAL, restart.accounts.capture()?.origin)
        assertNull(restart.accounts.captureOnline())
        assertEquals(0, restart.lookups)
    }

    /** Resumed inline by a change made on its own thread, as Dispatchers.Main.immediate is on the main thread. */
    private fun TestScope.immediate() =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    /** Resumed only when the thread is next free, as the main thread is for a change made elsewhere. */
    private fun TestScope.posted() =
        CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler))

    private suspend fun restarted() = Restart(AccountLocalDataFixture().also { it.connect() })

    /** A process started again: the local authority is on disk, the session has not connected yet. */
    private class Restart(disk: AccountLocalDataFixture) {
        private val id = checkNotNull(disk.kept).canonical
        private var history: HistoryAccess? = null
        private var epoch: Long? = null
        private var ready = false
        var lookups = 0
        private val authority = LocalSpotifyAuthority({ disk.receipt }, { disk.receipt = it }, { disk.kept })
        val accounts = AccountLocalDataCoordinator({ history }, { epoch }, { lookups++; id }, authority, { ready })

        fun sessionConnected() {
            history = HistoryAccess(HistoryAccount(id), 1)
            epoch = 1
            ready = true
        }
    }

    private class Home(private val accounts: AccountLocalDataCoordinator, private val scope: CoroutineScope) {
        val trace = mutableListOf<String>()
        var state = "connecting"
        var load: Job? = null
        var search: Job? = null

        /** Stands for the legacy-download question: asked for an access, never carried to another. */
        var question: AccountLocalDataCoordinator.Access? = null

        /** Completing one is the library answering the load made for that access. */
        val libraries = mutableMapOf<AccountLocalDataCoordinator.Access, CompletableDeferred<String>>()

        private val transitions = AccountAccessTransitions(accounts)
        private val observation = transitions.observe { observed, answered ->
            trace += "observer:${observed?.origin}"
            if (!answered) {
                load?.cancel()
                state = "connecting"
                question = null
            }
            search?.cancel()
        }

        init {
            scope.launch {
                transitions.collect { access ->
                    trace += "collector:${access?.origin}"
                    question = null
                    load?.cancel()
                    load = null
                    if (access == null) {
                        state = "connecting"
                        return@collect
                    }
                    question = access
                    refresh()
                }
            }
        }

        fun answer(rows: String) {
            libraries.getValue(checkNotNull(accounts.capture())).complete(rows)
        }

        fun close() = observation.close()

        private fun refresh() {
            val access = accounts.capture() ?: return
            trace += "refresh:${access.origin}"
            load = scope.launch {
                if (access.origin == AccountLocalDataCoordinator.Origin.ONLINE) state = "loading"
                val rows = libraries.getOrPut(access) { CompletableDeferred() }.await()
                accounts.use(access) { state = "ready:${access.account.id}:$rows" }
            }
        }
    }
}
