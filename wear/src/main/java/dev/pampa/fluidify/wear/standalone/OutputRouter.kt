package dev.pampa.fluidify.wear.standalone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A place on the watch the sound can come out of. */
data class LocalOutput(
    val id: Int,
    val name: String,
    val kind: Kind,
) {
    enum class Kind { SPEAKER, HEADPHONES }
}

/**
 * The watch's own outputs: its speaker and whatever headphones are connected.
 *
 * Kept current with the system's device callbacks, so the output list shows a
 * pair of earbuds the moment they connect. A choice is applied to the engine's
 * AudioTrack as its preferred device; headphones win over the speaker by
 * default, which is also what the system does.
 */
class OutputRouter(private val context: Context, private val prefs: StandalonePrefs) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val _outputs = MutableStateFlow(read())

    val outputs: StateFlow<List<LocalOutput>> = _outputs.asStateFlow()

    /** What the listener picked in the output list, if anything. */
    val chosen = MutableStateFlow<LocalOutput?>(null)

    /**
     * The picked output while it is still connected; otherwise headphones if any
     * are, otherwise the speaker if the listener allows it.
     */
    val best: LocalOutput?
        get() = chosen.value?.takeIf { it in _outputs.value }
            ?: _outputs.value.firstOrNull { it.kind == LocalOutput.Kind.HEADPHONES }
            ?: _outputs.value.firstOrNull { it.kind == LocalOutput.Kind.SPEAKER && prefs.speakerAllowed }

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            _outputs.value = read()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            _outputs.value = read()
        }
    }

    init {
        audio.registerAudioDeviceCallback(callback, null)
    }

    fun deviceFor(output: LocalOutput?): AudioDeviceInfo? =
        output?.let { wanted -> audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == wanted.id } }

    /**
     * Opens the system's own way of connecting headphones: Wear's output switcher
     * where there is one, its Bluetooth picker filtered to audio devices otherwise.
     * The same two intents Media3 uses on Wear for the same job.
     */
    fun openHeadphonePicker(): Boolean {
        val switcher = Intent(ACTION_OUTPUT_SWITCHER)
            .putExtra(EXTRA_SWITCHER_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val bluetooth = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .putExtra(EXTRA_CLOSE_ON_CONNECT, true)
            .putExtra(EXTRA_CONNECTION_ONLY, true)
            .putExtra(EXTRA_FILTER_TYPE, FILTER_TYPE_AUDIO)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in listOf(switcher, bluetooth)) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    private fun read(): List<LocalOutput> =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { device ->
            val kind = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> LocalOutput.Kind.SPEAKER
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                -> LocalOutput.Kind.HEADPHONES
                else -> return@mapNotNull null
            }
            LocalOutput(device.id, device.productName?.toString().orEmpty(), kind)
        }.distinctBy { it.kind to it.name }

    private companion object {
        const val ACTION_OUTPUT_SWITCHER = "com.android.settings.panel.action.MEDIA_OUTPUT"
        const val EXTRA_SWITCHER_PACKAGE = "com.android.settings.panel.extra.PACKAGE_NAME"
        const val EXTRA_CLOSE_ON_CONNECT = "EXTRA_CLOSE_ON_CONNECT"
        const val EXTRA_CONNECTION_ONLY = "EXTRA_CONNECTION_ONLY"
        const val EXTRA_FILTER_TYPE = "android.bluetooth.devicepicker.extra.FILTER_TYPE"
        const val FILTER_TYPE_AUDIO = 1
    }
}
