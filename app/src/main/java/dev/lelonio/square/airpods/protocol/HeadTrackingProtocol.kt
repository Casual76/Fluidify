// SPDX-License-Identifier: GPL-3.0-or-later
// Payload pairing from LibrePods contributors (2025), AACPManager.kt:665-779, pinned 89356c08.
package dev.lelonio.square.airpods.protocol
import dev.lelonio.square.airpods.AacpCodec

data class HeadPacketPair(val format: HeadPacketFormat, val start: List<Int>, val stop: List<Int>)
object HeadTrackingProtocol {
    const val preferenceKey = "use_alternate_head_tracking_packets"
    val defaultPreferenceFormat = HeadPacketFormat.ALTERNATE // local preference only, never RX support
    val pairs: List<HeadPacketPair> = HeadPacketFormat.entries.map {
        HeadPacketPair(it,AacpCodec.headTracking(it,true).map { b -> b.toInt() and 255 },
            AacpCodec.headTracking(it,false).map { b -> b.toInt() and 255 })
    }
}
