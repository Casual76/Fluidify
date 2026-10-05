package dev.pampa.fluidify.wear.library

import android.content.Context
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import com.google.android.gms.wearable.Wearable
import dev.pampa.fluidify.wear.link.CoverDirectory
import dev.pampa.fluidify.wear.link.readChannelLine
import dev.pampa.fluidify.wear.link.PhoneLink
import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.protocol.ThumbHeader
import dev.pampa.fluidify.wear.protocol.ThumbRequest
import dev.pampa.fluidify.wear.protocol.ThumbWant
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.update
import dev.lelonio.square.io.ReadWatchdog
import java.io.DataInputStream
import java.io.File

/**
 * The covers of the watch's lists: kept on the watch, asked of the phone in batches.
 *
 * A list row asks for its cover with [want]; the request waits a moment for the rest of the
 * screen's rows, then goes to the phone as one channel ([ThumbRequest]) and the answers land in
 * [store]. A cover is fetched once and then lives on the watch's disk, so the Home opens with its
 * covers even when the phone is in another room. [revision] ticks when covers arrive or a fetch
 * gives up, so the rows that were waiting redraw.
 */
class Thumbnails(context: Context, private val link: PhoneLink, private val scope: CoroutineScope) {

    private val covers = CoverDirectory(File(context.filesDir, "thumbs"), MAX_FILES)
    private val channels by lazy { Wearable.getChannelClient(context) }
    private val _revision = MutableStateFlow(0L)
    private val wanted = LinkedHashMap<String, String?>()
    private val inFlight = HashSet<String>()
    private val gaveUp = HashMap<String, Long>()
    private var job: Job? = null

    val revision: StateFlow<Long> = _revision.asStateFlow()
    init {
        scope.launch {
            link.status.collect { status ->
                if (status == LinkStatus.CONNECTED) {
                    synchronized(this@Thumbnails) { gaveUp.clear() }
                    _revision.update { it + 1 }
                }
            }
        }
    }

    /** Drops every kept cover: they came from the previous account's lists. Blocking; off the main thread. */
    fun clear() {
        synchronized(this) { wanted.clear(); gaveUp.clear() }
        covers.clear()
        _revision.update { it + 1 }
    }

    /** The kept cover for [key]; a lookup in memory, so it is safe to ask from composition. */
    fun fileFor(key: String?): File? = covers.fileFor(key)

    /** Whether the phone could not give [key] lately: the row may try the CDN itself. */
    fun unavailable(key: String?): Boolean {
        if (key == null) return true
        val until = synchronized(this) { gaveUp[key] } ?: return false
        return System.currentTimeMillis() < until
    }

    /** A row wants [key]'s cover; [url] is where the phone can find it. */
    fun want(key: String, url: String?) {
        if (!CoverDirectory.isSafeName(key) || covers.contains(key)) return
        synchronized(this) {
                if (key in inFlight || key in wanted) return
            val until = gaveUp[key]
            if (until != null && System.currentTimeMillis() < until) return
            wanted[key] = url
            if (job?.isActive == true) return
            job = scope.launch(Dispatchers.IO) {
                delay(GATHER_MS)
                drain()
            }
        }
    }

    private suspend fun drain() {
        while (true) {
            val batch = synchronized(this) {
                val taken = wanted.entries.take(ThumbRequest.MAX_WANTS).map { ThumbWant(it.key, it.value) }
                taken.forEach { wanted.remove(it.key); inFlight += it.key }
                if (taken.isEmpty()) job = null
                taken
            }
            if (batch.isEmpty()) return
            val reachable = link.reachablePhone() != null
            val received = runCatching { if (reachable) fetch(batch) else emptySet() }
                .onFailure {
                    if (it is CancellationException) {
                        synchronized(this) { batch.forEach { want -> inFlight -= want.key }; job = null }
                        throw it
                    }
                    Log.i(TAG, "thumbnails stopped: ${it.message}")
                }
                .getOrDefault(emptySet())
            val now = System.currentTimeMillis()
            synchronized(this) {
                batch.forEach { want ->
                    inFlight -= want.key
                    if (reachable && link.status.value == LinkStatus.CONNECTED && want.key !in received) gaveUp[want.key] = now + RETRY_AFTER_MS
                }
            }
            if (reachable) _revision.update { it + 1 }
        }
    }

    private suspend fun fetch(batch: List<ThumbWant>): Set<String> = withContext(Dispatchers.IO) {
        val node = link.reachablePhone() ?: return@withContext emptySet()
        val channel = channels.openChannel(node, WearPaths.THUMBS).await()
        val received = HashSet<String>()
        try {
            channels.getOutputStream(channel).await().let { out ->
                out.write(WearCodec.encode(ThumbRequest.serializer(), ThumbRequest(batch)))
                out.write('\n'.code)
                out.flush()
            }
            DataInputStream(ReadWatchdog(channels.getInputStream(channel).await(), ANSWER_WAIT_MS) { channels.close(channel) }).use { input ->
                while (true) {
                    val line = runInterruptible { input.readChannelLine(MAX_LINE) } ?: break
                    val header = WearCodec.decodeOrNull(ThumbHeader.serializer(), line.encodeToByteArray()) ?: break
                    if (header.bytes !in 1..MAX_BYTES) break
                    val bytes = ByteArray(header.bytes)
                    runInterruptible { input.readFully(bytes) }
                    if (store(header.key, bytes)) {
                        received += header.key
                        // Rows redraw as covers land, not only when the whole batch is in.
                        if (received.size % REDRAW_EVERY == 0) _revision.update { it + 1 }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { channels.close(channel).await() } }
        }
        covers.trim()
        received
    }

    private fun store(key: String, bytes: ByteArray): Boolean {
        if (!CoverDirectory.isSafeName(key)) return false
        val part = File(covers.directory, "$key.part")
        val target = covers.fileOf(key)
        return runCatching {
            part.writeBytes(bytes)
            if (!part.renameTo(target)) {
                target.delete()
                part.renameTo(target)
            }
            covers.added(key)
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "Thumbnails"
        const val GATHER_MS = 120L
        const val ANSWER_WAIT_MS = 15_000L
        const val RETRY_AFTER_MS = 10 * 60_000L
        const val MAX_FILES = 400
        const val MAX_BYTES = 512 * 1024
        const val MAX_LINE = 4 * 1024
        const val REDRAW_EVERY = 6
    }
}

/** The watch's thumbnails, for [dev.pampa.fluidify.wear.ui.common.Thumb]; null in previews and tests. */
val LocalThumbnails = staticCompositionLocalOf<Thumbnails?> { null }
