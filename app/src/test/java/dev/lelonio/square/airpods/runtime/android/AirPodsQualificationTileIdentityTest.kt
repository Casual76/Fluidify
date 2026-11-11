// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** PREPARED ONLY. Presentation change while system listening, with equal availability. */
@OptIn(ExperimentalCoroutinesApi::class)
class AirPodsQualificationTileIdentityTest {
    @Test fun activeProductToActiveTestRefreshesLabelAndStopDetachesCollector() = runTest {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var identity: Any = Any()
        val presented = mutableListOf<Any>()
        val observation = TileAvailabilityObservation(
            resolve = { TileAvailabilitySource(changes) { true } },
            present = { available -> assertTrue(available); presented += identity; true },
            dispatcher = StandardTestDispatcher(testScheduler), presentationIdentity = { identity })
        observation.start(); runCurrent()
        assertEquals(1, presented.size)
        identity = Any(); changes.emit(Unit); runCurrent()
        assertEquals(2, presented.size)
        assertNotSame(presented[0], presented[1])
        observation.stop(); identity = Any(); changes.emit(Unit); runCurrent()
        assertEquals(2, presented.size)
        observation.destroy()
    }
}
