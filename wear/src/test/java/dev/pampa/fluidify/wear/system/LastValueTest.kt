package dev.pampa.fluidify.wear.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The tile's cover is worked out once per cover, not once per layout. */
class LastValueTest {

    @Test
    fun theSameKeyIsComputedOnce() {
        val last = LastValue<String, ByteArray>()
        var computed = 0
        val first = last.getOrCompute("a") { computed++; byteArrayOf(1) }
        val second = last.getOrCompute("a") { computed++; byteArrayOf(2) }
        assertEquals(1, computed)
        assertEquals(first, second)
    }

    @Test
    fun anotherKeyReplacesIt() {
        val last = LastValue<String, String>()
        assertEquals("one", last.getOrCompute("a") { "one" })
        assertEquals("two", last.getOrCompute("b") { "two" })
        assertEquals("one again", last.getOrCompute("a") { "one again" })
    }

    @Test
    fun aValueThatIsNotThereYetIsNeverKept() {
        val last = LastValue<String, String>()
        assertNull(last.getOrCompute("a") { null })
        assertEquals("arrived", last.getOrCompute("a") { "arrived" })
    }
}
