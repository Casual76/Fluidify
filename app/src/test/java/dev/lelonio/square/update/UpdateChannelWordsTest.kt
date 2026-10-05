package dev.lelonio.square.update

import dev.antigravity.fluidengine.foundation.UpdateChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The channel as a word: what is stored, and what is told to the watch. */
class UpdateChannelWordsTest {

    @Test
    fun eachChannelHasItsWord() {
        assertEquals("stable", UpdateChannel.STABLE.word)
        assertEquals("beta", UpdateChannel.BETA.word)
    }

    @Test
    fun wordsReadBackAsTheirChannel() {
        UpdateChannel.entries.forEach { assertEquals(it, updateChannelOf(it.word)) }
    }

    @Test
    fun anythingElseIsStable() {
        assertEquals(UpdateChannel.STABLE, updateChannelOf(null))
        assertEquals(UpdateChannel.STABLE, updateChannelOf(""))
        assertEquals(UpdateChannel.STABLE, updateChannelOf("canary"))
    }

    @Test
    fun theCaseOfAStoredWordDoesNotMatter() {
        assertEquals(UpdateChannel.BETA, updateChannelOf("BETA"))
    }
}
