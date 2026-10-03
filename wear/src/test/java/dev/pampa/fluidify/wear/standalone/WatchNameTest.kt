package dev.pampa.fluidify.wear.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchNameTest {

    @Test
    fun theSerialAfterTheNameIsLeftOut() {
        assertEquals("Galaxy Watch7", WatchName.clean("Galaxy Watch7 (L5LJ)"))
    }

    @Test
    fun aPlainNameStaysAsItIs() {
        assertEquals("Pixel Watch 3", WatchName.clean("  Pixel Watch 3 "))
        assertEquals("Watch (of Anna's)", WatchName.clean("Watch (of Anna's)"))
    }

    @Test
    fun blankIsNoName() {
        assertNull(WatchName.clean("   "))
    }
}
