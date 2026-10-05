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
 *
 * Which covers exist is remembered in memory ([CoverDirectory]), so the screens, which ask on
 * every redraw, never go to the disk to find out.
 */
class ArtStore(context: Context) {

    private val covers = CoverDirectory(File(context.filesDir, "art"), MAX_FILES)
    private val _revision = MutableStateFlow(0L)

    val revision: StateFlow<Long> = _revision.asStateFlow()

    init {
        // Old or interrupted writes are checked off the composition thread, once per process.
        checker.execute {
            var removed = false
            covers.directory.listFiles()?.filter { it.extension == "webp" }?.forEach { file ->
                synchronized(this) {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        if (file.delete()) {
                            covers.forget(file.nameWithoutExtension)
                            removed = true
                        }
                    }
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
     * Asked from composition, once per row of a list, so it must not touch the disk: it is a lookup
     * in what [CoverDirectory] remembers.
     */
    fun fileFor(key: String?): File? = covers.fileFor(key)

    fun has(key: String): Boolean = covers.contains(key)

    /** Drops every cover, for an account that is gone. Blocking; off the main thread. */
    @Synchronized fun clear() {
        covers.clear()
        _revision.update { it + 1 }
    }

    @Synchronized fun store(key: String, bytes: ByteArray) {
        if (!CoverDirectory.isSafeName(key) || bytes.isEmpty()) return
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        val target = covers.fileOf(key)
        val part = File.createTempFile("$key-", ".part", covers.directory)
        try {
            part.writeBytes(bytes)
            if (!part.renameTo(target)) {
                target.delete()
                check(part.renameTo(target)) { "Cannot commit artwork" }
            }
        } finally {
            part.delete()
        }
        covers.added(key)
        covers.trim()
        _revision.update { it + 1 }
        onStored?.invoke(key)
    }

    companion object {
        private const val MAX_FILES = 60

        /** One quiet thread for the start-up check of what is on the disk; nothing waits for it. */
        private val checker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "art-check").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }
}
