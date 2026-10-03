package dev.pampa.fluidify.wear.downloads

import android.content.Context
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
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Downloads on the watch: what to keep, how to fetch it, and what the phone is told.
 *
 * Edited from either side (the watch's playlist screen, the phone's menu) and
 * worked through by [WatchDownloadWorker]. The phone reads the result from the
 * DataItem this keeps current ([publish]), which is also how it shows progress.
 */
class WatchDownloads(private val context: Context) {

    val store = WatchDownloadStore(context)
    private val prefs = context.getSharedPreferences("watch_downloads", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(snapshot())
    val status: StateFlow<WatchDownloadsStatus> = _status.asStateFlow()

    /** The watch's own quality, 160 unless set: the phone's default, and what a watch speaker can tell apart. */
    var qualityKbps: Int
        get() = prefs.getInt(KEY_QUALITY, DEFAULT_KBPS)
        set(value) = prefs.edit { putInt(KEY_QUALITY, value) }

    var preference: TransferPreference
        get() = prefs.getString(KEY_PREFERENCE, null)?.let { runCatching { TransferPreference.valueOf(it) }.getOrNull() }
            ?: TransferPreference.WIFI_FIRST
        set(value) = prefs.edit { putString(KEY_PREFERENCE, value.name) }

    fun keep(uri: String, title: String, artUrl: String? = null) {
        store.keep(uri, title, artUrl)
        changed(schedule = true)
    }

    fun drop(uri: String) {
        store.drop(uri)
        changed(schedule = false)
    }

    /** What the phone asked for. */
    fun onRequest(request: DownloadRequest) {
        request.qualityKbps?.takeIf { it in ALLOWED_KBPS }?.let { qualityKbps = it }
        request.preference?.let { preference = it }
        val owner = request.owner
        when {
            owner == null -> changed(schedule = true)
            request.keep -> keep(owner, request.title, request.artUrl)
            else -> drop(owner)
        }
    }

    /** Starts (or nudges) the queue. Bluetooth needs no network, so none is required. */
    fun schedule() {
        val work = OneTimeWorkRequestBuilder<WatchDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // A queue that cannot be scheduled now is scheduled by the next change or the nightly
        // sync; it is never a reason for a screen to fail.
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, work) }
            .onFailure { Log.w(TAG, "download queue not scheduled: ${it.message}") }
    }

    fun changed(schedule: Boolean, active: String? = null, paused: String? = null) {
        _status.value = snapshot(active, paused)
        if (schedule) schedule()
    }

    /** Tells the phone what the watch has. Not urgent: a screen of progress, not a command. */
    suspend fun publish() {
        val bytes = WearCodec.encode(WatchDownloadsStatus.serializer(), _status.value)
        runCatching {
            Wearable.getDataClient(context).putDataItem(PutDataRequest.create(WearPaths.DOWNLOAD_STATUS).setData(bytes)).await()
        }.onFailure { Log.i(TAG, "status not published: ${it.message}") }
    }

    private fun snapshot(active: String? = null, paused: String? = null) = WatchDownloadsStatus(
        owners = store.ownerStatus(),
        bytesUsed = store.bytesUsed(),
        bytesFree = store.bytesFree(),
        waiting = store.pending().size,
        active = active,
        paused = paused,
        qualityKbps = qualityKbps,
        preference = preference,
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    companion object {
        private const val TAG = "WatchDownloads"
        const val WORK = "watch-downloads"
        private const val KEY_QUALITY = "quality_kbps"
        private const val KEY_PREFERENCE = "preference"
        const val DEFAULT_KBPS = 160
        val ALLOWED_KBPS = setOf(96, 160, 320)

        /** Below this much free space the queue waits: the watch needs room for everything else. */
        const val RESERVE_BYTES = 1L shl 30

        const val PAUSED_STORAGE = "storage"
        const val PAUSED_OFFLINE = "offline"
    }
}
