// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.content.Context

/** Non-debug dev has no debug owner, restore, data reader, consent factory or exported entry. */
object AirPodsQualificationBootstrap {
    fun install(context: Context) = Unit
    internal fun presence(address: String?, associationId: Int?) = Unit
}
