package dev.lelonio.square.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Same-directory, synced staging followed by a single atomic publication (API 26+).
 * There is intentionally no delete/rename fallback: unsupported atomic moves or
 * failed writes leave the previous index intact. A leftover .tmp is never read.
 * The store serializes calls, including creation of the staging file.
 */
internal fun writeDownloadIndexAtomically(
    destination: File,
    contents: String,
    finish: (File, File) -> Unit = { staged, target ->
        Files.move(staged.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    },
) {
    val staged = File(destination.parentFile, "${destination.name}.tmp")
    try {
        FileOutputStream(staged).use { output ->
            output.write(contents.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        finish(staged, destination)
    } finally {
        staged.delete()
    }
}
