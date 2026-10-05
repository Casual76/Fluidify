package dev.pampa.fluidify.wear.standalone

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
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
import dev.pampa.fluidify.wear.system.PlayerIntents

/**
 * "Headphones connected: listen here?"
 *
 * When a pair of headphones connects to the watch and the watch is not already
 * playing, a quiet notification offers to play through them: the phone's music
 * moved over if the phone is playing, the last thing the watch played if not.
 * One tap, and only when it is useful; nothing starts by itself.
 *
 * This receiver only hears the system's announcement, and so it is exported (the broadcast comes
 * from the Bluetooth process, which a receiver closed to other apps would not hear). Anything else
 * sent to it is ignored: the prompt's own button does not come here but opens the app, see
 * [HeadphonesPrompt.listen], so that no other app can start the music by sending it an intent.
 */
class HeadphonesReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        // Only the preference, not the whole of the watch's own player (WearApp.standalone):
        // a pair of headphones connecting must not be what builds the engine's parts.
        if (!StandalonePrefs(context).headphonePrompt) return
        val app = context.applicationContext as WearApp
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        if (!isAudio(context, device)) return
        if (app.playback.mode.value == PlaybackMode.WATCH && app.local.isPlaying) return
        HeadphonesPrompt.show(context, name(context, device))
    }

    // Both read the device only after [allowed] has said BLUETOOTH_CONNECT is granted; lint cannot
    // see through the helper.
    @SuppressLint("MissingPermission")
    private fun isAudio(context: Context, device: BluetoothDevice): Boolean {
        if (!allowed(context)) return false
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ?: return false
        return major == BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    @SuppressLint("MissingPermission")
    private fun name(context: Context, device: BluetoothDevice): String =
        if (allowed(context)) runCatching { device.name }.getOrNull().orEmpty() else ""

    private fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

object HeadphonesPrompt {
    private const val CHANNEL = "headphones_quiet"
    private const val OLD_CHANNEL = "headphones"
    private const val ID = 9
    private const val PREFS = "headphones_prompt"
    private const val KEY_TOKEN = "token"

    /** Gone by itself after a couple of minutes: a prompt that waits all day is clutter. */
    private const val TIMEOUT_MS = 2 * 60_000L

    fun show(context: Context, name: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val system = context.getSystemService(NotificationManager::class.java)
        if (system.getNotificationChannel(CHANNEL) == null) {
            // Quiet: a question on the wrist, not a sound and a buzz at the moment the headphones
            // connect. The channel from before had default importance, which an app cannot lower
            // afterwards, so this is a new one and the old one is removed.
            system.deleteNotificationChannel(OLD_CHANNEL)
            system.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.headphones_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        // The button opens the app, which does the moving (see [listen]): a notification action
        // that starts an activity from a receiver is the "trampoline" Android 12 no longer allows.
        // The token says the tap is this notification's own; see [claim].
        val token = java.util.UUID.randomUUID().toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply()
        val listen = PlayerIntents.listenHere(context, token)
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TOKEN).apply()
        NotificationManagerCompat.from(context).cancel(ID)
    }

    /**
     * Whether [token] is the one the prompt on screen was given, and, if it is, spent: the same
     * tap cannot be taken twice. The activity that opens on the button is open to every app, and
     * is sometimes shown again with the intent it was first started with (from the recents, after
     * the system let it go); neither of those may start the music.
     */
    fun claim(context: Context, token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_TOKEN, null) != token) return false
        prefs.edit().remove(KEY_TOKEN).apply()
        return true
    }

    /** What the prompt's button does: the music moves to the headphones. Main thread; see MainActivity. */
    fun listen(app: WearApp) {
        dismiss(app)
        app.standalone.router.refresh()
        val headphones = app.standalone.router.outputs.value.firstOrNull { it.kind == LocalOutput.Kind.HEADPHONES }
        val phonePlaying = app.remote.nowPlaying.value.snapshot?.let { it.isPlaying || it.playWhenReady } == true
        // The phone's music follows if there is some; if not, the last thing the watch
        // played starts, with no handoff to pull anything over it.
        app.playback.moveToWatch(headphones, fromPhone = true, handoff = phonePlaying)
        if (!phonePlaying) app.local.resumeLast()
    }
}
