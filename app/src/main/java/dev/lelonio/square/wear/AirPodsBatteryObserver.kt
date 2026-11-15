package dev.lelonio.square.wear

import android.os.SystemClock
import dev.lelonio.square.airpods.runtime.android.AirPodsAndroidController
import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.pampa.fluidify.wear.protocol.AirPodsBatterySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Observes ONLY an already installed owner. No install/get-or-create, refresh, request or wake. */
internal class AirPodsBatteryObserver(
    private val scope: CoroutineScope,
    private val owner: AirPodsAndroidController?,
    private val accounts: AccountLocalDataCoordinator,
    private val projection: AirPodsBatteryProjection,
    onChanged: () -> Unit,
) : AutoCloseable {
    @Volatile private var closed = false
    private val accountObserver = accounts.observe { access ->
        projection.accountChanged(access?.requestSession, SystemClock.elapsedRealtime())
        scope.launch { if (!closed) onChanged() }
    }
    private val job: Job? = owner?.let { installed ->
        scope.launch {
            combine(
                installed.state.map { AirPodsBatteryObservation.from(it) }.distinctUntilChanged(),
                installed.runtime.map { it.active && !it.verifying }.distinctUntilChanged(),
                accounts.access.map { it?.requestSession }.distinctUntilChanged(),
            ) { _, _, _ -> snapshot() }
                .distinctUntilChanged()
                .collect { if (!closed) onChanged() }
        }
    }

    fun snapshot(): AirPodsBatterySnapshot? {
        if (closed) return null
        val installed = owner ?: return null
        return accounts.locked {
            val access = accounts.capture() ?: return@locked null
            projection.snapshot(
                AirPodsBatteryObservation.from(installed.state.value),
                installed.runtime.value.let { it.active && !it.verifying },
                access.requestSession, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
            )
        }
    }

    override fun close() {
        closed = true
        job?.cancel()
        accountObserver.close()
    }
}
