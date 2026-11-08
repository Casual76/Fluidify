// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

/** Controller authorization only. A null reason is not a capability or hardware receipt. */
enum class TrackingBlockReason {
    NO_ADMITTED_SESSION,
    HEAD_GESTURES_DISABLED,
    AUTOMATIONS_DISABLED,
    BACKGROUND_DISABLED,
}
