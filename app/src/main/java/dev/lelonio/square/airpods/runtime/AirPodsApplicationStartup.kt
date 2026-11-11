package dev.lelonio.square.airpods.runtime

import android.content.Context
import dev.lelonio.square.airpods.runtime.android.AirPodsAndroidController
import dev.lelonio.square.airpods.runtime.android.AirPodsAndroidRuntime
import kotlinx.coroutines.flow.StateFlow

/** Installs the normal product host. Availability is separate from authorization:
 * only explicit consent, granted permissions, a verified pair and current device facts
 * can admit a session. Installing this host never starts media or controls by itself.
 */
internal object AirPodsApplicationStartup {
    fun install(
        context: Context,
        visibility: StateFlow<Boolean>,
        mediaFactory: () -> AirPodsMediaPort,
    ): Result<AirPodsAndroidController> = runCatching {
        AirPodsAndroidRuntime.install(
            context,
            visibility,
            conformance = { true },
            mediaFactory = mediaFactory,
        )
    }
}
