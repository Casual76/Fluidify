package dev.pampa.fluidify.wear.update

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.pampa.fluidify.wear.BuildConfig
import dev.pampa.fluidify.wear.protocol.UpdateOffer
import dev.pampa.fluidify.wear.protocol.UpdatePhase
import dev.pampa.fluidify.wear.protocol.UpdateStatus
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import dev.antigravity.fluidengine.foundation.compareVersions
import java.io.File

/**
 * Updates this watch app with an APK the phone sends over Bluetooth.
 *
 * Three steps, each over a different part of the Data Layer:
 *  1. the phone offers a version ([WearPaths.UPDATE_OFFER]); this side says yes or no;
 *  2. the phone opens a channel ([WearPaths.UPDATE_APK]) and sends the file, which
 *     Play Services writes straight to disk without this process having to stay up;
 *  3. when the file is complete, it is checked (checksum, package, version) and
 *     handed to `PackageInstaller` through the engine's installer, the same code
 *     the phone uses to update itself.
 *
 * The installer of record is this app itself when it was installed with
 * `tools/install-wear.ps1` (or the phone's installer), and then Android installs
 * the update without asking; otherwise the watch shows one confirmation.
 */
class WatchUpdater(private val context: Context) {

    private val prefs = context.getSharedPreferences("watch_update", Context.MODE_PRIVATE)
    private val messages by lazy { Wearable.getMessageClient(context) }
    private val channels by lazy { Wearable.getChannelClient(context) }

    private val _status = MutableStateFlow<UpdateStatus?>(null)

    /** What the update is doing, for the "Altro" page. Null when nothing is happening. */
    val status: StateFlow<UpdateStatus?> = _status.asStateFlow()

    /** Automatic pushes from the phone are accepted; on by default. */
    var autoUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    private val target: File get() = File(context.cacheDir, "watch-update.apk")

    suspend fun onOffer(fromNode: String, offer: UpdateOffer) {
        val order = compareVersions(offer.versionName, BuildConfig.VERSION_NAME)
        val decline = when {
            // Asked for by hand (a build pushed from the phone while developing): the same
            // version is fine, an older one is not. Automatic pushes only ever go forward.
            offer.requestedByUser && order < 0 -> "older"
            !offer.requestedByUser && order <= 0 -> "already-current"
            !offer.requestedByUser && !autoUpdate -> "auto-update-off"
            else -> null
        }
        if (decline != null) {
            reply(fromNode, UpdateStatus(UpdatePhase.DECLINE, offer.versionName, reason = decline))
            return
        }
        prefs.edit()
            .putString(KEY_VERSION, offer.versionName)
            .putString(KEY_SHA, offer.sha256)
            .putString(KEY_NODE, fromNode)
            .apply()
        target.delete()
        report(fromNode, UpdateStatus(UpdatePhase.ACCEPT, offer.versionName))
    }

    /** The phone opened the APK channel: let Play Services write it to disk. */
    suspend fun onChannelOpened(channel: ChannelClient.Channel) {
        val version = prefs.getString(KEY_VERSION, null) ?: return
        report(channel.nodeId, UpdateStatus(UpdatePhase.RECEIVING, version))
        runCatching { channels.receiveFile(channel, Uri.fromFile(target), false).await() }
            .onFailure { fail(channel.nodeId, version, "receive: ${it.message}") }
    }

    /** The file has fully arrived (or the transfer broke). */
    suspend fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int) {
        val version = prefs.getString(KEY_VERSION, null) ?: return
        val node = prefs.getString(KEY_NODE, channel.nodeId) ?: channel.nodeId
        if (closeReason != ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL || !target.isFile) {
            fail(node, version, "transfer interrupted ($closeReason)")
            return
        }
        install(node, target, version, prefs.getString(KEY_SHA, "").orEmpty())
    }

    private suspend fun install(node: String, file: File, version: String, sha256: String) {
        report(node, UpdateStatus(UpdatePhase.INSTALLING, version))
        val installer = AndroidAppUpdateInstaller(context, EngineHttp(userAgent = "Fluidify-Wear/${BuildConfig.VERSION_NAME}"))
        // Follow the install until Android takes over (a confirmation, or the
        // package manager replacing this very process); the result receiver keeps
        // working without us.
        withTimeoutOrNull(INSTALL_FOLLOW_MS) {
            installer.installFile(file = file, expectedVersionName = version, sha256 = sha256)
                .takeWhile { state ->
                    when (state) {
                        is AppUpdateInstallState.AwaitingUserAction -> {
                            report(node, UpdateStatus(UpdatePhase.AWAITING_CONFIRMATION, version))
                            true
                        }
                        is AppUpdateInstallState.Installed -> {
                            report(node, UpdateStatus(UpdatePhase.INSTALLED, version))
                            false
                        }
                        is AppUpdateInstallState.Error -> {
                            fail(node, version, state.message)
                            false
                        }
                        else -> true
                    }
                }
                .collect { }
        }
    }

    private suspend fun fail(node: String, version: String, reason: String) {
        Log.w(TAG, "update to $version failed: $reason")
        report(node, UpdateStatus(UpdatePhase.FAILED, version, reason = reason))
    }

    private suspend fun report(node: String, status: UpdateStatus) {
        _status.value = status.takeUnless { it.phase == UpdatePhase.INSTALLED || it.phase == UpdatePhase.DECLINE }
        reply(node, status)
    }

    private suspend fun reply(node: String, status: UpdateStatus) {
        runCatching {
            messages.sendMessage(node, WearPaths.UPDATE_STATUS, WearCodec.encode(UpdateStatus.serializer(), status)).await()
        }.onFailure { Log.i(TAG, "status not delivered: ${it.message}") }
    }

    private companion object {
        const val TAG = "WatchUpdater"
        const val KEY_AUTO = "auto"
        const val KEY_VERSION = "version"
        const val KEY_SHA = "sha256"
        const val KEY_NODE = "node"
        const val INSTALL_FOLLOW_MS = 20_000L
    }
}
