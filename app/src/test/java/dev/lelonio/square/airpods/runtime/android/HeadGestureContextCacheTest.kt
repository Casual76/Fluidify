// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HeadGestureContextCacheTest {
    @Test fun ageStartsBeforeReadAndExpiresAt250MillisecondsWithoutReadingAgain() = runBlocking {
        var now = 10L; var reads = 0
        val cache = HeadGestureContextCache<String>({ now })
        cache.refresh { reads++; "target" }
        repeat(500) { assertEquals("target", cache.current()) }
        assertEquals(1, reads)
        now += 250; assertEquals("target", cache.current())
        now++; assertNull(cache.current())
        cache.refresh { now += 251; "late-main" }
        assertNull(cache.current())
    }
    @Test fun invalidationFencesBothPublishedAndInFlightContext() = runBlocking {
        val cache = HeadGestureContextCache<String>({ 10 })
        cache.refresh { "first" }; cache.invalidate(); assertNull(cache.current())
        cache.refresh { cache.invalidate(); "obsolete" }; assertNull(cache.current())
        cache.refresh { "current" }; assertEquals("current", cache.current())
    }
}
