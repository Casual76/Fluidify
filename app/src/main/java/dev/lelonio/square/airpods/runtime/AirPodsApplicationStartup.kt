package dev.lelonio.square.airpods.runtime

import android.content.Context
import dev.lelonio.square.airpods.runtime.android.AirPodsAndroidController
import dev.lelonio.square.airpods.runtime.android.AirPodsAndroidRuntime
import kotlinx.coroutines.flow.StateFlow

/** Installs the optional process host; it does not authorize a control session.
 * No physical standalone PASS is recorded for this integration yet. Keep this gate closed;
 * neither cache migration, setup verification nor an enabled preference replaces that PASS.
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
            conformance = { false },
            mediaFactory = mediaFactory,
        )
    }
}
