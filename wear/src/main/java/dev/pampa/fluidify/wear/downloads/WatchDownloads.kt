package dev.pampa.fluidify.wear.downloads

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dev.pampa.fluidify.wear.protocol.DownloadRequest
import dev.pampa.fluidify.wear.protocol.WatchDownloads as WatchDownloadsStatus
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import dev.pampa.fluidify.wear.protocol.logic.TransferPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Downloads on the watch: what to keep, how to fetch it, and what the phone is told.
 *
 * Edited from either side (the watch's playlist screen, the phone's menu) and
 * worked through by [WatchDownloadWorker]. The phone reads the result from the
 * DataItem this keeps current ([publish]), which is also how it shows progress.
 */
class WatchDownloads internal constructor(
    private val context: Context,
    val authority: WatchDownloadAuthority = WatchDownloadAuthority.forContext(context),
    private val sessions: dev.pampa.fluidify.wear.link.PhoneAccountSession = dev.pampa.fluidify.wear.link.PhoneAccountSession.forContext(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val enqueue: ((String, String?) -> Unit)? = null,
    private val publishTransport: (suspend (WatchDownloadsStatus) -> Unit)? = null,
) {

    val store = WatchDownloadStore(context, authority)
    private val prefs = context.getSharedPreferences("watch_downloads", Context.MODE_PRIVATE)
    private var lastPhoneNamespace = sessions.capture()?.namespace

    /**
     * Where a screen runs [keep] and [drop]: off the main thread (they write and delete files), and
     * one at a time in the order they were asked, so a switch flipped twice ends where it was left.
     */
    val edits: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "download-edits").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    @Volatile private var measuredAt = 0L
    @Volatile private var usedBytes = 0L
    @Volatile private var freeBytes = 0L
    @Volatile private var measuredNamespace: String? = null

    private val _status = MutableStateFlow(snapshot())
    val status: StateFlow<WatchDownloadsStatus> = _status.asStateFlow()
    init {
        authority.observe { admitted ->
            measuredNamespace = null
            changed(schedule = false)
            scope.launch { measureStorage(); if (admitted != null) schedule(); publish(final = true) }
        }
        scope.launch { sessions.scope.collect { onPhoneAccount(); changed(schedule = false); publish(final = true) } }
        scope.launch { measureStorage() }
    }
    @Synchronized fun onPhoneAccount(explicit: Boolean = false) {
        val phone = sessions.capture()
        val local = authority.capture()
        val changed = lastPhoneNamespace != null && phone != null && lastPhoneNamespace != phone.namespace
        lastPhoneNamespace = phone?.namespace
        if (changed || (phone == null && explicit) || (local != null && phone != null && phone.namespace != local.namespace)) authority.revoke()
    }
    fun capture() = authority.capture()
    private fun setting(key: String) = authority.capture()?.let { "$key/${it.namespace}" } ?: "$key/unavailable"

    /** The watch's own quality, 160 unless set: the phone's default, and what a watch speaker can tell apart. */
    var qualityKbps: Int
        get() = prefs.getInt(setting(KEY_QUALITY), DEFAULT_KBPS)
        set(value) { authority.capture()?.let { authority.use(it) { prefs.edit { putInt(setting(KEY_QUALITY), value) } } } }

    var preference: TransferPreference
        get() = prefs.getString(setting(KEY_PREFERENCE), null)?.let { runCatching { TransferPreference.valueOf(it) }.getOrNull() }
            ?: TransferPreference.BLUETOOTH_FIRST
        set(value) { authority.capture()?.let { authority.use(it) { prefs.edit { putString(setting(KEY_PREFERENCE), value.name) } } } }

    fun keep(access: WatchDownloadAuthority.Access, uri: String, title: String, artUrl: String? = null) {
        try {
            store.forAccount(access).keep(uri, title, artUrl)
            changed(schedule = true, expected = access)
        } catch (failed: java.io.IOException) { changed(schedule = false, expected = access) }
        catch (failed: IllegalStateException) { changed(schedule = false, expected = access) }
    }
    fun drop(access: WatchDownloadAuthority.Access, uri: String) {
        try { store.forAccount(access).drop(uri) }
        catch (failed: java.io.IOException) { Log.w(TAG, "download plan not persisted", failed) }
        catch (failed: IllegalStateException) { Log.w(TAG, "download plan not persisted", failed) }
        changed(schedule = false, expected = access)
    }
    fun claimLegacy(access: WatchDownloadAuthority.Access) {
        try {
            store.claimLegacy(access)
            changed(schedule = false, expected = access)
        } catch (failed: Exception) {
            if (failed !is kotlinx.coroutines.CancellationException)
                changed(schedule = false, paused = PAUSED_STORAGE, expected = access)
        }
    }
    /** Decode admission is separate from the watch's own local identity. */
    fun onRequest(request: DownloadRequest): Boolean {
        val phone = sessions.capture() ?: return false
        if (request.accountSession != phone.session || request.accountNamespace != phone.namespace) return false
        try { store.phoneEdit(sessions, phone) { files ->
            val receiptKey = "receipts/${phone.namespace}"
            val receipts = prefs.getStringSet(receiptKey, emptySet()).orEmpty()
            if (request.requestId != null && request.requestId in receipts) return@phoneEdit
            request.qualityKbps?.takeIf { it in ALLOWED_KBPS }?.let { value ->
                check(prefs.edit().putInt("$KEY_QUALITY/${phone.namespace}", value).commit())
            }
            request.preference?.let { value ->
                check(prefs.edit().putString("$KEY_PREFERENCE/${phone.namespace}", value.name).commit())
            }
            request.owner?.let { owner ->
                if (request.keep) files.keep(owner, request.title, request.artUrl) else files.drop(owner)
            }
            request.requestId?.let { id -> check(prefs.edit().putStringSet(receiptKey, receipts + id).commit()) }
        } } catch (failed: Exception) {
            if (failed !is kotlinx.coroutines.CancellationException) Log.w(TAG, "download plan not persisted", failed)
            changed(schedule = false)
            return false
        }
        changed(schedule = false)
        schedule(phone)
        return true
    }

    /** Starts (or nudges) the queue. Bluetooth needs no network, so none is required. */
    fun schedule(phonePlan: dev.pampa.fluidify.wear.link.PhoneAccountSession.Scope? = null) {
        val local = authority.capture()?.takeIf { phonePlan == null || it.namespace == phonePlan.namespace }
        val phone = phonePlan ?: sessions.capture()?.takeIf { it.namespace == local?.namespace }
        if (local == null && phonePlan == null) return
        val namespace = local?.namespace ?: phone?.namespace ?: return
        enqueue?.let { it(namespace, phone?.session); return }
        val work = OneTimeWorkRequestBuilder<WatchDownloadWorker>()
            .setInputData(androidx.work.workDataOf("account" to namespace, "phone-session" to phone?.session,
                "local-lineage" to local?.lineage))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // A queue that cannot be scheduled now is scheduled by the next change or the nightly
        // sync; it is never a reason for a screen to fail.
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK/$namespace/${local?.lineage ?: "phone"}/${phone?.session ?: "offline"}", ExistingWorkPolicy.KEEP, work) }
            .onFailure { Log.w(TAG, "download queue not scheduled: ${it.message}") }
    }

    fun changed(schedule: Boolean, active: String? = null, paused: String? = null, expected: WatchDownloadAuthority.Access? = null) {
        if (expected != null) {
            if (!authority.isCurrent(expected)) return
            authority.use(expected) { _status.value = snapshot(active, paused) }
        } else _status.value = snapshot(active, paused)
        if (schedule) schedule()
    }

    private var lastPublishAt = 0L
    private var trailingPublish: Job? = null

    /**
     * Tells the phone what the watch has. Not urgent: a screen of progress, not a command.
     *
     * Each one is a write to the Data Layer, and the queue asks at every track: unless it is
     * [final], at most one goes out every [PUBLISH_EVERY_MS], and the newest state is sent once
     * that time has passed, so the phone never ends up showing an old one. The last state of a
     * pass, and what the phone asked for, are final.
     */
    suspend fun publish(final: Boolean = false) {
        if (!final) {
            val wait = synchronized(this) { lastPublishAt + PUBLISH_EVERY_MS - SystemClock.elapsedRealtime() }
            if (wait > 0) {
                scheduleTrailingPublish(wait)
                return
            }
        }
        // What was waiting to go is going now, with something newer.
        synchronized(this) { trailingPublish?.cancel() }
        send()
    }

    private suspend fun send() {
        synchronized(this) { lastPublishAt = SystemClock.elapsedRealtime() }
        withContext(Dispatchers.IO) { measureStorage() }
        val phone = sessions.capture() ?: return
        val local = authority.capture() ?: return
        if (local.namespace != phone.namespace) return
        val value = authority.use(local) {
            val previous = _status.value.takeIf { it.accountNamespace == local.namespace }
            snapshot(previous?.active, previous?.paused).copy(accountSession = phone.session, accountNamespace = local.namespace)
        }
        if (!sessions.isCurrent(phone) || !authority.isCurrent(local)) return
        val bytes = WearCodec.encode(WatchDownloadsStatus.serializer(), value)
        publishTransport?.let { it(value); return }
        runCatching {
            Wearable.getDataClient(context).putDataItem(PutDataRequest.create(WearPaths.DOWNLOAD_STATUS).setData(bytes)).await()
        }.onFailure { Log.i(TAG, "status not published: ${it.message}") }
    }

    /** One publish after [waitMs], whatever the number of asks in between. */
    @Synchronized private fun scheduleTrailingPublish(waitMs: Long) {
        if (trailingPublish?.isActive == true) return
        trailingPublish = scope.launch {
            delay(waitMs)
            send()
        }
    }

    private fun snapshot(active: String? = null, paused: String? = null): WatchDownloadsStatus {
        val account = authority.capture() ?: return WatchDownloadsStatus()
        return authority.use(account) { WatchDownloadsStatus(
        owners = store.forAccount(account).ownerStatus(),
        bytesUsed = usedBytes.takeIf { measuredNamespace == account.namespace } ?: 0L,
        bytesFree = freeBytes.takeIf { measuredNamespace == account.namespace } ?: 0L,
        waiting = store.forAccount(account).pending().size,
        active = active,
        // A kept list the disk would not take says "storage", like a disk that is nearly full.
        paused = paused ?: PAUSED_STORAGE.takeIf { store.forAccount(account).saveFailed.value },
        qualityKbps = qualityKbps,
        preference = preference,
        updatedAtEpochMs = System.currentTimeMillis(),
        accountNamespace = account.namespace,
        unavailable = store.forAccount(account).unavailableCount(),
    ) }
    }

    @Synchronized private fun measureStorage() {
        val now = System.currentTimeMillis()
        val account = authority.capture() ?: return
        authority.use(account) {
            usedBytes = store.forAccount(account).bytesUsed()
            freeBytes = store.forAccount(account).bytesFree()
            measuredNamespace = account.namespace
        }
        measuredAt = now
        if (authority.isCurrent(account)) authority.use(account) {
            _status.update { it.copy(bytesUsed = usedBytes, bytesFree = freeBytes) }
        }
    }

    companion object {
        private const val TAG = "WatchDownloads"
        const val WORK = "watch-downloads"
        /** The least time between two statuses sent to the phone while a queue works. */
        private const val PUBLISH_EVERY_MS = 1_000L
        private const val KEY_QUALITY = "quality_kbps"
        private const val KEY_PREFERENCE = "preference"
        const val DEFAULT_KBPS = 160

        /** The qualities a download can have, lowest first: the phone's own three choices. */
        val QUALITY_STEPS = listOf(96, 160, 320)
        val ALLOWED_KBPS = QUALITY_STEPS.toSet()

        /** Below this much free space the queue waits: the watch needs room for everything else. */
        const val RESERVE_BYTES = 1L shl 30

        const val PAUSED_STORAGE = "storage"
        const val PAUSED_OFFLINE = "offline"
    }
}
