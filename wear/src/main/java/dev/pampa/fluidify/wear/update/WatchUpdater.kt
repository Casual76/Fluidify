package dev.pampa.fluidify.wear.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.antigravity.fluidengine.foundation.compareVersions
import dev.lelonio.square.update.WatchApkValidation
import dev.pampa.fluidify.wear.BuildConfig
import dev.pampa.fluidify.wear.protocol.UpdateOffer
import dev.pampa.fluidify.wear.protocol.UpdatePhase
import dev.pampa.fluidify.wear.protocol.UpdateStatus
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File

/** APK and install state survive screen-off, process death and failed installations. */
class WatchUpdater(private val context: Context) {
    private val prefs = context.getSharedPreferences("watch_update", Context.MODE_PRIVATE)
    private val messages by lazy { Wearable.getMessageClient(context) }
    private val channels by lazy { Wearable.getChannelClient(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val directory = File(context.filesDir, "watch-update").apply { mkdirs() }
    private val target = File(directory, "incoming.apk")
    private val ready = File(directory, "ready.apk")
    private val _status = MutableStateFlow(restoreStatus())
    val status: StateFlow<UpdateStatus?> = _status.asStateFlow()
    private var progress: Job? = null
    private var expiry: Job? = null
    init { scheduleExpiry() }

    var autoUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO, value).apply() }

    val canRetry: Boolean get() = ready.isFile && prefs.contains(KEY_READY_VERSION)

    suspend fun onOffer(fromNode: String, offer: UpdateOffer) = lock.withLock {
        val order = compareVersions(offer.versionName, BuildConfig.VERSION_NAME)
        val decline = when {
            !offer.sha256.isSha256() -> "missing-checksum"
            offer.sizeBytes <= 0 -> "invalid-size"
            offer.requestedByUser && order < 0 -> "older"
            !offer.requestedByUser && order <= 0 -> "already-current"
            !offer.requestedByUser && !autoUpdate -> "auto-update-off"
            else -> null
        }
        if (decline != null) {
            reply(fromNode, UpdateStatus(UpdatePhase.DECLINE, offer.versionName, reason = decline))
            return@withLock
        }
        val current = _status.value
        if (current?.phase in listOf(UpdatePhase.RECEIVING, UpdatePhase.INSTALLING, UpdatePhase.AWAITING_CONFIRMATION)) {
            if (current?.versionName == offer.versionName) {
                prefs.edit().putString(KEY_NODE, fromNode).commit()
                if (current.phase == UpdatePhase.AWAITING_CONFIRMATION) {
                    WatchUpdateNotifications.show(context, current,
                        WatchInstallConfirmationActivity.find(context, prefs.getInt(KEY_SESSION, -1)), canRetry)
                }
                reply(fromNode, current)
            } else reply(fromNode, UpdateStatus(UpdatePhase.DECLINE, offer.versionName, reason = "busy"))
            return@withLock
        }
        prefs.edit().putString(KEY_NODE, fromNode).commit()
        // A full transfer can outlive this process before onInputClosed finishes checking it.
        if (target.isFile && prefs.getString(KEY_VERSION, null) == offer.versionName &&
            target.length() == offer.sizeBytes && WatchApkValidation.reject(context, target, offer.versionName, offer.sha256) == null) {
            target.copyTo(ready, overwrite = true)
            target.delete()
            prefs.edit().putString(KEY_READY_VERSION, offer.versionName).putString(KEY_READY_SHA, offer.sha256).remove(KEY_VERSION).commit()
            expiry?.cancel()
        }
        if (ready.isFile && prefs.getString(KEY_READY_VERSION, null) == offer.versionName &&
            prefs.getString(KEY_READY_SHA, null).equals(offer.sha256, ignoreCase = true) &&
            WatchApkValidation.reject(context, ready, offer.versionName, offer.sha256) == null) {
            // Tell the phone to wait for installation, not send the same bytes again.
            installReady()
            return@withLock
        }
        prefs.edit().putString(KEY_VERSION, offer.versionName).putString(KEY_SHA, offer.sha256)
            .putLong(KEY_AT, System.currentTimeMillis()).putLong(KEY_SIZE, offer.sizeBytes).commit()
        target.delete()
        scheduleExpiry()
        report(UpdateStatus(UpdatePhase.ACCEPT, offer.versionName))
    }

    suspend fun onChannelOpened(channel: ChannelClient.Channel) = lock.withLock {
        val version = acceptedVersion(channel.nodeId) ?: run { channels.close(channel).await(); return@withLock }
        report(UpdateStatus(UpdatePhase.RECEIVING, version))
        try {
            progress?.cancel()
            progress = scope.launch {
                val throttle = ProgressThrottle()
                var lastBytes = -1L
                var lastByteAt = android.os.SystemClock.elapsedRealtime()
                val startedAt = lastByteAt
                while (isActive) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val bytes = target.length()
                    if (bytes != lastBytes) {
                        lastBytes = bytes
                        lastByteAt = now
                    }
                    if (now - lastByteAt > STALL_MS || now - startedAt > TRANSFER_DEADLINE_MS) {
                        fail(version, "transfer-timeout")
                        runCatching { channels.close(channel).await() }
                        break
                    }
                    val fraction = (bytes.toFloat() / prefs.getLong(KEY_SIZE, 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                    // Each report is a write to the disk, a notification and a message over Bluetooth:
                    // not one for every percent.
                    if (throttle.accept((fraction * 100).toInt(), now)) report(UpdateStatus(UpdatePhase.RECEIVING, version, fraction))
                    delay(PROGRESS_POLL_MS)
                }
            }
            channels.receiveFile(channel, Uri.fromFile(target), false).await()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { progress?.cancel(); fail(version, "receive: ${error.message}") }
    }

    suspend fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int) = lock.withLock {
        progress?.cancelAndJoin()
        progress = null
        try {
            val version = acceptedVersion(channel.nodeId) ?: return@withLock
            if (closeReason != ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL || target.length() != prefs.getLong(KEY_SIZE, -1)) {
                fail(version, "transfer-interrupted")
                return@withLock
            }
            val sha = prefs.getString(KEY_SHA, "").orEmpty()
            WatchApkValidation.reject(context, target, version, sha)?.let { fail(version, it); return@withLock }
            target.copyTo(ready, overwrite = true)
            target.delete()
            prefs.edit().putString(KEY_READY_VERSION, version).putString(KEY_READY_SHA, sha).remove(KEY_VERSION).commit()
            expiry?.cancel()
            installReady()
        } finally {
            withContext(NonCancellable) { runCatching { channels.close(channel).await() } }
        }
    }

    /** Shared by Bluetooth updates and the charging-only standalone updater. */
    suspend fun installDownloaded(file: File, version: String, sha: String): Boolean = lock.withLock {
        WatchApkValidation.reject(context, file, version, sha)?.let { fail(version, it); return@withLock false }
        file.copyTo(ready, overwrite = true)
        prefs.edit().putString(KEY_READY_VERSION, version).putString(KEY_READY_SHA, sha).commit()
        installReady()
        _status.value?.phase != UpdatePhase.FAILED
    }

    /** A visible tap may resume Android's pending confirmation; otherwise retry the cached APK. */
    fun confirmOrRetry() {
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val session = prefs.getInt(KEY_SESSION, -1)
        if (_status.value?.phase == UpdatePhase.AWAITING_CONFIRMATION && session >= 0) {
            val pending = WatchInstallConfirmationActivity.find(context, session)
            if (pending != null && runCatching { pending.send(); true }.getOrDefault(false)) return
        }
        scope.launch { lock.withLock { installReady() } }
    }

    private suspend fun installReady() {
        val version = prefs.getString(KEY_READY_VERSION, null) ?: return
        val sha = prefs.getString(KEY_READY_SHA, "").orEmpty()
        WatchApkValidation.reject(context, ready, version, sha)?.let { fail(version, it); return }
        if (!context.packageManager.canRequestPackageInstalls()) {
            fail(version, "install-permission")
            return
        }
        val manager = context.packageManager.packageInstaller
        val old = prefs.getInt(KEY_SESSION, -1)
        if (old >= 0) runCatching { manager.abandonSession(old) }
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                setSize(ready.length())
            }
            val id = manager.createSession(params)
            prefs.edit().putInt(KEY_SESSION, id).commit()
            report(UpdateStatus(UpdatePhase.INSTALLING, version))
            manager.openSession(id).use { session ->
                ready.inputStream().use { input -> session.openWrite("base.apk", 0, ready.length()).use { output ->
                    input.copyTo(output); session.fsync(output)
                } }
                val callback = Intent(context, WatchInstallResultReceiver::class.java).putExtra("session", id)
                val pending = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail(version, error.message ?: "install") }
    }

    suspend fun onInstallResult(intent: Intent) = lock.withLock {
        val id = intent.getIntExtra("session", -1)
        if (id != prefs.getInt(KEY_SESSION, -2)) return@withLock
        val version = prefs.getString(KEY_READY_VERSION, null) ?: return@withLock
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation == null) { fail(version, "confirmation-unavailable"); return@withLock }
                val pending = WatchInstallConfirmationActivity.create(context, id, confirmation)
                report(UpdateStatus(UpdatePhase.AWAITING_CONFIRMATION, version))
                WatchUpdateNotifications.show(context, _status.value!!, pending)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                report(UpdateStatus(UpdatePhase.INSTALLED, version))
                ready.delete()
                prefs.edit().remove(KEY_READY_VERSION).remove(KEY_READY_SHA).remove(KEY_SESSION).commit()
            }
            else -> fail(version, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install")
        }
    }

    private suspend fun fail(version: String, reason: String) {
        prefs.edit().remove(KEY_VERSION).commit()
        report(UpdateStatus(UpdatePhase.FAILED, version, reason = reason))
    }

    private fun acceptedVersion(node: String): String? = prefs.getString(KEY_VERSION, null)?.takeIf {
        node == prefs.getString(KEY_NODE, null) && System.currentTimeMillis() - prefs.getLong(KEY_AT, 0) < OFFER_TTL_MS
    }

    private fun restoreStatus(): UpdateStatus? = runCatching {
        prefs.getString(KEY_STATUS, null)?.let { WearCodec.json.decodeFromString(UpdateStatus.serializer(), it) }
    }.getOrNull()?.let { status ->
        when {
            compareVersions(BuildConfig.VERSION_NAME, status.versionName) > 0 -> null
            status.phase == UpdatePhase.RECEIVING || status.phase == UpdatePhase.ACCEPT ->
                status.copy(phase = UpdatePhase.FAILED, reason = "transfer-interrupted")
            status.phase == UpdatePhase.INSTALLING && context.packageManager.packageInstaller.getSessionInfo(prefs.getInt(KEY_SESSION, -1))?.isCommitted != true ->
                status.copy(phase = if (BuildConfig.VERSION_NAME == status.versionName) UpdatePhase.INSTALLED else UpdatePhase.FAILED, reason = "install-interrupted")
            else -> status
        }
    }

    private fun scheduleExpiry() {
        expiry?.cancel()
        val version = prefs.getString(KEY_VERSION, null) ?: return
        expiry = scope.launch {
            delay((OFFER_TTL_MS - (System.currentTimeMillis() - prefs.getLong(KEY_AT, 0))).coerceAtLeast(0))
            lock.withLock { if (prefs.getString(KEY_VERSION, null) == version) fail(version, "offer-expired") }
        }
    }

    private suspend fun report(status: UpdateStatus) {
        _status.value = status
        prefs.edit().putString(KEY_STATUS, WearCodec.json.encodeToString(UpdateStatus.serializer(), status)).apply()
        WatchUpdateNotifications.show(context, status, canRetry = canRetry)
        prefs.getString(KEY_NODE, null)?.let { reply(it, status) }
    }
    fun onPhoneHello(node: String) {
        if (node != prefs.getString(KEY_NODE, null)) return
        _status.value?.let { status -> scope.launch { reply(node, status) } }
    }
    private suspend fun reply(node: String, status: UpdateStatus) {
        withTimeoutOrNull(REPLY_WAIT_MS) {
            runCatching { messages.sendMessage(node, WearPaths.UPDATE_STATUS, WearCodec.encode(UpdateStatus.serializer(), status)).await() }
        }
    }
    companion object {
        private const val KEY_AUTO = "auto"
        private const val KEY_VERSION = "version"
        private const val KEY_SHA = "sha256"
        private const val KEY_NODE = "node"
        private const val KEY_AT = "offered_at"
        private const val KEY_SIZE = "size"
        private const val KEY_STATUS = "status"
        private const val KEY_READY_VERSION = "ready_version"
        private const val KEY_READY_SHA = "ready_sha"
        private const val KEY_SESSION = "session"
        private const val OFFER_TTL_MS = 30 * 60_000L

        /** A transfer that moves no bytes for this long is dead. */
        private const val STALL_MS = 30_000L

        /** The longest a transfer may take, however steadily it goes. */
        private const val TRANSFER_DEADLINE_MS = 15 * 60_000L
        private const val PROGRESS_POLL_MS = 1_000L

        /** How long the phone is given to take a status message. */
        private const val REPLY_WAIT_MS = 5_000L
    }
}
