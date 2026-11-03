package dev.lelonio.square.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the page host is given for the tabs.
 *
 * A step between two tabs is sideways, in the direction of the bar; anything
 * involving a page that is not a tab must answer zero, or the host would slide
 * a playlist in like a tab and never let the finger take it back.
 */
class TabStepTest {

    @Test
    fun towardsTheEndOfTheRowIsPositive() {
        assertTrue(tabStep(Routes.HOME, Routes.SEARCH) > 0)
        assertTrue(tabStep(Routes.HOME, Routes.LIBRARY) > 0)
        assertTrue(tabStep(Routes.SEARCH, Routes.LIBRARY) > 0)
    }

    @Test
    fun towardsTheStartIsNegative() {
        assertTrue(tabStep(Routes.LIBRARY, Routes.HOME) < 0)
        assertTrue(tabStep(Routes.SEARCH, Routes.HOME) < 0)
    }

    @Test
    fun pagesAreNotTabs() {
        assertEquals(0, tabStep(Routes.HOME, Routes.PLAYLIST))
        assertEquals(0, tabStep(Routes.PLAYLIST, Routes.LIBRARY))
        assertEquals(0, tabStep(Routes.LIBRARY, Routes.SETTINGS))
        assertEquals(0, tabStep(Routes.SETTINGS, Routes.HOME))
    }

    @Test
    fun theSameTabOrNothingIsNoStep() {
        assertEquals(0, tabStep(Routes.HOME, Routes.HOME))
        assertEquals(0, tabStep(null, Routes.HOME))
        assertEquals(0, tabStep(Routes.HOME, null))
    }
}
