// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.*
import android.companion.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.PropertyValue
import kotlinx.coroutines.*

/** Not exported. No intent extra grants acquisition; only the process gate reservation does. */
class AirPodsControlService : Service() {
    private var attachedStopAction: String? = null
    private var attachedOwner: AirPodsAndroidController? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val owner = attachedOwner ?: AirPodsServiceOwnerRegistry.resolve()
        if (intent?.action?.startsWith(ACTION_STOP) == true) {
            if (intent.action != owner?.serviceStopAction) return START_NOT_STICKY
            if (owner != null) CoroutineScope(Dispatchers.IO).launch { owner.stopControls() }
            stopSelf(); return START_NOT_STICKY
        }
        if (owner?.hasAdmission() != true) { stopSelf(); return START_NOT_STICKY }
        attachedOwner = owner
        attachedStopAction = owner.serviceStopAction
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.airpods_feature_title), NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 0, Intent(this, AirPodsControlService::class.java).setAction(owner.serviceStopAction), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_airpods_service).setContentTitle(getString(R.string.airpods_feature_title))
                .setContentText(getString(if (owner.isFunctionalTest) R.string.airpods_functional_test_notification else R.string.airpods_feature_stop)).setOngoing(true)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.airpods_feature_stop), stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION, notification)
            CoroutineScope(Dispatchers.IO).launch { if (owner?.attach(this@AirPodsControlService) != true) finishFromOwner() }
        } catch (_: Exception) {
            if (owner != null) CoroutineScope(Dispatchers.IO).launch { owner.stopControls(dev.lelonio.square.airpods.runtime.storage.SuppressReason.START_FAILED) }
            finishFromOwner()
        }
        return START_NOT_STICKY
    }
    internal fun finishFromOwner() { Handler(Looper.getMainLooper()).post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
    override fun onDestroy() {
        attachedOwner?.let { owner -> owner.serviceDestroyed(this); AirPodsServiceOwnerRegistry.serviceEnded(owner, attachedStopAction) }
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "airpods_controls"
        private const val NOTIFICATION = 7421
        private const val ACTION_STOP = "dev.lelonio.square.airpods.STOP."
    }
}

/** Platform-bound trigger only; protected by BIND_COMPANION_DEVICE_SERVICE in manifest. */
@Suppress("DEPRECATION")
class AirPodsCompanionService : CompanionDeviceService() {
    override fun onDeviceAppeared(address: String) { AirPodsAndroidRuntime.get()?.presence(address, null) }
    override fun onDeviceDisappeared(address: String) { AirPodsAndroidRuntime.get()?.presence(address, null) }
    override fun onDeviceAppeared(associationInfo: AssociationInfo) { AirPodsAndroidRuntime.get()?.presence(associationInfo.deviceMacAddress?.toString(), associationInfo.id) }
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) { AirPodsAndroidRuntime.get()?.presence(associationInfo.deviceMacAddress?.toString(), associationInfo.id) }
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) { AirPodsAndroidRuntime.get()?.presence(null, event.associationId) }
}

/** Listening is inert. Click only writes on an existing admitted session, never clears suppress. */
class AirPodsQuickSettingsTile : TileService() {
    private val availability = TileAvailabilityObservation(
        resolve = { AirPodsAndroidRuntime.get()?.let(TileAvailabilitySource::from) },
        present = { available ->
            val tile = qsTile
            tile?.apply {
                state = if (available) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
                if (Build.VERSION.SDK_INT >= 29) subtitle = getString(if (state == Tile.STATE_ACTIVE) R.string.airpods_tile_cycle else R.string.airpods_tile_unavailable)
                label = getString(R.string.airpods_feature_title); updateTile()
            }
            tile != null
        },
    )
    override fun onStartListening() { availability.start() }
    override fun onStopListening() {
        availability.stop()
        super.onStopListening()
    }
    override fun onDestroy() {
        availability.destroy()
        super.onDestroy()
    }
    override fun onClick() {
        val owner = AirPodsAndroidRuntime.get() ?: return
        owner.cycleTile()
        availability.refresh()
    }
}
