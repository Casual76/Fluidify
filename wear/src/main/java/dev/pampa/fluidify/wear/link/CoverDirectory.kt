package dev.pampa.fluidify.wear.link

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The covers kept in one directory, as the art store and the list thumbnails both keep them: a
 * file per cover, named by its key, the least recently used ones dropped past [maxFiles].
 *
 * Which covers are there is held in memory, read from the directory once. Rows ask for their cover
 * from composition, and every `File.isFile` and `lastModified` there is a system call on the main
 * thread, repeated for each row each time a cover arrives; the answer here is a lookup. The price
 * is that a file put in the directory behind this class's back is not seen: everything that
 * writes goes through [added], [forget], [trim] and [clear].
 *
 * The last-use stamp that decides what [trim] drops is written on a background thread, at most
 * once a minute per cover.
 */
internal class CoverDirectory(val directory: File, private val maxFiles: Int) {

    private val present = ConcurrentHashMap.newKeySet<String>()
    private val stampedAt = ConcurrentHashMap<String, Long>()

    init {
        directory.mkdirs()
        directory.listFiles { file -> file.name.endsWith(EXTENSION) }?.forEach { present += it.name.removeSuffix(EXTENSION) }
    }

    /** The cover for [key] if it is there. Never touches the disk (the stamp aside, off this thread). */
    fun fileFor(key: String?): File? {
        if (key.isNullOrBlank() || !isSafeName(key) || key !in present) return null
        touchLater(key)
        return fileOf(key)
    }

    fun contains(key: String): Boolean = isSafeName(key) && key in present

    /** Where the cover for [key] goes: a safe name, so never outside [directory]. */
    fun fileOf(key: String): File = File(directory, key + EXTENSION)

    /** The file for [key] is in place: it is a cover now. */
    fun added(key: String) {
        present += key
    }

    /** The file for [key] has been deleted. */
    fun forget(key: String) {
        present -= key
        stampedAt.remove(key)
    }

    /** Past [maxFiles], drops the covers that were used longest ago. */
    fun trim() {
        if (present.size <= maxFiles) return
        val files = directory.listFiles { file -> file.name.endsWith(EXTENSION) } ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach {
            it.delete()
            forget(it.name.removeSuffix(EXTENSION))
        }
    }

    /** Drops every cover. Blocking; off the main thread. */
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
        present.clear()
        stampedAt.clear()
    }

    private fun touchLater(key: String) {
        val now = System.currentTimeMillis()
        val last = stampedAt[key]
        if (last != null && now - last < TOUCH_EVERY_MS) return
        stampedAt[key] = now
        toucher.execute { fileOf(key).setLastModified(now) }
    }

    companion object {
        private const val EXTENSION = ".webp"
        private const val TOUCH_EVERY_MS = 60_000L

        /** Keys are image ids: letters and digits, so a key can never name a path. */
        fun isSafeName(key: String): Boolean = key.isNotEmpty() && key.length <= 128 && key.all { it.isLetterOrDigit() }

        /** One quiet thread for the last-use stamps; nothing waits for it. */
        private val toucher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cover-touch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
}
