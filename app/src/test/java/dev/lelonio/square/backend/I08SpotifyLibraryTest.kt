package dev.lelonio.square.backend

import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.OwnerDto
import dev.lelonio.square.data.PageDto
import dev.lelonio.square.data.PlaylistDto
import dev.lelonio.square.data.SpotifyNetworkPolicy
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Prepared for root execution. No authenticated API and NOT EXECUTED by I08. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class I08SpotifyLibraryTest {
    @Before fun online() { SpotifyNetworkPolicy.setManual(false) }
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }
    private fun dto(i: Int) = PlaylistDto("p$i", "spotify:playlist:p$i", "P$i", owner = OwnerDto(if (i % 2 == 0) "a" else "b"))

    @Test fun fallbackReads121RowsAndPublishesOwnedEditabilityBeyondTheFirst50() = runTest {
        val all = (0..120).map(::dto)
        val offsets = mutableListOf<Int>()
        val rows = readSpotifyRootlist("a", {}, { throw IOException("primary") }, { true }, { offset ->
            offsets += offset
            PageDto(all.drop(offset).take(50), total = all.size,
                next = if (offset + 50 < all.size) "continuation" else null)
        })
        assertEquals(listOf(0, 50, 100), offsets)
        assertEquals(all.map { it.uri }, rows.map { it.uri })
        assertEquals(true, rows.last().editable)
        assertEquals("a", rows.last().owner)
        assertNull(rows[119].editable) // no collaborative rights in the existing DTO
    }

    @Test fun nextContinuesEvenWhenTotalWasOmittedAndShortPagesAdvanceByRawCount() = runTest {
        val offsets = mutableListOf<Int>()
        val rows = readCompleteLibraryPages({}, { offset ->
            offsets += offset
            when (offset) {
                0 -> PageDto(listOf(1, 2), next = "more")
                2 -> PageDto(listOf(3), next = "more")
                3 -> PageDto(listOf(4))
                else -> error("wrong offset")
            }
        })
        assertEquals(listOf(0, 2, 3), offsets)
        assertEquals(listOf(1, 2, 3, 4), rows)
    }

    @Test fun interruptedSecondPageCannotPublishPrefixAndExplicitRetryStartsAtZero() = runTest {
        val offsets = mutableListOf<Int>()
        var fail = true
        suspend fun read() = readSpotifyRootlist("a", {}, { emptyList() }, { true }, { offset ->
            offsets += offset
            if (offset == 50 && fail) throw IOException("interrupted")
            PageDto((offset until minOf(offset + 50, 73)).map(::dto), total = 73)
        })
        assertTrue(runCatching { read() }.exceptionOrNull() is IOException)
        fail = false
        assertEquals(73, read().size)
        assertEquals(listOf(0, 50, 0, 50), offsets)
        val premature = runCatching {
            readCompleteLibraryPages<Int>({}, { PageDto(emptyList(), total = 73) })
        }
        assertTrue(premature.exceptionOrNull() is IllegalStateException)
    }

    @Test fun disappearingTotalCannotTurnAnInterruptedPrefixIntoACompleteLibrary() = runTest {
        val result = runCatching {
            readCompleteLibraryPages<Int>({}, { offset ->
                if (offset == 0) PageDto(listOf(1, 2), total = 4)
                else PageDto(emptyList())
            })
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test fun primaryCancellationNeverEntersFallbackAndGenuineEmptyFallbackIsComplete() = runTest {
        var fallbackCalls = 0
        val cancelled = runCatching {
            readSpotifyRootlist("a", {}, { throw CancellationException("replaced read") }, { true }, {
                fallbackCalls++; PageDto(emptyList())
            })
        }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
        assertEquals(0, fallbackCalls)
        val emptyPrimary = readSpotifyRootlist("a", {}, { emptyList() }, { false }, {
            fallbackCalls++
            error("an empty native library does not need Web API setup")
        })
        assertTrue(emptyPrimary.isEmpty())
        assertEquals(0, fallbackCalls)
        val rows = readSpotifyRootlist("a", {}, { throw IOException("root unavailable") }, { true }, { PageDto(emptyList()) })
        assertTrue(rows.isEmpty())
    }

    @Test fun manualRevisionAndAccountReplacementRejectLatePageBeforeAnyNextDispatch() = runTest {
        val account = AccountLocalDataFixture(); val access = account.connect("a")
        val permit = SpotifyNetworkPolicy.capture()
        val answer = CompletableDeferred<PageDto<PlaylistDto>>()
        var calls = 0
        val read = async { runCatching {
            readSpotifyRootlist("a", { permit.check(); account.accounts.requireOnline(access) },
                { throw IOException("root unavailable") }, { true }, { calls++; answer.await() })
        } }
        runCurrent()
        SpotifyNetworkPolicy.setManual(true)
        SpotifyNetworkPolicy.setManual(false)
        answer.complete(PageDto(listOf(dto(0)), total = 2))
        assertTrue(read.await().exceptionOrNull() is SpotifyNetworkPolicy.Offline)
        assertEquals(1, calls)
        val fresh = SpotifyNetworkPolicy.capture()
        val switched = runCatching {
            readSpotifyRootlist("a", { fresh.check(); account.accounts.requireOnline(access) },
                { throw IOException("root unavailable") }, { true }, {
                    account.connect("b")
                    PageDto(listOf(dto(0)), total = 2)
                })
        }
        assertTrue(switched.exceptionOrNull() is CancellationException)
        // New online work uses the new account, never resumes the rejected old prefix.
        val next = account.accounts.captureOnline()!!
        val recovered = readSpotifyRootlist("b", { account.accounts.requireOnline(next) },
            { listOf(CatalogPlaylist("spotify:playlist:b", "B")) }, { false }, { error("no fallback") })
        assertEquals("spotify:playlist:b", recovered.single().uri)
    }
}
