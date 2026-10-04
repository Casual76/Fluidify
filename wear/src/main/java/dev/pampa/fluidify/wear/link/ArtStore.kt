package dev.pampa.fluidify.wear.link

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlinx.coroutines.flow.update

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

    init {
        // Old or interrupted writes are checked off the composition thread, once per process.
        toucher.execute {
            var removed = false
            directory.listFiles()?.filter { it.extension == "webp" }?.forEach { file ->
                synchronized(this) {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) removed = file.delete() || removed
                }
            }
            if (removed) _revision.update { it + 1 }
        }
    }

    /** Told the key of each cover that lands, for the surfaces outside the app (tile, complication). */
    @Volatile
    var onStored: ((String) -> Unit)? = null

    /**
     * The cover for [key], when it has arrived.
     *
     * Asked from composition, once per row of a list, so it must not write: the last-use stamp
     * that keeps the store's order is set on a background thread, at most once a minute per file.
     */
    fun fileFor(key: String?): File? {
        if (key.isNullOrBlank() || !key.isSafeName()) return null
        val file = File(directory, "$key.webp")
        if (!file.isFile) return null
        touchLater(file)
        return file
    }

    private fun touchLater(file: File) {
        val now = System.currentTimeMillis()
        if (now - file.lastModified() < TOUCH_EVERY_MS) return
        toucher.execute { file.setLastModified(now) }
    }

    fun has(key: String): Boolean = key.isSafeName() && File(directory, "$key.webp").isFile

    @Synchronized fun store(key: String, bytes: ByteArray) {
        if (!key.isSafeName() || bytes.isEmpty()) return
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        val target = File(directory, "$key.webp")
        val part = File.createTempFile("$key-", ".part", directory)
        try {
            part.writeBytes(bytes)
            if (!part.renameTo(target)) {
                target.delete()
                check(part.renameTo(target)) { "Cannot commit artwork" }
            }
        } finally {
            part.delete()
        }
        trim()
        _revision.update { it + 1 }
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
        private const val TOUCH_EVERY_MS = 60_000L

        /** One quiet thread for the last-use stamps; nothing waits for it. */
        private val toucher = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "art-touch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
}
