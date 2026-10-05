package dev.lelonio.square.io

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The player's covers for downloaded tracks are `file://` addresses: reading them as HTTP left
 * every downloaded song without a cover in the player, the notification and the widget.
 */
class HttpFetchTest {

    @Test fun aFileAddressIsReadFromTheDisk() {
        val file = File.createTempFile("cover with spaces", ".jpg")
        try {
            val bytes = ByteArray(1_000) { it.toByte() }
            file.writeBytes(bytes)
            assertArrayEquals(bytes, HttpFetch.bytes(file.toURI().toString(), 2_000))
        } finally {
            file.delete()
        }
    }

    @Test fun aFileLargerThanTheLimitIsRefused() {
        val file = File.createTempFile("big", ".jpg")
        try {
            file.writeBytes(ByteArray(3_000))
            assertNull(HttpFetch.bytes(file.toURI().toString(), 2_000))
        } finally {
            file.delete()
        }
    }
}
