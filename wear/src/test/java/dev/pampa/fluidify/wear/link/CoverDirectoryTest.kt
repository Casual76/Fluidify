package dev.pampa.fluidify.wear.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The covers on the watch's disk, and what the screens are told about them without asking the disk. */
class CoverDirectoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun cover(dir: File, key: String, modified: Long = 1_000L) =
        File(dir, "$key.webp").apply {
            writeBytes(ByteArray(4))
            setLastModified(modified)
        }

    @Test
    fun coversAlreadyThereAreKnownAtOnce() {
        val dir = folder.newFolder("art")
        cover(dir, "abc")
        val covers = CoverDirectory(dir, maxFiles = 10)
        assertNotNull(covers.fileFor("abc"))
        assertTrue(covers.contains("abc"))
        assertNull(covers.fileFor("zzz"))
    }

    @Test
    fun aFileWrittenBehindItsBackIsNotSeenUntilItIsAdded() {
        val dir = folder.newFolder("art")
        val covers = CoverDirectory(dir, maxFiles = 10)
        cover(dir, "late")
        assertFalse("a lookup, not a look at the disk", covers.contains("late"))
        covers.added("late")
        assertTrue(covers.contains("late"))
        assertEquals(File(dir, "late.webp"), covers.fileFor("late"))
    }

    @Test
    fun keysThatCouldNamePathsAreNeverCovers() {
        val dir = folder.newFolder("art")
        val covers = CoverDirectory(dir, maxFiles = 10)
        assertNull(covers.fileFor("../x"))
        assertNull(covers.fileFor(""))
        assertNull(covers.fileFor(null))
        assertFalse(CoverDirectory.isSafeName("a/b"))
        assertFalse(CoverDirectory.isSafeName("a".repeat(129)))
        assertTrue(CoverDirectory.isSafeName("ab67616d0000b273"))
    }

    @Test
    fun pastTheLimitTheLongestUnusedGoFirst() {
        val dir = folder.newFolder("art")
        cover(dir, "old", modified = 1_000L)
        cover(dir, "middle", modified = 2_000L)
        cover(dir, "new", modified = 3_000L)
        val covers = CoverDirectory(dir, maxFiles = 2)
        covers.trim()
        assertFalse(covers.contains("old"))
        assertFalse(File(dir, "old.webp").exists())
        assertTrue(covers.contains("middle"))
        assertTrue(covers.contains("new"))
    }

    @Test
    fun clearingForgetsEverythingAndDeletesTheFiles() {
        val dir = folder.newFolder("art")
        cover(dir, "one")
        cover(dir, "two")
        val covers = CoverDirectory(dir, maxFiles = 10)
        covers.clear()
        assertFalse(covers.contains("one"))
        assertEquals(0, dir.listFiles()!!.size)
    }
}
