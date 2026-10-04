package dev.pampa.fluidify.wear.standalone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.playback.PlaybackMode

/**
 * "Headphones connected: listen here?"
 *
 * When a pair of headphones connects to the watch and the watch is not already
 * playing, a quiet notification offers to play through them: the phone's music
 * moved over if the phone is playing, the last thing the watch played if not.
 * One tap, and only when it is useful; nothing starts by itself.
 */
class HeadphonesReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as WearApp
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (!app.standalone.prefs.headphonePrompt) return
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                if (!isAudio(context, device)) return
                if (app.playback.mode.value == PlaybackMode.WATCH && app.local.isPlaying) return
                HeadphonesPrompt.show(context, name(context, device))
            }
            HeadphonesPrompt.ACTION_LISTEN -> {
                HeadphonesPrompt.dismiss(context)
                val headphones = app.standalone.router.outputs.value.firstOrNull { it.kind == LocalOutput.Kind.HEADPHONES }
                val phonePlaying = app.remote.nowPlaying.value.snapshot?.let { it.isPlaying || it.playWhenReady } == true
                // The phone's music follows if there is some; if not, the last thing the watch
                // played starts, with no handoff to pull anything over it.
                app.playback.moveToWatch(headphones, fromPhone = true, handoff = phonePlaying)
                if (!phonePlaying) app.local.resumeLast()
                context.startActivity(dev.pampa.fluidify.wear.system.PlayerIntents.showPlayer(context))
            }
        }
    }

    private fun isAudio(context: Context, device: BluetoothDevice): Boolean {
        if (!allowed(context)) return false
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ?: return false
        return major == BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    private fun name(context: Context, device: BluetoothDevice): String =
        if (allowed(context)) runCatching { device.name }.getOrNull().orEmpty() else ""

    private fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

object HeadphonesPrompt {
    const val ACTION_LISTEN = "dev.pampa.fluidify.wear.HEADPHONES_LISTEN"
    private const val CHANNEL = "headphones"
    private const val ID = 8

    /** Gone by itself after a couple of minutes: a prompt that waits all day is clutter. */
    private const val TIMEOUT_MS = 2 * 60_000L

    fun show(context: Context, name: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val system = context.getSystemService(NotificationManager::class.java)
        if (system.getNotificationChannel(CHANNEL) == null) {
            system.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.headphones_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val listen = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, HeadphonesReceiver::class.java).setAction(ACTION_LISTEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.headphones_connected))
            .setContentText(if (name.isNotBlank()) context.getString(R.string.headphones_listen_on, name) else context.getString(R.string.headphones_listen))
            .setContentIntent(listen)
            .addAction(R.drawable.ic_tile_play, context.getString(R.string.headphones_listen), listen)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }
}
