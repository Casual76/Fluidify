package dev.pampa.fluidify.wear.library

import android.content.Context
import dev.pampa.fluidify.wear.link.PhoneLink
import dev.pampa.fluidify.wear.protocol.ContextPage
import dev.pampa.fluidify.wear.protocol.DeviceList
import dev.pampa.fluidify.wear.protocol.LibraryPage
import dev.pampa.fluidify.wear.protocol.LibrarySection
import dev.pampa.fluidify.wear.protocol.QueueWindow
import dev.pampa.fluidify.wear.protocol.RpcMethod
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File
import java.security.MessageDigest

/**
 * The phone's library and state, as seen from the watch, with a memory.
 *
 * Every answer is kept on the watch's disk, and every screen shows the kept copy
 * first and the fresh one when it arrives: the library opens instantly, even
 * with the phone in another room, and Bluetooth only ever makes it more current,
 * never slower to appear. The queue and the devices are not kept: they are only
 * right at the moment they are asked for.
 */
class PhoneLibrary(context: Context, private val link: PhoneLink) {

    private val directory = File(context.filesDir, "library").apply { mkdirs() }

    /**
     * What has been read or received this session, so a screen coming back (the Home, after the
     * player) has its list in the first frame without touching the disk on the main thread —
     * which, done in composition, was part of the stutter on the swipe to the Home.
     */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun peekHome(): LibraryPage? = memory["home"] as? LibraryPage
    fun peekSection(section: LibrarySection): LibraryPage? = memory["section-$section"] as? LibraryPage
    fun peekContext(uri: String): ContextPage? = memory["context-${hash(uri)}"] as? ContextPage

    fun cachedHome(): LibraryPage? = read("home", LibraryPage.serializer())
    suspend fun home(): Result<LibraryPage> = fetch("home", RpcMethod.Home, LibraryPage.serializer())

    fun cachedSection(section: LibrarySection): LibraryPage? = read("section-$section", LibraryPage.serializer())
    suspend fun section(section: LibrarySection): Result<LibraryPage> =
        fetch("section-$section", RpcMethod.Library(section), LibraryPage.serializer())

    fun cachedContext(uri: String): ContextPage? = read("context-${hash(uri)}", ContextPage.serializer())
    suspend fun context(uri: String): Result<ContextPage> =
        fetch("context-${hash(uri)}", RpcMethod.Context(uri), ContextPage.serializer())

    suspend fun search(query: String): Result<LibraryPage> =
        link.request(RpcMethod.Search(query), LibraryPage.serializer(), timeoutMs = SEARCH_TIMEOUT_MS)

    suspend fun queue(): Result<QueueWindow> = link.request(RpcMethod.Queue(), QueueWindow.serializer())

    suspend fun devices(): Result<DeviceList> = link.request(RpcMethod.Devices, DeviceList.serializer())

    /**
     * Pins or unpins [uri] on the phone, and updates the kept Home at once so the change shows
     * before the next answer from the phone. True when the phone took it.
     */
    suspend fun setPinned(uri: String, pinned: Boolean): Boolean {
        val ok = link.send(dev.pampa.fluidify.wear.protocol.Command.SetPinned(uri, pinned))?.ok == true
        if (ok) {
            val home = peekHome() ?: withContext(Dispatchers.IO) { cachedHome() }
            home?.let { page ->
                val updated = page.copy(
                    shelves = page.shelves.mapIndexed { index, shelf ->
                        if (index != 0) shelf else shelf.copy(items = shelf.items.map { if (it.uri == uri) it.copy(pinned = pinned) else it })
                    },
                )
                withContext(Dispatchers.IO) { write("home", LibraryPage.serializer(), updated) }
            }
        }
        return ok
    }

    /** Puts pages in the cache as if the phone had sent them: for previews and screenshot tests. */
    internal fun seed(home: LibraryPage? = null, contexts: List<ContextPage> = emptyList(), sections: Map<LibrarySection, LibraryPage> = emptyMap()) {
        home?.let { write("home", LibraryPage.serializer(), it) }
        sections.forEach { (section, page) -> write("section-$section", LibraryPage.serializer(), page) }
        contexts.forEach { write("context-${hash(it.uri)}", ContextPage.serializer(), it) }
    }

    private suspend fun <T> fetch(key: String, method: RpcMethod, serializer: KSerializer<T>): Result<T> {
        val result = link.request(method, serializer)
        result.getOrNull()?.let { value -> withContext(Dispatchers.IO) { write(key, serializer, value) } }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String, serializer: KSerializer<T>): T? {
        (memory[key] as? T)?.let { return it }
        return runCatching {
            val file = File(directory, "$key.json")
            if (!file.isFile) return null
            WearCodec.decodeOrNull(serializer, file.readBytes())
        }.getOrNull()?.also { memory[key] = it as Any }
    }

    private fun <T> write(key: String, serializer: KSerializer<T>, value: T) {
        memory[key] = value as Any
        runCatching {
            val part = File(directory, "$key.part")
            part.writeBytes(WearCodec.encode(serializer, value))
            part.renameTo(File(directory, "$key.json"))
        }
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 20_000L
    }
}
