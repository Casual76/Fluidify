package dev.pampa.fluidify.wear.library

import android.content.Context
import dev.pampa.fluidify.wear.link.PhoneAccountSession.Scope
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
import kotlinx.coroutines.sync.withLock
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
    private val fetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val homeLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Forgets everything kept, in memory and on disk: the watch is no longer the account these
     * pages were read for. Blocking, so off the main thread.
     */
    fun clear() {
        memory.clear()
        fetchedAt.clear()
        // Account namespaces on disk survive logout; a different namespace cannot read them.
    }

    fun peekHome(): LibraryPage? = peek("home")
    fun peekSection(section: LibrarySection): LibraryPage? = peek("section-$section")
    fun peekContext(uri: String): ContextPage? = peek("context-${hash(uri)}")

    fun cachedHome(): LibraryPage? = read("home", LibraryPage.serializer())
    suspend fun home(): Result<LibraryPage> {
        val account = link.accountSessions.capture() ?: return Result.failure(IllegalStateException("account-changed"))
        return homeLock.withLock {
        if (!link.accountSessions.isCurrent(account)) return@withLock Result.failure(IllegalStateException("account-changed"))
        val cached = peekHome()
        if (cached != null && System.currentTimeMillis() - (fetchedAt[currentKey("home")] ?: 0) < HOME_FRESH_MS) Result.success(cached)
        else fetch("home", RpcMethod.Home, LibraryPage.serializer())
        }
    }

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

    /** Whether [uri] is in Liked Songs, as the phone knows it; null when it cannot say. */
    suspend fun isLiked(uri: String): Boolean? {
        val answer = link.request(RpcMethod.Liked(listOf(uri)), dev.pampa.fluidify.wear.protocol.LikedAnswer.serializer()).getOrNull() ?: return null
        return when (uri) {
            in answer.liked -> true
            in answer.notLiked -> false
            else -> null
        }
    }

    /**
     * Pins or unpins [uri] on the phone, and updates the kept Home at once so the change shows
     * before the next answer from the phone. True when the phone took it.
     */
    suspend fun setPinned(uri: String, pinned: Boolean): Boolean {
        val account = link.accountSessions.capture() ?: return false
        val ok = link.send(dev.pampa.fluidify.wear.protocol.Command.SetPinned(uri, pinned))?.ok == true
        if (!link.accountSessions.isCurrent(account)) return false
        if (ok) {
            val home = peekHome() ?: withContext(Dispatchers.IO) { cachedHome() }
            home?.let { page ->
                val updated = page.copy(
                    shelves = page.shelves.map { shelf ->
                        shelf.copy(items = shelf.items.map { if (it.uri == uri) it.copy(pinned = pinned) else it })
                    },
                )
                withContext(Dispatchers.IO) { write(account, "home", LibraryPage.serializer(), updated) }
            }
        }
        return ok
    }

    /** Puts pages in the cache as if the phone had sent them: for previews and screenshot tests. */
    internal fun seed(home: LibraryPage? = null, contexts: List<ContextPage> = emptyList(), sections: Map<LibrarySection, LibraryPage> = emptyMap()) {
        val account = checkNotNull(link.accountSessions.capture()) { "Seed a synthetic account session first" }
        home?.let { write(account, "home", LibraryPage.serializer(), it) }
        sections.forEach { (section, page) -> write(account, "section-$section", LibraryPage.serializer(), page) }
        contexts.forEach { write(account, "context-${hash(it.uri)}", ContextPage.serializer(), it) }
    }

    private suspend fun <T> fetch(key: String, method: RpcMethod, serializer: KSerializer<T>): Result<T> {
        val account = link.accountSessions.capture() ?: return Result.failure(IllegalStateException("account-changed"))
        val result = link.request(method, serializer)
        if (!link.accountSessions.isCurrent(account)) return Result.failure(IllegalStateException("account-changed"))
        result.getOrNull()?.let { value -> withContext(Dispatchers.IO) { write(account, key, serializer, value) } }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> peek(key: String): T? {
        val account = link.accountSessions.capture() ?: return null
        return link.accountSessions.use(account) { memory["${account.namespace}:$key"] as? T }
    }

    private fun currentKey(key: String): String = link.accountSessions.capture()?.let { "${it.namespace}:$key" } ?: "unavailable"

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String, serializer: KSerializer<T>): T? {
        val account = link.accountSessions.capture() ?: return null
        val cacheKey = "${account.namespace}:$key"
        return link.accountSessions.use(account) {
            (memory[cacheKey] as? T) ?: runCatching {
                val file = File(File(directory, hash(account.namespace)), "$key.json")
                if (!file.isFile) null else WearCodec.decodeOrNull(serializer, file.readBytes())
            }.getOrNull()?.also { memory[cacheKey] = it as Any }
        }
    }

    private fun <T> write(account: Scope, key: String, serializer: KSerializer<T>, value: T) =
        link.accountSessions.use(account) {
            val cacheKey = "${account.namespace}:$key"
            val folder = File(directory, hash(account.namespace)).apply { mkdirs() }
            val atomic = android.util.AtomicFile(File(folder, "$key.json"))
            val stream = atomic.startWrite()
            try {
                stream.write(WearCodec.encode(serializer, value))
                atomic.finishWrite(stream)
            } catch (failure: Exception) { atomic.failWrite(stream); throw failure }
            memory[cacheKey] = value as Any
            fetchedAt[cacheKey] = System.currentTimeMillis()
        }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 20_000L

        /** A Home read this recently is shown as it is, without asking the phone again. */
        const val HOME_FRESH_MS = 120_000L
    }
}
