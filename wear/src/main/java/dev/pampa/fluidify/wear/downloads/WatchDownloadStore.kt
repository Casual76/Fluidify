package dev.pampa.fluidify.wear.downloads

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.pampa.fluidify.wear.link.PhoneAccountSession
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.serialization.builtins.ListSerializer

/** Stable UI facade; captured file handles never follow a later account. Legacy bytes stay put. */
class WatchDownloadStore(
    private val context: Context,
    val authority: WatchDownloadAuthority = WatchDownloadAuthority.forContext(context),
) {
    private val base = File(context.filesDir, "downloads")
    private val mutableOwners = MutableStateFlow<List<KeptOwner>>(emptyList())
    val owners = mutableOwners.asStateFlow()
    private val mutableFailed = MutableStateFlow(false)
    val saveFailed = mutableFailed.asStateFlow()
    private var pinned: Pair<WatchDownloadAuthority.Access, WatchDownloadFiles>? = null
    init { authority.observe { reload(it) } }

    private fun reload(access: WatchDownloadAuthority.Access?) {
        pinned = access?.let { captured -> captured to make(captured.namespace, object : DownloadFileAccess {
            override fun <T> use(block: () -> T): T = authority.use(captured, block)
        }) }
        mutableOwners.value = pinned?.second?.owners?.value.orEmpty()
        val files = pinned?.second
        mutableFailed.value = files?.saveFailed?.value == true
        files?.onChanged = { mutableOwners.value = files.owners.value; mutableFailed.value = files.saveFailed.value }
    }
    fun forAccount(access: WatchDownloadAuthority.Access): WatchDownloadFiles = authority.use(access) {
        if (pinned?.first !== access) reload(access)
        pinned!!.second
    }
    private fun make(namespace: String, access: DownloadFileAccess): WatchDownloadFiles {
        require(namespace.matches(Regex("[a-f0-9]{64}")))
        return WatchDownloadFiles(File(base, "accounts/$namespace"),
            context.getSharedPreferences("watch-download-failures-$namespace", Context.MODE_PRIVATE), access)
    }
    /** A current phone correlation can edit a plan; it cannot authorize the watch's offline view. */
    internal fun <T> phoneEdit(sessions: PhoneAccountSession, scope: PhoneAccountSession.Scope,
        block: (WatchDownloadFiles) -> T): T {
        val result = sessions.use(scope) {
            val files = make(scope.namespace, object : DownloadFileAccess {
                override fun <T> use(block: () -> T): T = sessions.use(scope, block)
            })
            block(files)
        }
        authority.capture()?.takeIf { it.namespace == scope.namespace }?.let { authority.use(it) { reload(it) } }
        return result
    }
    private fun current(): WatchDownloadFiles? = authority.capture()?.let(::forAccount)
    fun isKept(uri: String) = current()?.isKept(uri) == true
    fun isComplete(uri: String) = current()?.isComplete(uri) == true
    fun offlineTracks(uri: String) = current()?.offlineTracks(uri).orEmpty()
    fun ownerStatus() = current()?.ownerStatus().orEmpty()
    fun pending() = current()?.pending().orEmpty()
    fun unavailableCount() = current()?.unavailableCount() ?: 0
    fun bytesUsed() = current()?.bytesUsed() ?: 0L
    fun bytesFree() = current()?.bytesFree() ?: 0L

    fun hasLegacy(): Boolean {
        if (!File(base, "owners.json").isFile || File(base, "legacy-claimed").exists()) return false
        val reserved = File(base, "legacy-claim")
        return !reserved.exists() || reserved.readText() == authority.capture()?.namespace
    }
    /** Explicit user choice, captured before dispatch. Reserve first so a crash cannot reassign it. */
    fun claimLegacy(access: WatchDownloadAuthority.Access) = authority.use(access) {
        val marker = File(base, "legacy-claim")
        if (marker.exists()) check(marker.readText() == access.namespace) { "Legacy already claimed" }
        else {
            val tmp = File(base, "legacy-claimed.tmp")
            tmp.writeText(access.namespace)
            check(tmp.renameTo(marker))
        }
        val files = forAccount(access)
        val legacy = File(base, "owners.json")
        val list = WearCodec.json.decodeFromString(ListSerializer(KeptOwner.serializer()), legacy.readText())
        for (kind in listOf("audio", "meta")) {
            val source = File(base, kind)
            if (source.isDirectory) source.walkTopDown().filter { it.isFile && !it.name.contains(".part") }.forEach { old ->
                val target = File(files.root, "$kind/${old.relativeTo(source).invariantSeparatorsPath}")
                if (!target.exists()) {
                    target.parentFile!!.mkdirs()
                    val tmp = File(target.path + ".legacy.tmp")
                    old.copyTo(tmp, overwrite = true)
                    check(tmp.renameTo(target))
                }
            }
        }
        files.importLegacy(list)
        files.invalidateAll()
        val done = File(base, "legacy-claimed.tmp")
        done.writeText(access.namespace)
        check(done.renameTo(File(base, "legacy-claimed")))
        reload(access)
    }
    companion object {
        const val MAX_FAILURES = WatchDownloadFiles.MAX_FAILURES
        fun formatKbps(format: String?) = WatchDownloadFiles.formatKbps(format)
    }
}
