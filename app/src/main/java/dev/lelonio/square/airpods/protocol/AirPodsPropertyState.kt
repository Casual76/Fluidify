// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.protocol

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.runtime.storage.AirPodsPairValues

/** Local send state, never a firmware ACK. SENT lasts [CONFIRMATION_WINDOW_MS] without a matching
 * RX, then becomes NOT_CONFIRMED: not a failure, only no longer shown as the current value.
 * Persisted by name; NOT_CONFIRMED is appended so existing ordinals never move. */
enum class DesiredStatus { QUEUED, WRITING, SENT, UNCERTAIN, CONFIRMED, CONTRADICTED, FAILED, NOT_CONFIRMED }

/** How long a SENT intent waits for its property RX before it stops winning effective. */
const val CONFIRMATION_WINDOW_MS = 5_000L

data class DesiredProperty(
    val requested: PropertyValue,
    val sequence: Long,
    val generation: Long,
    val status: DesiredStatus,
    val startedMs: Long,
    /** Latest SDU ingress ordinal at entry to the physical writer. */
    val writeAfterRx: Long? = null,
)

/** Restoring a saved choice preserves its record without creating a new write. A restored intent
 * is never QUEUED/WRITING/SENT and has sequence 0, so it can neither win [propertyEffective] over
 * this session's RX nor count as [requestedOrObserved]. */
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
        // No default can stand in for an enrolled byte that was never received.
        CommandProperty.NAME, CommandProperty.HEARING_AID -> null
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

/** An intent in flight (queued, writing, or sent within the last [CONFIRMATION_WINDOW_MS]) wins;
 * then this session's RX; then this pair's last saved RX. UNCERTAIN, NOT_CONFIRMED and restored
 * choices never win. With nothing received, a switch is unknown (null), never a table default;
 * numeric and slider defaults remain as a starting position only, marked [EffectiveSource.DEFAULT]. */
fun AirPodsState.propertyEffective(property: CommandProperty): EffectiveProperty? {
    desired[property]?.takeIf { it.status in setOf(DesiredStatus.QUEUED, DesiredStatus.WRITING,
        DesiredStatus.SENT) }?.let {
        return EffectiveProperty(it.requested.shownFor(property), EffectiveSource.DESIRED)
    }
    propertyObserved(property)?.let { return EffectiveProperty(it.value, EffectiveSource.OBSERVED) }
    cachedValues.observed[property]?.let { return EffectiveProperty(it.value.shownFor(property), EffectiveSource.SAVED_OBSERVATION) }
    if (AirPodsProperties.spec(property)?.encoding in setOf(ControlEncoding.BOOLEAN_1_2,
            ControlEncoding.HEARING_AID_PAIR)) return null
    return AirPodsDisplayDefaults.value(property)?.let { EffectiveProperty(it, EffectiveSource.DEFAULT) }
}

/** Base for a single-bit edit of the 0x1A cycle when nothing of this session says otherwise:
 * the mask in flight, else this session's RX, else this pair's saved RX, else 0 like pinned
 * AirPodsViewModel.kt:757. Never a write gate (FIX-07): only what the toggle builds on. */
fun AirPodsState.listeningModeMaskBase(): Int =
    (propertyEffective(CommandProperty.LISTENING_MODE_CONFIGS)?.value as? PropertyValue.Number)?.value ?: 0

/** The 0x2C enrolled byte, only as received: this generation's RX, else this pair's saved RX.
 * Null means unknown, and then no 0x2C may be written (nothing can stand in for it). */
fun AirPodsState.hearingAidEnrolled(): Int? {
    val received = properties[CommandProperty.HEARING_AID]?.takeIf { it.generation == generation }?.value
        ?: cachedValues.observed[CommandProperty.HEARING_AID]?.value
    return (received as? PropertyValue.Number)?.value?.let(HearingAidWire::enrolled)
}

/** The 0x2C value a write would carry. The caller only chooses on/off: 1/2, or a packed value
 * whose enrolled byte is the received one. The enrolled byte is always [hearingAidEnrolled].
 * Null: no enrolled byte received, a packed value carrying another one, or no such state. */
fun AirPodsState.hearingAidWireValue(requested: Int): Int? {
    val enrolled = hearingAidEnrolled() ?: return null
    return when {
        requested == 1 || requested == 2 -> HearingAidWire.pack(enrolled, requested == 1)
        AirPodsProperties.spec(CommandProperty.HEARING_AID)?.accepts(requested) == true &&
            HearingAidWire.enrolled(requested) == enrolled -> requested
        else -> null
    }
}

internal fun PropertyValue.shownFor(property: CommandProperty): PropertyValue =
    if (property == CommandProperty.HEARING_AID) HearingAidWire.shown(this) else this

/** Only a current-session request or RX can make another explicit request redundant. */
internal fun AirPodsState.requestedOrObserved(property: CommandProperty): PropertyValue? =
    desired[property]?.takeIf { it.generation == generation && it.sequence > 0 &&
        it.status in setOf(DesiredStatus.QUEUED, DesiredStatus.WRITING, DesiredStatus.SENT) }?.requested?.shownFor(property)
        ?: propertyObserved(property)?.value

/** Only the graph subscribes to this source; the gesture detector keeps the ordered events. */
interface AirPodsHeadSampleSource {
    val headSamples: kotlinx.coroutines.flow.StateFlow<Received<HeadSample>?>
}
