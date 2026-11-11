package dev.lelonio.square.playback

import dev.lelonio.square.data.DownloadStore
import java.util.UUID

/**
 * One native player's source identity, independent of renewable credential receipts.
 *
 * Each publication still goes through DownloadStore's current-account check. Revocation
 * publishes null; a renewed receipt for the same account can then publish again without
 * changing the native player owner. The native namespace/root fence remains unchanged,
 * so this identity cannot move an existing player to another account's downloads.
 */
internal class NativeDownloadSourceOwner(private val downloads: DownloadStore) {
    private val id = UUID.randomUUID().toString()

    // Called inside the native lifecycle owner. A Flow emission may have waited for
    // that owner while authentication renewed: re-capture, never reuse its old Access.
    fun binding(): String = downloads.captureAccess()?.let {
        downloads.nativeBindingJson(it, sourceOwner = id)
    } ?: "null"
}
