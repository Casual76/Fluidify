package dev.lelonio.square.wear

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import dev.lelonio.square.update.WatchApkValidation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.ChannelClient
import dev.lelonio.square.io.WriteWatchdog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.foundation.compareVersions
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource
import dev.lelonio.square.BuildConfig
import dev.lelonio.square.update.word
import dev.pampa.fluidify.wear.protocol.Hello
import dev.pampa.fluidify.wear.protocol.UpdateChangelog
import dev.pampa.fluidify.wear.protocol.UpdateCheckReply
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * The release line to check on, asked each time: it is a setting and can change between two
     * checks (see PreferencesStore.updateChannel). Stable when nothing says otherwise.
     */
    private val channel: () -> UpdateChannel = { UpdateChannel.STABLE },
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State

        /** [checkedAtMs] is when the manifest said so; 0 when this was learned from the watch. */
        data class UpToDate(val version: String, val checkedAtMs: Long = 0) : State

        /** [changelog] is the release's notes as the manifest has them, blank when it has none. */
        data class Available(val version: String, val changelog: String = "") : State
        data class Downloading(val version: String, val progress: Float?) : State
        data class Offered(val version: String, val changelog: String = "") : State
        /**
         * The build is on its way to the watch: sent over Bluetooth by this phone, or, when [overWifi],
         * fetched by the watch itself over its own Wi-Fi (see [UpdateOffer.downloadUrl]).
         */
        data class Sending(val version: String, val progress: Float? = null, val overWifi: Boolean = false) : State
        data class Installing(val version: String) : State
        data class AwaitingConfirmation(val version: String) : State

        /**
         * The watch has the build and will install it when its own music stops: it never replaces
         * the app under a song it is playing (that ends the song). Not a failure and not a wait
         * for the person: nothing is asked of them.
         */
        data class WaitingForPlayback(val version: String) : State
        data class Installed(val version: String, val changelog: String = "") : State

        /**
         * The watch declined an automatic push because the person turned automatic updates off on
         * it (see [REASON_AUTO_OFF]). Not a fault, and nothing to retry: the page says where the
         * update is done instead, and no notification is made of it.
         */
        data object AutoUpdateOff : State
        data class Failed(val reason: String) : State
    }

    /**
     * What the last check or install left behind, kept across restarts.
     *
     * The state above lives in memory and is [State.Idle] again in every new process, which is
     * most of the times the page is opened: it said "Not checked yet" about a watch that had been
     * checked an hour before. This is what it can say instead. [changelog] is the notes of
     * [version], when they are known, for the "What's new" row.
     */
    data class Outcome(val version: String, val atMs: Long, val changelog: String = "")

    /** The answer to a check the watch asked for; see [onUpdateRequest]. */
    private sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class Update(val version: String) : CheckResult
        data object Failed : CheckResult
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
    private val sendLock = Mutex()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _watch = MutableStateFlow<WatchInfo?>(null)
    val watch: StateFlow<WatchInfo?> = _watch.asStateFlow()

    private val readyLock = Any()
    private var readyLoaded = false
    private var readyValue: Pair<File, UpdateOffer>? = null

    /**
     * The APK ready to send, and the offer that describes it.
     *
     * Restored from the preferences and the disk the first time it is asked for, not when this is
     * built: the coordinator is made with the bridge, on the main thread, in every process the
     * phone starts, and a phone with no watch used to read a file and a preference at startup
     * for an offer nobody would ever ask about. Callers that may be on the main thread load it
     * first from the IO dispatcher (`withContext(Dispatchers.IO) { ready }`).
     */
    private var ready: Pair<File, UpdateOffer>?
        get() = synchronized(readyLock) {
            if (!readyLoaded) {
                readyValue = restoreOffer()
                readyLoaded = true
            }
            readyValue
        }
        set(value) = synchronized(readyLock) {
            readyValue = value
            readyLoaded = true
        }

    init {
        scope.launch {
            state.collect { WatchUpdateNotifications.show(context, it) }
        }
    }

    var autoUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    /** A watch said hello. Checks for a newer build now and then, and pushes it when allowed. */
    fun onWatchHello(nodeId: String, hello: Hello) {
        scope.launch {
            noteWatch(nodeId, hello)
            val cached = withContext(Dispatchers.IO) { ready }
            if (cached != null && compareVersions(hello.versionName, cached.second.versionName) >= 0) {
                val notes = savedChangelog()
                rememberOutcome(hello.versionName, notes)
                _state.value = State.Installed(hello.versionName, notes)
                clearReady()
                return@launch
            }
            if (settleAgainst(hello.versionName)) return@launch
            val due = System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) > CHECK_EVERY_MS
            if (autoUpdate && due) {
                val update = check()
                if (update != null) push(update, requestedByUser = false)
            }
        }
    }

    /**
     * Lets the version the watch says it runs overrule what this phone last believed about an
     * update for it.
     *
     * The page said "1.7.1 available" (or "waiting for the watch", or "installing") about a watch
     * already on 1.7.1, for as long as this process lived: those states are only ever left by the
     * next step of the same update, and a watch updated some other way — or whose "installed" was
     * lost with the process that the install replaced — never sends that step. A hello is the watch
     * saying what it is now, which settles it. True when it did.
     */
    private fun settleAgainst(watchVersion: String): Boolean {
        val pending = when (val current = _state.value) {
            is State.Available -> current.version
            is State.Downloading -> current.version
            is State.Offered -> current.version
            is State.Sending -> current.version
            is State.Installing -> current.version
            is State.AwaitingConfirmation -> current.version
            is State.WaitingForPlayback -> current.version
            else -> return false
        }
        if (compareVersions(watchVersion, pending) < 0) return false
        val installedNow = compareVersions(watchVersion, pending) == 0 && _state.value !is State.Available
        if (installedNow) {
            val notes = savedChangelog()
            rememberOutcome(watchVersion, notes)
            _state.value = State.Installed(watchVersion, notes)
        } else {
            rememberOutcome(watchVersion, notes = null)
            _state.value = State.UpToDate(watchVersion, System.currentTimeMillis())
        }
        clearReady()
        return true
    }

    /** What is known of the watch: who it is, from its hello, and when that was heard. */
    private suspend fun noteWatch(nodeId: String, hello: Hello) {
        val name = catchingNonCancel {
            Wearable.getNodeClient(context).connectedNodes.await().firstOrNull { it.id == nodeId }?.displayName
        }.getOrNull() ?: _watch.value?.name.orEmpty()
        _watch.value = WatchInfo(nodeId, name, hello, System.currentTimeMillis())
    }

    /**
     * The release line changed (see PreferencesStore.updateChannel): what was learned on the other
     * one no longer holds.
     *
     * The six-hour gate is cleared so that the next hello or page open checks at once, and so is
     * the memory of the last outcome and whatever the page was saying about it ("up to date" on
     * stable is not news about beta). A transfer already under way is left alone: it is not about
     * the channel, and an APK already on the watch's way is still a newer build.
     */
    fun onChannelChanged() {
        prefs.edit().remove(KEY_CHECKED).remove(KEY_OUTCOME_VERSION).remove(KEY_OUTCOME_AT).remove(KEY_OUTCOME_NOTES).apply()
        _state.update { current ->
            when (current) {
                is State.Idle, is State.UpToDate, is State.Available, is State.Failed, is State.AutoUpdateOff, is State.Installed -> State.Idle
                else -> current
            }
        }
    }

    /** Asks the manifest whether there is a build newer than the watch's. */
    suspend fun check(): AvailableAppUpdate? = lock.withLock {
        val watch = _watch.value ?: return null
        _state.value = State.Checking
        val result = updater.check(watch.hello.versionName, channel())
        result.fold(
            onSuccess = { update ->
                val now = System.currentTimeMillis()
                _state.value = if (update == null) {
                    State.UpToDate(watch.hello.versionName, now)
                } else {
                    State.Available(update.version, update.changelog)
                }
                if (update == null) {
                    prefs.edit().putLong(KEY_CHECKED, now).apply()
                    rememberOutcome(watch.hello.versionName, notes = null)
                }
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
        val update = updater.check(NO_VERSION, channel()).getOrElse { error ->
            // A manifest that is not there at all (a 404 before the first watch release) is the
            // same news as one with nothing in it.
            val missing = error.message.orEmpty().contains("404")
            return@withLock Result.failure(if (missing) dev.lelonio.square.wear.install.NoReleaseException() else error)
        } ?: return@withLock Result.failure(dev.lelonio.square.wear.install.NoReleaseException())
        if (!update.sha256.matches(SHA256_HEX)) return@withLock Result.failure(IllegalStateException("missing-checksum"))
        val file = catchingNonCancel {
            installer.download(update) { progress -> onProgress(progress.progress.takeIf { it in 0f..1f }) }
        }.getOrElse { return@withLock Result.failure(it) }
        // Against the manifest's own checksum: that is the comparison that can catch a bad download.
        rejectArchive(file, update.version, update.sha256)?.let {
            file.delete()
            return@withLock Result.failure(IllegalStateException(it))
        }
        Result.success(file)
    }

    /**
     * An APK picked on the phone, copied and checked like a downloaded one (package and signature),
     * for the first install from the phone when the store has nothing to offer yet.
     */
    suspend fun apkFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "watch-install.apk")
        catchingNonCancel {
            // A null stream is a file that could not be opened: said so, not left to be offered
            // as whatever the previous pick left in the same cache file.
            val source = context.contentResolver.openInputStream(uri) ?: error("cannot read the file")
            source.use { input -> target.outputStream().use { input.copyTo(it) } }
            val version = archiveVersion(target) ?: error("not-an-apk")
            // Hashed once. There is no manifest to compare with for a file the person picked, so
            // the checksum is the file's own, which is what the validation then re-reads against.
            val hash = sha256Of(target)
            rejectArchive(target, version, hash, actualSha256 = hash)?.let { error(it) }
            target
        }.onFailure { target.delete() }
    }

    /** Checks and, if there is something, sends it. The "update the watch" row. */
    fun checkAndPush() {
        scope.launch { checkAndPushNow() }
    }

    /**
     * [checkAndPush], for a caller that has to hold its process open until the check is made (a
     * broadcast receiver: see WatchUpdateRetryReceiver). The transfer itself goes on in WorkManager.
     */
    suspend fun checkAndPushAndWait() {
        checkAndPushNow()
    }

    /**
     * The version being worked on right now, when an update is already on its way to the watch.
     *
     * A check asked for in the middle of one (the row pressed twice, a hello, the watch's own
     * request) would replace its progress with "checking" and could start the same transfer
     * again; it is told about the one that is running instead.
     */
    private fun inFlightVersion(): String? = when (val current = _state.value) {
        is State.Downloading -> current.version
        is State.Offered -> current.version
        is State.Sending -> current.version
        is State.Installing -> current.version
        is State.AwaitingConfirmation -> current.version
        is State.WaitingForPlayback -> current.version
        else -> null
    }

    /** [checkAndPush] as a suspend function, saying what it found. */
    private suspend fun checkAndPushNow(): CheckResult {
        inFlightVersion()?.let { return CheckResult.Update(it) }
        // No hello yet, so no version to compare with: not "up to date", just not known.
        val watch = _watch.value ?: return CheckResult.Failed
        val update = check()
        if (update != null) {
            push(update, requestedByUser = true)
            return CheckResult.Update(update.version)
        }
        if (_state.value !is State.Failed) return CheckResult.UpToDate
        // A cached update is also usable when the manifest cannot be reached.
        val cached = withContext(Dispatchers.IO) { ready }
        if (cached != null && compareVersions(cached.second.versionName, watch.hello.versionName) > 0) {
            offer(
                cached.first, cached.second.versionName, cached.second.sha256,
                requestedByUser = true, nodeId = watch.nodeId, changelog = savedChangelog(),
            )
            return CheckResult.Update(cached.second.versionName)
        }
        return CheckResult.Failed
    }

    /**
     * The watch asked, from its own "Check for updates" row, whether there is something for it.
     *
     * Does what the phone's row does (a check, and the transfer if there is a build) and answers
     * with what it found, because "nothing new" would otherwise be silence the watch cannot tell
     * from a phone that never heard. [hello] is the watch's own, sent with the request: this may
     * be a phone just woken for the message, which has not heard the watch's hello yet and would
     * not know which version to compare with.
     */
    suspend fun onUpdateRequest(nodeId: String, hello: Hello) {
        noteWatch(nodeId, hello)
        val reply = when (val result = checkAndPushNow()) {
            CheckResult.UpToDate -> UpdateCheckReply(UpdateCheckReply.UP_TO_DATE, hello.versionName)
            is CheckResult.Update -> UpdateCheckReply(UpdateCheckReply.UPDATE, result.version)
            CheckResult.Failed -> UpdateCheckReply(UpdateCheckReply.FAILED)
        }
        link.send(nodeId, WearPaths.UPDATE_REQUEST, WearCodec.encode(UpdateCheckReply.serializer(), reply))
    }

    private fun push(update: AvailableAppUpdate, requestedByUser: Boolean) {
        val watch = _watch.value ?: return
        val work = OneTimeWorkRequestBuilder<WatchUpdateDownloadWorker>()
            // A download needs a network: without the constraint the worker ran at once on a phone
            // with none, failed, and burned its retries before the connection came back. And one
            // nobody asked for waits for an unmetered one: twenty megabytes of someone's mobile
            // data is not a thing to spend on a push they did not request. The person who pressed
            // the row, or the watch's own "Check for updates", meant it, on whatever network.
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (requestedByUser) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build(),
            )
            .setInputData(workDataOf(
                "version" to update.version, "url" to update.downloadUrl, "sha256" to update.sha256,
                "bytes" to update.sizeBytes, "node" to watch.nodeId, "user" to requestedByUser,
                // WorkManager's whole input is 10 KB: the notes are cut, and the watch gets them
                // shorter still (see offerChecked).
                "changelog" to UpdateChangelog.truncate(update.changelog, UpdateChangelog.WORK_MAX_CHARS),
            )).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "watch-update-download",
            pushPolicy("${update.version}@${watch.nodeId}", requestedByUser),
            work,
        )
    }

    /**
     * What to do with a queued download when another is asked for.
     *
     * As [enqueuePolicy], with one more case: the same job asked for by the person when it was
     * queued by the automatic path. That one is waiting for an unmetered network, and what they
     * pressed was "now": it is replaced by the unconstrained one.
     */
    private fun pushPolicy(key: String, requestedByUser: Boolean): ExistingWorkPolicy {
        val previousKey = prefs.getString(KEY_PUSH_JOB, null)
        val previousUser = prefs.getBoolean(KEY_PUSH_USER, false)
        val policy = when {
            previousKey != null && previousKey != key -> ExistingWorkPolicy.REPLACE
            requestedByUser && !previousUser -> ExistingWorkPolicy.REPLACE
            else -> ExistingWorkPolicy.KEEP
        }
        prefs.edit().putString(KEY_PUSH_JOB, key)
            .putBoolean(KEY_PUSH_USER, requestedByUser || (previousKey == key && previousUser)).apply()
        return policy
    }

    /**
     * What to do with a unique job that is already queued or running when the same one is asked for.
     *
     * The same job asked twice (a hello every few minutes) is [ExistingWorkPolicy.KEEP]: the one
     * running is the one wanted. A different one, for another version or another watch, used to be
     * silently dropped by KEEP, and the newer build or the second watch waited for nothing:
     * [ExistingWorkPolicy.REPLACE]. [key] names the job, [slot] where its last name is remembered
     * (preferences, not memory: the job outlives the process).
     */
    private fun enqueuePolicy(slot: String, key: String): ExistingWorkPolicy {
        val previous = prefs.getString(slot, null)
        prefs.edit().putString(slot, key).apply()
        return if (previous != null && previous != key) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
    }

    suspend fun downloadAndOffer(update: AvailableAppUpdate, nodeId: String, requestedByUser: Boolean): Boolean {
        if (!update.sha256.matches(SHA256_HEX)) {
            _state.value = State.Failed("missing-checksum")
            return false
        }
        val cached = withContext(Dispatchers.IO) {
            ready?.takeIf { it.second.versionName == update.version && it.second.sha256.equals(update.sha256, true) && it.first.isFile }
        }
        val file = cached?.first ?: catchingNonCancel {
            installer.download(update) { progress ->
                _state.value = State.Downloading(update.version, progress.progress.takeIf { it in 0f..1f })
            }
        }.getOrElse {
            _state.value = State.Failed(it.message ?: "download")
            return false
        }
        // A fresh download is a temporary: once copied beside the offer it is not kept twice.
        offer(
            file,
            update.version,
            update.sha256,
            requestedByUser,
            nodeId,
            temporary = cached == null,
            changelog = update.changelog,
            // Where the watch can fetch the same file on its own Wi-Fi; this copy stays for Bluetooth.
            downloadUrl = update.downloadUrl,
        )
        val success = ready != null && _state.value !is State.Failed
        if (success) prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
        return success
    }

    /** Development builds: send an APK picked on the phone. */
    fun pushFile(uri: Uri) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val target = File(context.cacheDir, "watch-pushed.apk")
                catchingNonCancel {
                    // A null stream used to leave the previous pick in the cache file, to be
                    // offered to the watch as if it were this one.
                    val source = context.contentResolver.openInputStream(uri) ?: error("cannot read the file")
                    source.use { input -> target.outputStream().use { input.copyTo(it) } }
                    target
                }.onFailure {
                    Log.w(TAG, "picked APK not read: ${it.javaClass.simpleName}")
                    target.delete()
                }.getOrNull()
            }
            if (file == null) {
                _state.value = State.Failed("cannot-read")
                return@launch
            }
            val version = withContext(Dispatchers.IO) { archiveVersion(file) } ?: run {
                file.delete()
                _state.value = State.Failed("not-an-apk")
                return@launch
            }
            offer(file, version, sha256 = "", requestedByUser = true, temporary = true)
        }
    }

    /**
     * Checks [file] and offers it to the watch. [temporary] says [file] is a scratch copy (a
     * download, a picked file) that is of no use once the offer has its own copy: it is deleted
     * however this ends, where it used to sit in the cache for good.
     */
    private suspend fun offer(
        file: File,
        version: String,
        sha256: String,
        requestedByUser: Boolean,
        nodeId: String? = _watch.value?.nodeId,
        temporary: Boolean = false,
        changelog: String = "",
        downloadUrl: String = "",
    ) {
        try {
            offerChecked(file, version, sha256, requestedByUser, nodeId, changelog, downloadUrl)
        } finally {
            if (temporary) withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    private suspend fun offerChecked(
        file: File,
        version: String,
        sha256: String,
        requestedByUser: Boolean,
        nodeId: String?,
        changelog: String,
        downloadUrl: String,
    ) {
        val node = nodeId ?: run {
            _state.value = State.Failed("no-watch")
            return
        }
        // The file is hashed here once, and only once: against the manifest's checksum when there
        // is one (a download), which is what can catch a corrupt file, and handed to the
        // validation below so it does not read the APK through again for the same number.
        val checksum = withContext(Dispatchers.IO) { sha256Of(file) }
        if (sha256.isNotBlank() && !sha256.equals(checksum, ignoreCase = true)) {
            _state.value = State.Failed("checksum")
            return
        }
        withContext(Dispatchers.IO) { rejectArchive(file, version, checksum, actualSha256 = checksum) }?.let { reason ->
            _state.value = State.Failed(reason)
            return
        }
        val offer = UpdateOffer(
            versionName = version,
            sizeBytes = file.length(),
            sha256 = checksum,
            requestedByUser = requestedByUser,
            channel = channel().word,
            // The watch has a small screen and a thin pipe: a few paragraphs at most. The phone
            // keeps the longer text for its own page, beside the offer.
            changelog = UpdateChangelog.truncate(changelog, UpdateChangelog.WATCH_MAX_CHARS),
            downloadUrl = downloadUrl,
        )
        val notes = UpdateChangelog.truncate(changelog, UpdateChangelog.WORK_MAX_CHARS)
        // Disk and a synchronous preferences commit: not on the main thread this runs on.
        val saved = withContext(Dispatchers.IO) {
            val target = File(context.filesDir, "watch-ready.apk")
            val kept = if (file.absolutePath != target.absolutePath) file.copyTo(target, overwrite = true) else target
            prefs.edit().putString(KEY_OFFER, WearCodec.json.encodeToString(UpdateOffer.serializer(), offer))
                .putString(KEY_OFFER_NODE, node).putLong(KEY_OFFER_AT, System.currentTimeMillis())
                .putString(KEY_OFFER_NOTES, notes).commit()
            kept
        }
        ready = saved to offer
        _state.value = State.Offered(version, notes)
        if (!link.awaitNearbyWatch(node)) _state.value = State.Failed("watch-not-nearby")
        else if (!link.send(node, WearPaths.UPDATE_OFFER, WearCodec.encode(UpdateOffer.serializer(), offer)))
            _state.value = State.Failed("offer-send-failed")
    }

    /** What the watch says about an offer or an install. */
    fun onStatus(nodeId: String, status: UpdateStatus) {
        if (nodeId != prefs.getString(KEY_OFFER_NODE, null) || ready?.second?.versionName != status.versionName) return
        when (status.phase) {
            // An accept that says it is waiting for playback is the watch telling the phone it has the
            // build already and is holding the install back: nothing to send, and sending it again
            // would be the same bytes over Bluetooth for nothing.
            UpdatePhase.ACCEPT -> if (status.reason == UpdateStatus.REASON_WAITING_PLAYBACK) {
                _state.value = State.WaitingForPlayback(status.versionName)
            } else if (status.reason == UpdateStatus.REASON_WIFI) {
                // The watch fetches it itself over its Wi-Fi: nothing to send. If that does not
                // work out it says so with a plain accept, and the branch below sends it.
                _state.value = State.Sending(status.versionName, progress = null, overWifi = true)
            } else {
                val work = OneTimeWorkRequestBuilder<WatchUpdateSendWorker>().setInputData(workDataOf("node" to nodeId)).build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "watch-update-send",
                    enqueuePolicy(KEY_SEND_JOB, "${status.versionName}@$nodeId"),
                    work,
                )
            }
            UpdatePhase.DECLINE -> {
                _state.value = when (status.reason) {
                    "already-current" -> State.UpToDate(status.versionName)
                    // Not a fault: the person turned automatic updates off on the watch, and an
                    // automatic push was told so. What to do about it is on the page, not a "failed".
                    REASON_AUTO_OFF -> State.AutoUpdateOff
                    else -> State.Failed(status.reason ?: "declined")
                }
            }
            UpdatePhase.RECEIVING -> if (_state.value !is State.Installing && _state.value !is State.AwaitingConfirmation) {
                if (status.reason == UpdateStatus.REASON_WIFI) {
                    _state.value = State.Sending(status.versionName, status.progress, overWifi = true)
                } else {
                    sendingProgress(status.versionName, status.progress)
                }
            }
            UpdatePhase.INSTALLING -> _state.value = State.Installing(status.versionName)
            UpdatePhase.AWAITING_CONFIRMATION -> _state.value = State.AwaitingConfirmation(status.versionName)
            UpdatePhase.INSTALLED -> {
                val notes = savedChangelog()
                rememberOutcome(status.versionName, notes)
                _state.value = State.Installed(status.versionName, notes)
                clearReady()
            }
            UpdatePhase.FAILED -> _state.value = State.Failed(status.reason ?: "install")
        }
    }

    suspend fun sendPrepared(nodeId: String): Boolean = sendLock.withLock {
        val (file, offer) = withContext(Dispatchers.IO) { ready } ?: return@withLock false
        if (!link.awaitNearbyWatch(nodeId)) {
            _state.value = State.Failed("watch-not-nearby")
            return@withLock false
        }
        withContext(Dispatchers.IO) { rejectArchive(file, offer.versionName, offer.sha256) }?.let {
            _state.value = State.Failed(it)
            return@withLock false
        }
        _state.value = State.Sending(offer.versionName)
        val channels = Wearable.getChannelClient(context)
        var channel: ChannelClient.Channel? = null
        val finished = CompletableDeferred<Int>()
        val callback = object : ChannelClient.ChannelCallback() {
            override fun onOutputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
                finished.complete(closeReason)
            }
        }
        try {
            // withTimeoutOrNull, not withTimeout: its TimeoutCancellationException is a
            // CancellationException, which the catch below rethrows, so a send that merely took too
            // long reached the worker as a cancellation and was never reported as a failure.
            val completed = withTimeoutOrNull(SEND_BUDGET_MS) {
                val opened = channels.openChannel(nodeId, WearPaths.UPDATE_APK).await()
                channel = opened
                channels.registerChannelCallback(opened, callback).await()
                val output = channels.getOutputStream(opened).await()
                withContext(Dispatchers.IO) {
                    WriteWatchdog(output, 30_000, 15 * 60_000L) { channels.close(opened) }.use { guarded ->
                        file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var sent = 0L
                            var lastPercent = -1
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                guarded.write(buffer, 0, count)
                                sent += count
                                val fraction = (sent.toFloat() / offer.sizeBytes).coerceIn(0f, 1f)
                                val percent = (fraction * 100).toInt()
                                if (percent != lastPercent) {
                                    sendingProgress(offer.versionName, fraction)
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                // sendFile's Task only starts a transfer. Wait for actual stream completion before closing the channel.
                check(finished.await() == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL) { "transfer-interrupted" }
                true
            }
            if (completed == null) {
                _state.value = State.Failed("send-timeout")
                false
            } else {
                true
            }
        } catch (cancelled: CancellationException) {
            _state.value = State.Failed("transfer-interrupted")
            throw cancelled
        } catch (error: Exception) {
            _state.value = State.Failed(error.message ?: "send")
            false
        } finally {
            withContext(NonCancellable) { channel?.let {
                catchingNonCancel { channels.unregisterChannelCallback(it, callback).await() }
                catchingNonCancel { channels.close(it).await() }
            } }
        }
    }

    private fun sendingProgress(version: String, fraction: Float) {
        _state.update { current ->
            if (current is State.Installing || current is State.AwaitingConfirmation || current is State.Installed || current is State.Failed) current
            else State.Sending(version, maxOf((current as? State.Sending)?.progress ?: 0f, fraction.coerceIn(0f, 1f)))
        }
    }

    /**
     * What the foreground notification of a send should say.
     *
     * The send worker may be started in a process that has just been born, whose state is still
     * [State.Idle] (or a stale "up to date"): the notification then read "Failed:". What is being
     * done is sending the offer that is ready, so that is what it says until the real progress
     * arrives.
     */
    internal suspend fun foregroundState(): State {
        val current = state.value
        if (current is State.Sending || current is State.Installing || current is State.AwaitingConfirmation ||
            current is State.Installed || current is State.Failed || current is State.Offered ||
            current is State.WaitingForPlayback
        ) return current
        val version = withContext(Dispatchers.IO) { ready?.second?.versionName }.orEmpty()
        return State.Sending(version)
    }

    internal fun onSendWorkerFinished() {
        scope.launch {
            kotlinx.coroutines.delay(1_500)
            WatchUpdateNotifications.show(context, state.value)
        }
    }

    private fun clearReady() {
        ready?.first?.delete()
        ready = null
        prefs.edit().remove(KEY_OFFER).remove(KEY_OFFER_AT).remove(KEY_OFFER_NODE).remove(KEY_OFFER_NOTES).apply()
    }

    /** The notes of the offer that is ready, in the longer form the phone keeps; blank if none. */
    private fun savedChangelog(): String = prefs.getString(KEY_OFFER_NOTES, null).orEmpty()

    /**
     * Writes down that the watch is at [version] now, for the page to say after a restart.
     *
     * [notes] are the release's; null keeps the ones already known for the same version (a later
     * "up to date" must not forget what the install brought).
     */
    private fun rememberOutcome(version: String, notes: String?) {
        val kept = if (prefs.getString(KEY_OUTCOME_VERSION, null) == version) prefs.getString(KEY_OUTCOME_NOTES, "").orEmpty() else ""
        prefs.edit().putString(KEY_OUTCOME_VERSION, version).putLong(KEY_OUTCOME_AT, System.currentTimeMillis())
            .putString(KEY_OUTCOME_NOTES, notes?.takeIf { it.isNotBlank() } ?: kept).apply()
    }

    /** The last check or install that had a result, or null if there has been none. */
    fun lastOutcome(): Outcome? {
        val version = prefs.getString(KEY_OUTCOME_VERSION, null) ?: return null
        return Outcome(version, prefs.getLong(KEY_OUTCOME_AT, 0), prefs.getString(KEY_OUTCOME_NOTES, "").orEmpty())
    }

    /**
     * Why this APK must not be offered, or null.
     *
     * [checksum] has no default on purpose: it used to be the file's own hash, which made the
     * check "the file equals itself" and added a second full read of the APK to every call. It is
     * the checksum the file is expected to have (the manifest's, or the offer's) or one the caller
     * computed once.
     */
    private fun rejectArchive(file: File, version: String, checksum: String, actualSha256: String? = null): String? =
        WatchApkValidation.reject(context, file, version, checksum, actualSha256)

    private fun restoreOffer(): Pair<File, UpdateOffer>? = runCatching {
        if (System.currentTimeMillis() - prefs.getLong(KEY_OFFER_AT, 0) > APK_RETENTION_MS) return null
        val file = File(context.filesDir, "watch-ready.apk").takeIf { it.isFile }
            ?: File(context.cacheDir, "watch-ready.apk").takeIf { it.isFile } ?: return null
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
        private const val KEY_PUSH_JOB = "push_job"
        private const val KEY_PUSH_USER = "push_job_user"
        private const val KEY_OFFER_NOTES = "offer_notes"
        private const val KEY_OUTCOME_VERSION = "outcome_version"
        private const val KEY_OUTCOME_AT = "outcome_at"
        private const val KEY_OUTCOME_NOTES = "outcome_notes"

        /** The watch's word for "automatic updates are off": see [State.AutoUpdateOff]. */
        const val REASON_AUTO_OFF = "auto-update-off"
        private const val KEY_SEND_JOB = "send_job"

        /** The whole of an APK over Bluetooth, from the channel opening to the watch having it. */
        private const val SEND_BUDGET_MS = 15 * 60_000L

        /** A SHA-256 as the manifest and the offer spell it. */
        internal val SHA256_HEX = WatchApkValidation.SHA256_HEX
        private const val APK_RETENTION_MS = 7 * 24 * 60 * 60_000L
        private const val CHECK_EVERY_MS = 6 * 60 * 60_000L

        /** The watch build's manifest, beside the phone's own (see docs/pampa-store-release.md). */
        const val WEAR_MANIFEST_URL =
            "https://raw.githubusercontent.com/Casual76/Fluidify/master/manifest-wear.json"
    }
}
