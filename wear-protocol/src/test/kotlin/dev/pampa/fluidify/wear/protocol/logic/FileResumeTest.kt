package dev.pampa.fluidify.wear.protocol.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class FileResumeTest {
    @Test fun resumesOnlyTheSameFile() {
        assertEquals(42L, FileResume.offset(42, 100, "same", "same"))
        assertEquals(0L, FileResume.offset(42, 100, "old", "new"))
        assertEquals(0L, FileResume.offset(42, 100, null, "new"))
        assertEquals(0L, FileResume.offset(100, 100, "same", "same"))
        assertEquals(0L, FileResume.offset(-1, 100, "same", "same"))
    }
}
