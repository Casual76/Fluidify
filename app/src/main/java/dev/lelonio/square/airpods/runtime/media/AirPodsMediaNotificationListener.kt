// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import android.service.notification.NotificationListenerService
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

/** Only signals access/session observers. Never reads notifications, grants consent or starts owners. */
class AirPodsMediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); signals.forEach { it() } }
    override fun onListenerDisconnected() { signals.forEach { it() }; super.onListenerDisconnected() }

    companion object {
        private val signals = CopyOnWriteArraySet<() -> Unit>()
        internal fun observe(signal: () -> Unit): Closeable {
            signals.add(signal)
            return Closeable { signals.remove(signal) }
        }
    }
}
