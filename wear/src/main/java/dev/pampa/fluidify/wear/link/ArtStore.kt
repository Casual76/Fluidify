package dev.pampa.fluidify.wear.link

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Covers the phone sent, on the watch's own disk.
 *
 * Keyed like the phone keys them (the Spotify image id), so a whole album is one
 * file. Kept to [MAX_FILES] by last use: about 3 MB, which on a watch with tens
 * of gigabytes is nothing, and which means the covers of what was played today
 * are there instantly when the app opens. [revision] ticks whenever a file
 * arrives, so a screen waiting for a cover that was not there yet redraws.
 */
class ArtStore(context: Context) {

    private val directory = File(context.filesDir, "art").apply { mkdirs() }
    private val _revision = MutableStateFlow(0L)

    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Told the key of each cover that lands, for the surfaces outside the app (tile, complication). */
    @Volatile
    var onStored: ((String) -> Unit)? = null

    /** The cover for [key], when it has arrived. */
    fun fileFor(key: String?): File? {
        if (key.isNullOrBlank() || !key.isSafeName()) return null
        val file = File(directory, "$key.webp")
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    fun has(key: String): Boolean = key.isSafeName() && File(directory, "$key.webp").isFile

    fun store(key: String, bytes: ByteArray) {
        if (!key.isSafeName() || bytes.isEmpty()) return
        val target = File(directory, "$key.webp")
        val part = File(directory, "$key.part")
        part.writeBytes(bytes)
        if (!part.renameTo(target)) {
            target.delete()
            part.renameTo(target)
        }
        trim()
        _revision.value = _revision.value + 1
        onStored?.invoke(key)
    }

    private fun trim() {
        val files = directory.listFiles { file -> file.name.endsWith(".webp") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun String.isSafeName(): Boolean = isNotEmpty() && length <= 128 && all { it.isLetterOrDigit() }

    companion object {
        private const val MAX_FILES = 60
    }
}
