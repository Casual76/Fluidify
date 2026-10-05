package dev.pampa.fluidify.wear.update

import dev.antigravity.fluidengine.foundation.UpdateChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a start is the first of a newer version, and which channel a word means. */
class WhatsNewTest {

    @Test
    fun aNewerVersionThanTheLastRunIsJustUpdated() {
        assertTrue(WhatsNew.justUpdated("1.6.4", "1.7.0"))
        assertTrue(WhatsNew.justUpdated("1.7.0", "1.7.1"))
    }

    @Test
    fun theSameVersionIsNotNews() {
        assertFalse(WhatsNew.justUpdated("1.7.0", "1.7.0"))
    }

    @Test
    fun aFirstRunIsNotAnUpdate() {
        // Nothing was recorded: a fresh install, or one from before this was kept.
        assertFalse(WhatsNew.justUpdated(null, "1.7.0"))
    }

    @Test
    fun anOlderVersionPutBackIsNotAnUpdate() {
        assertFalse(WhatsNew.justUpdated("1.7.0", "1.6.4"))
    }

    @Test
    fun thePhonesWordPicksTheChannelAndAnythingElseIsStable() {
        assertEquals(UpdateChannel.BETA, UpdateChannelPrefs.engine("beta"))
        assertEquals(UpdateChannel.STABLE, UpdateChannelPrefs.engine("stable"))
        assertEquals(UpdateChannel.STABLE, UpdateChannelPrefs.engine(""))
        assertEquals(UpdateChannel.STABLE, UpdateChannelPrefs.engine("nightly"))
    }
}
