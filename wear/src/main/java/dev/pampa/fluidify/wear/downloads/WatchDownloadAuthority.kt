package dev.pampa.fluidify.wear.downloads

import android.content.Context
import android.os.FileObserver
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** The watch's own APWelcome credential, never a phone namespace or last-account preference. */
class WatchDownloadAuthority internal constructor(
    private val stateFile: File,
    private val credentialFile: File,
) {
    class Access internal constructor(val canonical: String, val namespace: String, val lineage: String, internal val fingerprint: String)
    @Serializable private data class Receipt(val version: Int = 1, val lineage: String = UUID.randomUUID().toString(),
        val canonical: String? = null, val fingerprint: String? = null)
    private val json = Json { encodeDefaults = true }
    private var generation = UUID.randomUUID().toString()
    private var receipt: Receipt = if (!stateFile.exists()) {
        val identity = identity()
        Receipt(canonical = identity?.first, fingerprint = identity?.second).also(::persist)
    } else runCatching { json.decodeFromString(Receipt.serializer(), stateFile.readText()).also { check(it.version == 1) } }
        .getOrElse { Receipt().also(::persist) }
    private val mutable = MutableStateFlow<Access?>(null)
    val access = mutable.asStateFlow()
    private val observers = mutableListOf<(Access?) -> Unit>()
    init { capture() }

    @Synchronized fun capture(): Access? {
        val identity = identity()
        if (identity == null || receipt.canonical == null || identity.first != receipt.canonical || identity.second != receipt.fingerprint) {
            if (receipt.canonical != null) invalidateReceipt(rotateStart = false)
            return null
        }
        val old = mutable.value
        if (old == null) change(Access(identity.first, namespace(identity.first), receipt.lineage, identity.second))
        return mutable.value
    }
    @Synchronized fun isCurrent(value: Access) = capture() === value
    @Synchronized fun <T> use(value: Access, block: () -> T): T {
        if (!isCurrent(value)) throw CancellationException("Watch download account changed")
        return block()
    }
    @Synchronized internal fun observe(observer: (Access?) -> Unit) {
        observers += observer
        observer(capture())
    }
    /** Only a live native canonical identity may replace a tombstone. Caller fences its start. */
    @Synchronized internal fun startTicket(): String = generation
    @Synchronized internal fun confirm(canonical: String, ticket: String): Boolean {
        if (ticket != generation) return false
        val identity = identity() ?: return false
        if (identity.first != canonical) return false
        val next = receipt.copy(canonical = canonical, fingerprint = identity.second)
        if (next != receipt) {
            persist(next)
            receipt = next
            change(null)
        }
        return capture() != null
    }
    @Synchronized fun revoke() {
        invalidateReceipt(rotateStart = true)
    }
    private fun invalidateReceipt(rotateStart: Boolean) {
        if (rotateStart) generation = UUID.randomUUID().toString()
        receipt = Receipt()
        try { persist(receipt) } finally { change(null) }
    }
    private fun change(next: Access?) {
        if (mutable.value === next) return
        mutable.value = next
        observers.toList().forEach { it(next) }
    }
    private fun persist(value: Receipt) {
        stateFile.parentFile!!.mkdirs()
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(json.encodeToString(Receipt.serializer(), value))
        check(tmp.renameTo(stateFile)) { "Watch download authority not persisted" }
    }
    private fun identity(): Pair<String, String>? = runCatching {
        if (!credentialFile.isFile || credentialFile.length() !in 1L..65_536L) return null
        val bytes = credentialFile.readBytes()
        val obj = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        if (obj["auth_type"]?.jsonPrimitive?.intOrNull != 1) return null
        val name = obj["username"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val blob = (obj["auth_data"] ?: obj["encoded_auth_blob"])?.jsonPrimitive?.contentOrNull ?: return null
        if (Base64.getDecoder().decode(blob).isEmpty() || !credentialFile.readBytes().contentEquals(bytes)) return null
        name to hash(bytes)
    }.getOrNull()

    // Credential replacement/deletion revokes even with a screen sitting idle.
    private val watches = mutableListOf<FileObserver>()
    private fun watch() {
        val root = credentialFile.parentFile!!.parentFile!!
        root.mkdirs()
        fun add(dir: File) {
            if (!dir.isDirectory) return
            watches += object : FileObserver(dir.absolutePath, FileObserver.CLOSE_WRITE or FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO or FileObserver.CREATE or FileObserver.DELETE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) {
                override fun onEvent(event: Int, path: String?) {
                    capture()
                    if (dir == root && path == "reusable") synchronized(watches) { add(credentialFile.parentFile!!) }
                }
            }.also { it.startWatching() }
        }
        add(root.parentFile!!); add(root); add(credentialFile.parentFile!!)
    }
    companion object {
        private val instances = mutableMapOf<String, WatchDownloadAuthority>()
        fun forContext(context: Context): WatchDownloadAuthority = synchronized(instances) {
            val app = context.applicationContext
            instances.getOrPut(app.filesDir.absolutePath) {
                WatchDownloadAuthority(File(app.noBackupFilesDir, "watch-download-authority.json"),
                    File(app.filesDir, "librespot/reusable/credentials.json")).also { it.watch() }
            }
        }
        internal fun namespace(canonical: String) = hash("spotify\u0000$canonical".toByteArray())
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
