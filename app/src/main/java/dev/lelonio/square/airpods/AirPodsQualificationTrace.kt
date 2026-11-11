// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

/** Typed, optional observation. No packet, address, device name, notification or exception text.
 * Callbacks must be bounded/nonblocking; failure to record cannot affect the protocol. */
enum class QualificationStep { OWNER, CONSTRUCT, CONNECT, BOOTSTRAP, SUBSCRIBE, TX, RX_SDU, RX,
    CLOSE, AUDIO, PRESENCE, MEDIA_OBSERVE, MEDIA_REQUEST, MEDIA_RESULT, MEDIA_CLOSE }
enum class QualificationPhase { REQUESTED, COMPLETED, REJECTED, FAILED, OBSERVED, UNCONFIRMED }
data class QualificationEvent(
    val step: QualificationStep,
    val phase: QualificationPhase,
    val generation: Long = 0,
    val sequence: Long = 0,
    val property: CommandProperty? = null,
    val value: Int? = null,
    val token: Long? = null,
    val flags: Int = 0,
    val values: List<Int> = emptyList(),
)
fun interface AirPodsQualificationObserver {
    fun record(event: QualificationEvent)
    companion object { val NONE = AirPodsQualificationObserver {} }
}
internal fun AirPodsQualificationObserver.observe(event: QualificationEvent) {
    runCatching { record(event) } // Logging failure is never RX, ACK or authorization.
}
