package dev.lelonio.square.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lelonio.square.SquareApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The "Retry" button of the failed-update notification.
 *
 * A broadcast and not the settings screen: the point of the button is to try again without
 * opening anything. It does the same check-and-send as the "Update the watch" row.
 *
 * The phone's process may have been gone since the failure, in which case the coordinator is new
 * and has not heard the watch's hello, so it does not yet know which version to compare with:
 * the watch is greeted first and waited for. The work is held open with [goAsync], because a
 * receiver that returns ends its process's claim to be running, and a check that needs the
 * network would be cut off in the middle.
 */
class WatchUpdateRetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val bridge = (context.applicationContext as SquareApplication).wearBridge
        scope.launch {
            try {
                withTimeoutOrNull(BUDGET_MS) {
                    if (bridge.updates.watch.value == null) {
                        bridge.refreshWatchLink()
                        bridge.updates.watch.first { it != null }
                    }
                    bridge.updates.checkAndPushAndWait()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "dev.pampa.fluidify.WATCH_UPDATE_RETRY"

        /** A broadcast receiver is allowed about ten seconds; leave room to finish. */
        private const val BUDGET_MS = 9_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
