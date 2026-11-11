// SPDX-License-Identifier: GPL-3.0-or-later
// Catalog ownership from LibrePods pinned 89356c08 and approved Fluidify F1 contracts.
package dev.lelonio.square.airpods.protocol

enum class FeatureOwner { WIRE, WIRE_AND_HOST, HOST }
data class FeatureSpec(val catalogId: String, val group: String, val owner: FeatureOwner, val entryPoint: String)
object AirPodsFeatures {
    // 19 controls + these 16 semantic features = 35 included IDs. STEM is internal dependency.
    val semantic = listOf(
        FeatureSpec("device.rename", "identity", FeatureOwner.WIRE, "rename(name)"),
        FeatureSpec("device.battery", "status", FeatureOwner.WIRE, "state.batteries/liveBattery"),
        FeatureSpec("device.information", "identity", FeatureOwner.WIRE, "requestInformation/state.identityReceived"),
        FeatureSpec("device.eq", "audio", FeatureOwner.WIRE, "setEq(value)"),
        FeatureSpec("host.volume", "volume", FeatureOwner.HOST, "client host adapter; no AACP command"),
        FeatureSpec("host.ear_detection", "automation", FeatureOwner.HOST, "client host adapter; no AACP command"),
        FeatureSpec("host.ca", "automation", FeatureOwner.HOST, "client host adapter; no AACP command"),
        FeatureSpec("host.ca_pause", "automation", FeatureOwner.HOST, "client host adapter; no AACP command"),
        FeatureSpec("host.ca_relative", "automation", FeatureOwner.HOST, "client host adapter; no AACP command"),
        FeatureSpec("host.ca_volume", "automation", FeatureOwner.HOST, "client host adapter; no AACP command"),
        FeatureSpec("host.head_gestures", "gestures", FeatureOwner.WIRE_AND_HOST, "setHeadTracking/state.head"),
        FeatureSpec("host.long_press", "gestures", FeatureOwner.WIRE_AND_HOST, "setProperty(STEM_CONFIG)/state.stem"),
        FeatureSpec("host.disconnect_unworn", "automation", FeatureOwner.HOST, "client host adapter; no AACP command"),
        FeatureSpec("device.disconnect", "connection", FeatureOwner.HOST, "client host adapter; no AACP command"),
        FeatureSpec("extra.qs", "extras", FeatureOwner.HOST, "client host adapter; no AACP command"),
        FeatureSpec("extra.diagnostics", "diagnostics", FeatureOwner.HOST, "client host adapter; no AACP command")
    )
    val includedIds: Set<String> = AirPodsProperties.controls.filterNot { it.internalDependency }
        .map { it.catalogId }.toSet() + semantic.map { it.catalogId }
}
