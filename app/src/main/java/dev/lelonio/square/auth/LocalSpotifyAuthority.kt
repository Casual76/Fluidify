package dev.lelonio.square.auth

import android.content.Context
import android.os.FileObserver
import dev.lelonio.square.data.writeDownloadIndexAtomically
import dev.lelonio.square.data.AccountEntryMarker
import dev.lelonio.square.data.accountEntryMarker
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

/** Local storage authority only. Never a token, HistoryAccess, provider proof or network grant. */
class LocalSpotifyAuthority internal constructor(
    private val readState: () -> String?,
    private val writeState: (String) -> Unit,
    private val credentials: () -> KeptIdentity?,
) {
    internal data class KeptIdentity(val canonical: String, val fingerprint: String)
    internal data class Binding(val canonical: String, val lineage: String, val fingerprint: String)
    internal data class Verification(val lineage: String, val identity: KeptIdentity)

    @Serializable
    private data class State(
        val version: Int = 1,
        val lineage: String = UUID.randomUUID().toString(),
        val canonical: String? = null,
        val fingerprint: String? = null,
    )

    private val gate = Any()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val observers = mutableListOf<() -> Unit>()
    private var state: State

    init {
        val raw = readState()
        state = if (raw == null) {
            // Upgrade path: the native reusable cache is written from APWelcome's canonical
            // identity, not OAuth input or a last-account preference. Import it once, locally.
            val kept = credentials()
            State(canonical = kept?.canonical, fingerprint = kept?.fingerprint).also(::persist)
        } else {
            runCatching {
                check(Json.parseToJsonElement(raw).jsonObject.keys == setOf("version", "lineage", "canonical", "fingerprint"))
                json.decodeFromString(State.serializer(), raw).also {
                    check(it.version == 1)
                    UUID.fromString(it.lineage)
                    check((it.canonical == null) == (it.fingerprint == null))
                }
            }
                .getOrElse { State().also(::persist) }
        }
    }

    internal fun <T> locked(block: () -> T): T = synchronized(gate) { block() }
    internal fun observe(observer: () -> Unit) = locked { observers += observer }

    internal fun binding(): Binding? = locked {
        val canonical = state.canonical ?: return@locked null
        val actual = credentials()
        if (actual == null || actual.canonical != canonical || actual.fingerprint != state.fingerprint) {
            accountEntryMarker(AccountEntryMarker.AUTHORITY_BINDING_INVALID)
            invalidateLocked()
            return@locked null
        }
        Binding(canonical, state.lineage, actual.fingerprint)
    }

    internal fun verification(): Verification? = locked {
        credentials()?.let { Verification(state.lineage, it) }
    }

    internal fun isCurrent(proof: Verification): Boolean = locked {
        state.lineage == proof.lineage && credentials() == proof.identity
    }

    /** Called only after the service's live canonical verification; cannot bind a caller's ID. */
    internal fun confirm(proof: Verification, canonical: String): Binding? = locked {
        if (state.lineage != proof.lineage || credentials() != proof.identity ||
            canonical != proof.identity.canonical) return@locked null
        val next = state.copy(canonical = canonical, fingerprint = proof.identity.fingerprint)
        persist(next)
        state = next
        observers.toList().forEach { it() }
        binding()
    }

    /** Durable tombstone BEFORE any credential/token mutation. Failure prevents that mutation. */
    fun <T> invalidateBefore(change: () -> T): T = locked {
        accountEntryMarker(AccountEntryMarker.AUTHORITY_INVALIDATE_BEFORE_CHANGE)
        invalidateLocked()
        change()
    }

    fun invalidate() = locked {
        accountEntryMarker(AccountEntryMarker.AUTHORITY_EXPLICIT_INVALIDATE)
        invalidateLocked()
    }

    /** Native writer/deletion observation revokes; observation can never create a binding. */
    internal fun credentialsChanged() = locked { binding(); Unit }

    private fun invalidateLocked() {
        val next = State()
        state = next
        try { persist(next) }
        finally { observers.toList().forEach { it() } }
    }

    private fun persist(value: State) = writeState(json.encodeToString(State.serializer(), value))

    // Watch both directories because native may replace/delete the reusable directory itself.
    private var parentWatch: FileObserver? = null
    private var directoryWatch: FileObserver? = null
    private var credentialWatch: FileObserver? = null
    private fun watch(root: File) {
        root.mkdirs()
        fun watchKept() {
            credentialWatch?.stopWatching()
            val kept = File(root, "reusable")
            if (!kept.isDirectory) return
            credentialWatch = object : FileObserver(kept.absolutePath, WATCH_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path == "credentials.json") credentialsChanged()
                }
            }.also { it.startWatching() }
        }
        fun watchRoot() {
            directoryWatch?.stopWatching()
            if (root.isDirectory) {
                directoryWatch = object : FileObserver(root.absolutePath, WATCH_EVENTS) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == null || path == "reusable") {
                            watchKept()
                            credentialsChanged()
                        }
                    }
                }.also { it.startWatching() }
            }
            watchKept()
        }
        parentWatch = object : FileObserver(checkNotNull(root.parentFile).absolutePath, WATCH_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || path == root.name) {
                    watchRoot()
                    credentialsChanged()
                }
            }
        }.also { it.startWatching() }
        watchRoot()
    }

    companion object {
        private const val WATCH_EVENTS = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or
            FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        private val instances = mutableMapOf<String, LocalSpotifyAuthority>()
        fun forContext(context: Context): LocalSpotifyAuthority {
            val app = context.applicationContext
            val stateFile = File(app.noBackupFilesDir, "spotify-local-authority.json")
            return synchronized(instances) {
                instances.getOrPut(stateFile.absolutePath) {
                    LocalSpotifyAuthority(
                        { stateFile.takeIf { it.isFile }?.readText() },
                        { text -> stateFile.parentFile!!.mkdirs(); writeDownloadIndexAtomically(stateFile, text) },
                        { readKeptIdentity(EngineCredentials.dir(app).resolve("reusable/credentials.json")) },
                    ).also { it.watch(EngineCredentials.dir(app)) }
                }
            }
        }

        /** Runtime only: no secret bytes leave this function, and no values are logged. */
        internal fun readKeptIdentity(file: File): KeptIdentity? = runCatching {
            if (!file.isFile || file.length() !in 1L..65_536L) return@runCatching null
            val bytes = file.readBytes()
            val value = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            // AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS = 1.
            // The separate reusable directory is populated from APWelcome after authentication.
            if (value["auth_type"]?.jsonPrimitive?.intOrNull != 1) return@runCatching null
            val canonical = value["username"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val blob = (value["auth_data"] ?: value["encoded_auth_blob"])?.jsonPrimitive?.contentOrNull
                ?: return@runCatching null
            if (java.util.Base64.getDecoder().decode(blob).isEmpty()) return@runCatching null
            // A concurrent truncate/rewrite cannot be accepted as a stable credential snapshot.
            if (!file.isFile || !file.readBytes().contentEquals(bytes)) return@runCatching null
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            KeptIdentity(canonical, digest)
        }.getOrNull()
    }
}
