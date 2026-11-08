// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.playback

import java.util.UUID

/** Native occurrence identity, independent of Media3's position-based repeat inference. */
class PlaybackOccurrence {
    var id: String? = null
        private set
    var restored: Boolean = false
        private set
    private var key: Pair<Int, String>? = null
    private var nativeRequest: String? = null
    private var selectionAwaitingRequest = false
    private data class OwnedRestore(val nonce: String, val token: String, val uri: String,
        val valid: () -> Boolean, var dispatched: Boolean = false, var request: String? = null)
    private var ownedRestore: OwnedRestore? = null
    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    fun notifyListeners() { listeners.toList().forEach { it() } }

    fun select(index: Int, uri: String, restoredId: String? = null, notify: Boolean = true) {
        ownedRestore = null
        key = index to uri
        id = restoredId ?: UUID.randomUUID().toString()
        restored = restoredId != null
        selectionAwaitingRequest = restoredId == null
        if (notify) notifyListeners()
    }

    /** Called only by the saved-queue restore path, with a live lease/continuity validator. */
    fun beginOwnedRestore(index: Int, uri: String, token: String?, valid: () -> Boolean) {
        select(index, uri, notify = false)
        if (token != null && valid()) {
            id = null; restored = false; selectionAwaitingRequest = false
            ownedRestore = OwnedRestore(UUID.randomUUID().toString(), token, uri, valid)
        }
    }

    /** One command receives the nonce; retries/fallbacks carry that command's same nonce natively. */
    fun ownedRestoreNonceForLoad(): String? {
        val ticket = ownedRestore ?: return null
        if (!ticket.valid()) { ownedRestore = null; return null }
        if (ticket.dispatched) return null
        ticket.dispatched = true
        return ticket.nonce
    }

    fun nativeRestoreBound(nonce: String, request: String, remote: Boolean) {
        val ticket = ownedRestore ?: return
        if (remote || !ticket.valid()) { ownedRestore = null; return }
        if (ticket.dispatched && ticket.nonce == nonce && ticket.request == null) ticket.request = request
    }

    /** Loading may preload the next track. Only playing or an explicit selection starts it. */
    fun nativeEvent(type: String, uri: String, index: Int, remote: Boolean, notify: Boolean = true) {
        if (remote) ownedRestore = null
        if (type == "playback_request") {
            val ticket = ownedRestore
            if (ticket != null && ticket.request == uri && ticket.valid() && !remote) {
                ownedRestore = null
                nativeRequest = uri
                key = index to ticket.uri; id = ticket.token; restored = true
                selectionAwaitingRequest = false
                if (notify) notifyListeners()
                return
            }
            if (ticket != null && !ticket.valid()) ownedRestore = null
            if (uri == nativeRequest) return // repeated notification of the same load
            nativeRequest = uri
            if (!selectionAwaitingRequest || restored || remote) {
                // Fresh native loads cannot claim saved continuity from a matching URI.
                id = null; restored = false; key = null
                if (notify) notifyListeners()
            }
            selectionAwaitingRequest = false
            return
        }
        if (remote) {
            if (id != null) { id = null; restored = false; key = null; selectionAwaitingRequest = false; if (notify) notifyListeners() }
            return
        }
        if (type == "end_of_track" && (uri.isEmpty() || key?.second == uri)) {
            if (id != null) { id = null; restored = false; key = null; selectionAwaitingRequest = false; if (notify) notifyListeners() }
        } else if (type == "playing" && uri.isNotEmpty() && (id == null || key?.second != uri)) {
            key = index to uri; id = UUID.randomUUID().toString(); restored = false
            selectionAwaitingRequest = false
            if (notify) notifyListeners()
        }
    }
}
