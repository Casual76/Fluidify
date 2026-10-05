package dev.pampa.fluidify.wear.link

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * One line from a channel, up to the newline and without buffering past it: on the phone's
 * channels the bytes after the line are the payload (a file, a cover), and a buffered reader
 * would swallow the start of it. Null at the end of the stream, or when the line is longer than
 * [maxBytes], which is no header anybody sent.
 */
internal fun InputStream.readChannelLine(maxBytes: Int): String? {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val next = read()
        if (next < 0) return null
        if (next == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
        bytes.write(next)
        if (bytes.size() > maxBytes) return null
    }
}
