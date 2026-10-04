package dev.lelonio.square.wear

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.util.Log
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import dev.lelonio.square.update.WatchApkValidation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import com.google.android.gms.wearable.Wearable
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.compareVersions
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource
import dev.lelonio.square.BuildConfig
import dev.pampa.fluidify.wear.protocol.Hello
import dev.pampa.fluidify.wear.protocol.UpdateOffer
import dev.pampa.fluidify.wear.protocol.UpdatePhase
import dev.pampa.fluidify.wear.protocol.UpdateStatus
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Keeps the watch app up to date from the phone.
 *
 * The phone is where updates already arrive (the Pampa Store and the app's own
 * updater both live here), so it is also what notices the watch is behind: every
 * hello from the watch carries its version, which is compared with
 * `manifest-wear.json`, the watch build's entry beside the phone's own manifest.
 * A newer build is downloaded here, checked here (package, version, checksum and
 * that it is signed with this app's own key, without which Android would refuse
 * it as an update anyway) and only then offered to the watch, which takes it over
 * Bluetooth and installs it. The watch never needs Wi-Fi or a computer for this.
 *
 * Development builds can also push any APK picked on the phone ([pushFile]).
 */
class WatchUpdateCoordinator(
    private val context: Context,
    private val link: WearLink,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val version: String) : State
        data class Downloading(val version: String, val progress: Float?) : State
        data class Offered(val version: String) : State
        data class Sending(val version: String) : State
        data class Installing(val version: String) : State
        data class AwaitingConfirmation(val version: String) : State
        data class Installed(val version: String) : State
        data class Failed(val reason: String) : State
    }

    /** What the phone knows about the watch, from its last hello. */
    data class WatchInfo(val nodeId: String, val name: String, val hello: Hello, val seenAtMs: Long)

    private val prefs = context.getSharedPreferences("square_watch", Context.MODE_PRIVATE)
    private val http = EngineHttp(userAgent = "Fluidify/${BuildConfig.VERSION_NAME}")
    private val installer = AndroidAppUpdateInstaller(context, http)
    private val updater = EngineAppUpdater(
        http = http,
        source = UpdateSource(manifestUrl = WEAR_MANIFEST_URL, applicationId = context.packageName),
        installer = installer,
    )
    private val lock = Mutex()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _watch = MutableStateFlow<WatchInfo?>(null)
    val watch: StateFlow<WatchInfo?> = _watch.asStateFlow()

    /** The APK ready to send, and the offer that describes it. */
    private var ready: Pair<File, UpdateOffer>? = restoreOffer()

    var autoUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    /** A watch said hello. Checks for a newer build now and then, and pushes it when allowed. */
    fun onWatchHello(nodeId: String, hello: Hello) {
        scope.launch {
            val name = runCatching {
                Wearable.getNodeClient(context).connectedNodes.await().firstOrNull { it.id == nodeId }?.displayName
            }.getOrNull() ?: _watch.value?.name.orEmpty()
            _watch.value = WatchInfo(nodeId, name, hello, System.currentTimeMillis())
            val due = System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) > CHECK_EVERY_MS
            if (autoUpdate && due) {
                val update = check()
                if (update != null) push(update, requestedByUser = false)
            }
        }
    }

    /** Asks the manifest whether there is a build newer than the watch's. */
    suspend fun check(): AvailableAppUpdate? = lock.withLock {
        val watch = _watch.value ?: return null
        _state.value = State.Checking
        val result = updater.check(watch.hello.versionName)
        result.fold(
            onSuccess = { update ->
                _state.value = if (update == null) State.UpToDate(watch.hello.versionName) else State.Available(update.version)
                if (update == null) prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
                update
            },
            onFailure = {
                Log.w(TAG, "watch update check failed: $it")
                _state.value = State.Failed(it.message ?: "network")
                null
            },
        )
    }

    /**
     * The latest watch build from the store, downloaded and checked the same way as an
     * update (package, version, signature), for a watch that does not have the app at
     * all: the phone's installer streams it in over ADB (see install/WatchInstaller).
     */
    suspend fun latestApk(onProgress: (Float?) -> Unit): Result<File> = lock.withLock {
        val update = updater.check(NO_VERSION).getOrElse { error ->
            // A manifest that is not there at all (a 404 before the first watch release) is the
            // same news as one with nothing in it.
            val missing = error.message.orEmpty().contains("404")
            return@withLock Result.failure(if (missing) dev.lelonio.square.wear.install.NoReleaseException() else error)
        } ?: return@withLock Result.failure(dev.lelonio.square.wear.install.NoReleaseException())
        if (!update.sha256.matches(Regex("[a-fA-F0-9]{64}"))) return@withLock Result.failure(IllegalStateException("missing-checksum"))
        val file = runCatching {
            installer.download(update) { progress -> onProgress(progress.progress.takeIf { it in 0f..1f }) }
        }.getOrElse { return@withLock Result.failure(it) }
        rejectArchive(file, update.version, update.sha256)?.let { return@withLock Result.failure(IllegalStateException(it)) }
        Result.success(file)
    }

    /**
     * An APK picked on the phone, copied and checked like a downloaded one (package and signature),
     * for the first install from the phone when the store has nothing to offer yet.
     */
    suspend fun apkFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(context.cacheDir, "watch-install.apk")
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: error("cannot read the file")
            val version = archiveVersion(target) ?: error("not-an-apk")
            rejectArchive(target, version)?.let { error(it) }
            target
        }
    }

    /** Checks and, if there is something, sends it. The "update the watch" row. */
    fun checkAndPush() {
        scope.launch {
            val update = check() ?: return@launch
            push(update, requestedByUser = true)
        }
    }

    private fun push(update: AvailableAppUpdate, requestedByUser: Boolean) {
        val watch = _watch.value ?: return
        val work = OneTimeWorkRequestBuilder<WatchUpdateDownloadWorker>().setInputData(workDataOf(
            "version" to update.version, "url" to update.downloadUrl, "sha256" to update.sha256,
            "bytes" to update.sizeBytes, "node" to watch.nodeId, "user" to requestedByUser,
        )).build()
        WorkManager.getInstance(context).enqueueUniqueWork("watch-update-download", ExistingWorkPolicy.KEEP, work)
    }

    suspend fun downloadAndOffer(update: AvailableAppUpdate, nodeId: String, requestedByUser: Boolean): Boolean {
        if (!update.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            _state.value = State.Failed("missing-checksum")
            return false
        }
        val file = runCatching {
            installer.download(update) { progress ->
                _state.value = State.Downloading(update.version, progress.progress.takeIf { it in 0f..1f })
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            _state.value = State.Failed(it.message ?: "download")
            return false
        }
        offer(file, update.version, update.sha256, requestedByUser, nodeId)
        val success = _state.value is State.Offered
        if (success) prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
        return success
    }

    /** Development builds: send an APK picked on the phone. */
    fun pushFile(uri: Uri) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val target = File(context.cacheDir, "watch-pushed.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                target
            }
            val version = archiveVersion(file) ?: run {
                _state.value = State.Failed("not-an-apk")
                return@launch
            }
            offer(file, version, sha256 = "", requestedByUser = true)
        }
    }

    private suspend fun offer(file: File, version: String, sha256: String, requestedByUser: Boolean, nodeId: String? = _watch.value?.nodeId) {
        val node = nodeId ?: run {
            _state.value = State.Failed("no-watch")
            return
        }
        withContext(Dispatchers.IO) { rejectArchive(file, version) }?.let { reason ->
            _state.value = State.Failed(reason)
            return
        }
        val checksum = withContext(Dispatchers.IO) { sha256Of(file) }
        if (sha256.isNotBlank() && !sha256.equals(checksum, ignoreCase = true)) {
            _state.value = State.Failed("checksum")
            return
        }
        val offer = UpdateOffer(
            versionName = version,
            sizeBytes = file.length(),
            sha256 = checksum,
            requestedByUser = requestedByUser,
        )
        val saved = withContext(Dispatchers.IO) { file.copyTo(File(context.cacheDir, "watch-ready.apk"), overwrite = true) }
        ready = saved to offer
        prefs.edit().putString(KEY_OFFER, WearCodec.json.encodeToString(UpdateOffer.serializer(), offer))
            .putString(KEY_OFFER_NODE, node).putLong(KEY_OFFER_AT, System.currentTimeMillis()).commit()
        _state.value = State.Offered(version)
        link.send(node, WearPaths.UPDATE_OFFER, WearCodec.encode(UpdateOffer.serializer(), offer))
    }

    /** What the watch says about an offer or an install. */
    fun onStatus(nodeId: String, status: UpdateStatus) {
        if (nodeId != prefs.getString(KEY_OFFER_NODE, null) || ready?.second?.versionName != status.versionName) return
        when (status.phase) {
            UpdatePhase.ACCEPT -> scope.launch { send(nodeId) }
            UpdatePhase.DECLINE -> _state.value = State.UpToDate(_watch.value?.hello?.versionName ?: status.versionName)
            UpdatePhase.RECEIVING -> _state.value = State.Sending(status.versionName)
            UpdatePhase.INSTALLING -> _state.value = State.Installing(status.versionName)
            UpdatePhase.AWAITING_CONFIRMATION -> _state.value = State.AwaitingConfirmation(status.versionName)
            UpdatePhase.INSTALLED -> _state.value = State.Installed(status.versionName)
            UpdatePhase.FAILED -> _state.value = State.Failed(status.reason ?: "install")
        }
    }

    private suspend fun send(nodeId: String) {
        val (file, offer) = ready ?: restoreOffer()?.also { ready = it } ?: run {
            return
        }
        if (System.currentTimeMillis() - prefs.getLong(KEY_OFFER_AT, 0) > OFFER_TTL_MS) return
        withContext(Dispatchers.IO) { rejectArchive(file, offer.versionName, offer.sha256) }?.let {
            _state.value = State.Failed(it)
            return
        }
        _state.value = State.Sending(offer.versionName)
        runCatching {
            val channels = Wearable.getChannelClient(context)
            val channel = channels.openChannel(nodeId, WearPaths.UPDATE_APK).await()
            try { channels.sendFile(channel, Uri.fromFile(file)).await() }
            finally { withContext(NonCancellable) { runCatching { channels.close(channel).await() } } }
        }.onFailure {
            Log.w(TAG, "sending the watch build failed", it)
            _state.value = State.Failed(it.message ?: "send")
        }
    }

    /** Why this APK must not be offered, or null. */
    private fun rejectArchive(file: File, version: String, checksum: String = WatchApkValidation.sha256(file)): String? =
        WatchApkValidation.reject(context, file, version, checksum)

    private fun restoreOffer(): Pair<File, UpdateOffer>? = runCatching {
        if (System.currentTimeMillis() - prefs.getLong(KEY_OFFER_AT, 0) > OFFER_TTL_MS) return null
        val file = File(context.cacheDir, "watch-ready.apk").takeIf { it.isFile } ?: return null
        val offer = WearCodec.json.decodeFromString(UpdateOffer.serializer(), prefs.getString(KEY_OFFER, null) ?: return null)
        file to offer
    }.getOrNull()

    private fun archiveVersion(file: File): String? = runCatching {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.versionName
    }.getOrNull()

    private fun sha256Of(file: File): String = WatchApkValidation.sha256(file)

    companion object {
        /** Older than any build: what "the watch has nothing installed" is checked as. */
        private const val NO_VERSION = "0.0.0"

        private const val TAG = "WatchUpdate"
        private const val KEY_AUTO = "auto_update"
        private const val KEY_CHECKED = "checked_at"
        private const val KEY_OFFER = "offer"
        private const val KEY_OFFER_NODE = "offer_node"
        private const val KEY_OFFER_AT = "offer_at"
        private const val OFFER_TTL_MS = 30 * 60_000L
        private const val CHECK_EVERY_MS = 6 * 60 * 60_000L

        /** The watch build's manifest, beside the phone's own (see docs/pampa-store-release.md). */
        const val WEAR_MANIFEST_URL =
            "https://raw.githubusercontent.com/Casual76/Fluidify/master/manifest-wear.json"
    }
}
