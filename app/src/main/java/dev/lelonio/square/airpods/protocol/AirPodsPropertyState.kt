// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.protocol

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.runtime.storage.AirPodsPairValues

/** Local send state, never a firmware ACK. Silence does not change SENT into failure. */
enum class DesiredStatus { QUEUED, WRITING, SENT, UNCERTAIN, CONFIRMED, CONTRADICTED, FAILED }

data class DesiredProperty(
    val requested: PropertyValue,
    val sequence: Long,
    val generation: Long,
    val status: DesiredStatus,
    val startedMs: Long,
    /** Latest SDU ingress ordinal at entry to the physical writer. */
    val writeAfterRx: Long? = null,
)

/** Restoring a saved choice preserves its display intent without creating a new write. */
internal fun AirPodsPairValues.restoredChoices(generation: Long) = choices.mapValues { (_, saved) ->
    DesiredProperty(saved.value, 0, generation, when (saved.status) {
        DesiredStatus.QUEUED, DesiredStatus.WRITING, DesiredStatus.SENT -> DesiredStatus.UNCERTAIN
        else -> saved.status
    }, 0)
}

enum class EffectiveSource { OBSERVED, DESIRED, SAVED_OBSERVATION, DEFAULT }
data class EffectiveProperty(val value: PropertyValue, val source: EffectiveSource)

/** Display fallbacks from pinned LibrePods' non-demo ViewModel and Settings screens.
 * They never enter properties/mode/conversation or the automation event stream.
 * Zero is a meaningful receive/display mode mask; writes still require two modes. */
object AirPodsDisplayDefaults {
    fun value(property: CommandProperty): PropertyValue? = when (property) {
        CommandProperty.NAME -> null
        CommandProperty.EQ -> PropertyValue.Eq(false, 50, 50, 50)
        else -> PropertyValue.Number(when (property) {
            CommandProperty.MODE -> 3
            CommandProperty.MIC_MODE, CommandProperty.DOUBLE_CLICK_INTERVAL,
            CommandProperty.CLICK_HOLD_INTERVAL, CommandProperty.LISTENING_MODE_CONFIGS,
            CommandProperty.STEM_CONFIG -> 0
            CommandProperty.CHIME_VOLUME -> 75
            CommandProperty.AUTO_ANC_STRENGTH -> 50
            CommandProperty.VOLUME_SWIPE_INTERVAL -> 1
            CommandProperty.CALL_MANAGEMENT_CONFIG -> 3
            CommandProperty.EAR_DETECTION_CONFIG, CommandProperty.AUTOMATIC_CONNECTION_CONFIG,
            CommandProperty.ALLOW_OFF_OPTION -> 1
            else -> 2
        })
    }
}

fun AirPodsState.propertyEffective(property: CommandProperty): EffectiveProperty? {
    desired[property]?.takeIf { it.status in setOf(DesiredStatus.QUEUED, DesiredStatus.WRITING,
        DesiredStatus.SENT, DesiredStatus.UNCERTAIN) }?.let {
        return EffectiveProperty(it.requested, EffectiveSource.DESIRED)
    }
    propertyObserved(property)?.let { return EffectiveProperty(it.value, EffectiveSource.OBSERVED) }
    cachedValues.observed[property]?.let { return EffectiveProperty(it.value, EffectiveSource.SAVED_OBSERVATION) }
    return AirPodsDisplayDefaults.value(property)?.let { EffectiveProperty(it, EffectiveSource.DEFAULT) }
}

/** Only a current-session request or RX can make another explicit request redundant. */
internal fun AirPodsState.requestedOrObserved(property: CommandProperty): PropertyValue? =
    desired[property]?.takeIf { it.generation == generation && it.sequence > 0 &&
        it.status in setOf(DesiredStatus.QUEUED, DesiredStatus.WRITING, DesiredStatus.SENT) }?.requested
        ?: propertyObserved(property)?.value

/** Only the graph subscribes to this source; the gesture detector keeps the ordered events. */
interface AirPodsHeadSampleSource {
    val headSamples: kotlinx.coroutines.flow.StateFlow<Received<HeadSample>?>
}
