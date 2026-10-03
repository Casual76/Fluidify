package dev.pampa.fluidify.wear.standalone

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * What the watch is called in the account's device list.
 *
 * The name the person knows it by — the one in the watch's own "About" screen, "Galaxy Watch7" —
 * not the model code: the tests found the watch in Spotify's picker as "SM-L310". Taken from the
 * system's device name, then the Bluetooth name, then the model; the short serial a Galaxy Watch
 * puts in brackets after its name is left out, since nobody picks a speaker by its serial.
 */
object WatchName {

    fun of(context: Context): String {
        val candidates = sequence {
            yield(runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull())
            yield(
                runCatching {
                    context.getSystemService(BluetoothManager::class.java)?.adapter?.name
                }.getOrNull(),
            )
            yield(Build.MODEL)
        }
        return candidates.mapNotNull { it?.let(::clean) }.firstOrNull() ?: FALLBACK
    }

    /** "Galaxy Watch7 (L5LJ)" → "Galaxy Watch7"; blank → null. */
    fun clean(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return SERIAL_SUFFIX.replace(trimmed, "").trim().ifEmpty { trimmed }
    }

    private val SERIAL_SUFFIX = Regex("""\s*\([A-Za-z0-9]{2,6}\)$""")
    private const val FALLBACK = "Wear OS"
}
