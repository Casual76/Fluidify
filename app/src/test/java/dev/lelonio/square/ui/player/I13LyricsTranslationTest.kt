package dev.lelonio.square.ui.player

import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class I13LyricsTranslationTest {
    private val lyrics = Lyrics(listOf(
        LyricLine(0L, "First"), LyricLine(1_000L, ""), LyricLine(2_000L, "Last")), true)

    @Test fun nullTurnsOffAndExplicitRetryPreservesLineOrderAndCache() = runTest {
        val calls = mutableListOf<Pair<List<String>, String>>()
        val owner = I13LyricsTranslation(lyrics, "it", this) { texts, target ->
            calls += texts to target
            if (calls.size == 1) null else listOf("Prima", "", "Ultima")
        }
        assertFalse(owner.state.value.enabled)
        assertTrue(calls.isEmpty())
        owner.setEnabled(true)
        assertTrue(owner.state.value.busy)
        runCurrent()
        assertEquals(I13TranslationState(failed = true), owner.state.value)
        // No automatic retry after the null response.
        runCurrent()
        assertEquals(1, calls.size)
        owner.setEnabled(true)
        runCurrent()
        assertTrue(owner.state.value.enabled)
        assertFalse(owner.state.value.failed)
        assertEquals(listOf("Prima", "", "Ultima"), owner.state.value.machine)
        assertEquals(List(2) { listOf("First", "", "Last") to "it" }, calls)
        owner.setEnabled(false)
        owner.setEnabled(true)
        runCurrent()
        assertEquals(2, calls.size)
        assertEquals(listOf("Prima", "", "Ultima"), owner.state.value.machine)
    }

    @Test fun exceptionIsRecoverableAndMalformedAnswerCannotShiftSubtitles() = runTest {
        var attempts = 0
        val owner = I13LyricsTranslation(lyrics, "it", this) { _, _ ->
            when (++attempts) {
                1 -> throw IllegalStateException("unusable response")
                2 -> listOf("Too short")
                else -> listOf("Uno", "", "Due")
            }
        }
        repeat(2) {
            owner.setEnabled(true)
            runCurrent()
            assertFalse(owner.state.value.enabled)
            assertTrue(owner.state.value.failed)
            assertNull(owner.state.value.machine)
        }
        owner.setEnabled(true)
        runCurrent()
        assertEquals(listOf("Uno", "", "Due"), owner.state.value.machine)
    }

    @Test fun lateCancelledAnswerCannotOverwriteNewerIntentOrResult() = runTest {
        val old = CompletableDeferred<List<String>?>()
        val latest = CompletableDeferred<List<String>?>()
        var attempts = 0
        val owner = I13LyricsTranslation(lyrics, "it", this) { _, _ ->
            if (++attempts == 1) withContext(NonCancellable) { old.await() } else latest.await()
        }
        owner.setEnabled(true)
        runCurrent()
        owner.setEnabled(false)
        assertFalse(owner.state.value.busy)
        owner.setEnabled(true)
        runCurrent()
        latest.complete(listOf("Nuova", "", "Risposta"))
        runCurrent()
        old.complete(listOf("Vecchia", "", "Risposta"))
        runCurrent()
        assertTrue(owner.state.value.enabled)
        assertFalse(owner.state.value.busy)
        assertFalse(owner.state.value.failed)
        assertEquals(listOf("Nuova", "", "Risposta"), owner.state.value.machine)
    }

    @Test fun disposalAndOffCannotPublishOrShowAnErrorFromOldWork() = runTest {
        for (dispose in listOf(false, true)) {
            val response = CompletableDeferred<List<String>?>()
            val owner = I13LyricsTranslation(lyrics, "it", this) { _, _ ->
                withContext(NonCancellable) { response.await() }
            }
            owner.setEnabled(true)
            runCurrent()
            if (dispose) owner.dispose() else owner.setEnabled(false)
            response.complete(null)
            runCurrent()
            assertEquals(I13TranslationState(), owner.state.value)
        }
    }

    @Test fun providerCancellationDoesNotBecomeSuccessOrFailure() = runTest {
        val owner = I13LyricsTranslation(lyrics, "it", this) { _, _ -> throw CancellationException("cancel") }
        owner.setEnabled(true)
        runCurrent()
        assertEquals(I13TranslationState(), owner.state.value)
    }

    @Test fun suppliedTranslationNeedsNoRequestAndAlreadyTargetLanguageIsAnExplicitOutcome() = runTest {
        val supplied = lyrics.copy(lines = lyrics.lines.map { it.copy(translation = "Subtitle") })
        val own = I13LyricsTranslation(supplied, "it", this) { _, _ -> error("must not request") }
        own.setEnabled(true)
        runCurrent()
        assertTrue(own.state.value.enabled)
        assertFalse(own.state.value.busy)
        val sameLanguage = I13LyricsTranslation(lyrics, "it", this) { _, _ -> listOf("", "", "") }
        sameLanguage.setEnabled(true)
        runCurrent()
        assertTrue(sameLanguage.state.value.unchanged)
        assertFalse(sameLanguage.state.value.failed)
        assertTrue(sameLanguage.state.value.enabled)
    }
}
