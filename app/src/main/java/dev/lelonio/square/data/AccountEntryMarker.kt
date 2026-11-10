// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.data

import android.util.Log
import dev.lelonio.square.BuildConfig

/** Fixed diagnostic vocabulary only: no account IDs, credentials, exceptions or caller text. */
internal enum class AccountEntryMarker {
    SERVICE_CONNECTED,
    SERVICE_USERNAME_PENDING,
    SERVICE_USERNAME_FAILED,
    SERVICE_LIFECYCLE_CHANGED,
    SERVICE_USERNAME_EMPTY,
    SERVICE_SIGNED_OUT,
    SERVICE_HISTORY_CHANGED,
    SERVICE_DOWNLOAD_JOIN_PENDING,
    SERVICE_WEAR_JOIN_PENDING,
    SERVICE_VERIFY_PENDING,
    VERIFY_SNAPSHOT_PENDING,
    VERIFY_HISTORY_MISSING,
    VERIFY_EPOCH_MISSING,
    VERIFY_KEPT_PROOF_MISSING,
    VERIFY_NATIVE_NOT_READY,
    VERIFY_NATIVE_ID_PENDING,
    VERIFY_PROOF_STALE,
    VERIFY_NATIVE_LOST,
    VERIFY_HISTORY_STALE,
    VERIFY_EPOCH_STALE,
    VERIFY_IDENTITY_MISMATCH,
    VERIFY_CONFIRM_REJECTED,
    VERIFY_EXISTING_ACCEPTED,
    VERIFY_NEW_ACCEPTED,
    AUTHORITY_BINDING_INVALID,
    AUTHORITY_EXPLICIT_INVALIDATE,
    AUTHORITY_INVALIDATE_BEFORE_CHANGE,
    VM_OBSERVER_NONE,
    VM_OBSERVER_LOCAL,
    VM_OBSERVER_ONLINE,
    VM_REFRESH_NONE,
    VM_REFRESH_LOCAL,
    VM_REFRESH_ONLINE,
    VM_OFFLINE_READ_PENDING,
    VM_COLLECTOR_NONE,
    VM_COLLECTOR_LOCAL,
    VM_COLLECTOR_ONLINE,
}

/** Observes existing decisions only. No retry, getters, validation, grants or account writes. */
internal fun accountEntryMarker(marker: AccountEntryMarker) {
    if (BuildConfig.DEBUG) runCatching { Log.d("FluidifyAccountEntry", marker.name) }
}
