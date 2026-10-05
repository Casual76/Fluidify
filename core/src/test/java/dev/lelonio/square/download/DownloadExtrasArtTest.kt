package dev.lelonio.square.download

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The player's covers are addressed by file, and the queue keeps those addresses across restarts:
 * a cover must stay reachable under every name it has had, or a restored queue plays without one.
 */
class DownloadExtrasArtTest {
    private lateinit var root: File
    private val url = "https://i.scdn.co/image/ab67616d0000b273" + "0123456789abcdef01234567"

    @Before fun setUp() {
        root = Files.createTempDirectory("extras").toFile()
        DownloadExtras.attach(root) { true }
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun artDir() = File(root, "extras/art")

    /** The name covers had up to 1.6, and still have. */
    private fun originalName(): String =
        "0123456789abcdef01234567".hashCode().toUInt().toString(16) + ".jpg"

    @Test fun aCoverKeepsItsOriginalName() {
        artDir().mkdirs()
        File(artDir(), originalName()).writeText("cover")
        val found = DownloadExtras.fileOf(url, "art")!!
        assertEquals(originalName(), found.name)
    }

    @Test fun aCoverRenamedByA17BuildIsReachableUnderBothNamesAndSurvivesTheSweep() {
        artDir().mkdirs()
        val renamed = artDir().listFiles().orEmpty().size
        assertEquals(0, renamed)
        // What a 1.7 build left: the cover under its SHA-1 name only.
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            .digest("0123456789abcdef01234567".toByteArray())
            .joinToString("") { "%02x".format(it) } + ".jpg"
        File(artDir(), sha1).writeText("cover")

        DownloadExtras.sweep(trackUris = emptyList(), coverUrls = listOf(url))

        // The address a queue saved before 1.7 holds works again, and so does the one a 1.7 queue holds.
        assertEquals("cover", File(artDir(), originalName()).readText())
        assertTrue(File(artDir(), sha1).isFile)
        assertEquals(originalName(), DownloadExtras.fileOf(url, "art")!!.name)
    }
}
