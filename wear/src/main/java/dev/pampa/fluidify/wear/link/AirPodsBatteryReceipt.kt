package dev.pampa.fluidify.wear.link

import dev.pampa.fluidify.wear.protocol.AIRPODS_BATTERY_LIFETIME_MS
import dev.pampa.fluidify.wear.protocol.AccountState
import dev.pampa.fluidify.wear.protocol.AirPodsBatteryLevel
import dev.pampa.fluidify.wear.protocol.AirPodsBatterySnapshot
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.PlaybackSource
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory evidence only: serialization/restart must never restore this authority. */
class AirPodsBatteryLease internal constructor() {
    private val mutable = MutableStateFlow(true)
    val current: StateFlow<Boolean> = mutable.asStateFlow()
    internal val valid: Boolean get() = mutable.value
    internal fun revoke() { mutable.value = false }
}

data class AirPodsBatteryReceipt(
    val battery: AirPodsBatterySnapshot,
    val receivedAtEpochMs: Long,
    val receivedAtElapsedMs: Long,
    val leftExpiresAtElapsedMs: Long?,
    val rightExpiresAtElapsedMs: Long?,
    val caseExpiresAtElapsedMs: Long?,
    val lease: AirPodsBatteryLease,
) {
    fun isCurrent(wallMs: Long, elapsedMs: Long): Boolean = lease.valid &&
        elapsedMs >= receivedAtElapsedMs &&
        abs((wallMs - receivedAtEpochMs) - (elapsedMs - receivedAtElapsedMs)) <= AIRPODS_CLOCK_STEP_MS
}

/** No requests, timers, storage or clock learning from a persisted DataItem. */
class AirPodsBatteryReceiver {
    private var clockOffset: Long? = null
    private var clockWall = 0L
    private var clockElapsed = 0L
    private var minimumSnapshotTime = Long.MAX_VALUE
    private data class Contact(val node: String, val wallMs: Long, val elapsedMs: Long)
    private var pendingContact: Contact? = null
    private var contact: Contact? = null
    private var offsetLowerBound: Long? = null
    private var account: AccountState? = null
    private val retiredSessions = mutableSetOf<String>()
    private var retirementOverflow = false
    private var source: String? = null
    private var previousBattery: AirPodsBatterySnapshot? = null
    private var lease = AirPodsBatteryLease()

    @Synchronized fun observeClock(remoteMs: Long, wallMs: Long, elapsedMs: Long) {
        if (remoteMs <= 0 || wallMs <= 0 || elapsedMs < 0) return
        val next = wallMs - remoteMs
        val old = clockOffset
        val stepped = old != null && (abs(next - old) > AIRPODS_CLOCK_STEP_MS ||
                elapsedMs < clockElapsed ||
                abs((wallMs - clockWall) - (elapsedMs - clockElapsed)) > AIRPODS_CLOCK_STEP_MS)
        if (stepped) {
            invalidate()
            contact = null
            offsetLowerBound = null
        }
        clockOffset = next
        clockWall = wallMs
        clockElapsed = elapsedMs
    }

    /** Piggybacks the existing hello; it never sends a battery query or starts a connection. */
    @Synchronized fun beginContact(node: String, wallMs: Long, elapsedMs: Long) {
        pendingContact = Contact(node, wallMs, elapsedMs)
    }

    @Synchronized fun confirmContact(node: String, remoteMs: Long, wallMs: Long, elapsedMs: Long) {
        val pending = pendingContact ?: return
        if (node != pending.node) return
        pendingContact = null
        val roundTrip = elapsedMs - pending.elapsedMs
        if (remoteMs <= 0 || roundTrip !in 0..MAX_CONTACT_MS ||
            abs((wallMs - pending.wallMs) - roundTrip) > AIRPODS_CLOCK_STEP_MS) return
        // The phone created this hello after our existing request was sent. Charging the ENTIRE
        // round trip to telemetry gives an upper bound on its age, even with asymmetric latency.
        // A one-way offset estimated at receipt would silently forgive a delayed hello.
        offsetLowerBound = pending.wallMs - remoteMs
        contact = Contact(node, wallMs, elapsedMs)
        minimumSnapshotTime = remoteMs
    }

    /** Called only after the normal account consumer accepted this phone account message. */
    @Synchronized fun observeAccount(value: AccountState) {
        if (account?.localDataSession != value.localDataSession || !value.signedIn) {
            account?.localDataSession?.let {
                if (retiredSessions.size < 128) retiredSessions += it else retirementOverflow = true
            }
            invalidate()
        }
        account = value
    }

    @Synchronized fun accept(snapshot: PlaybackSnapshot, wallMs: Long, elapsedMs: Long): AirPodsBatteryReceipt? {
        val battery = snapshot.airPodsBattery
        val session = account
        val offset = offsetLowerBound
        val confirmedContact = contact
        if (retirementOverflow || battery == null || snapshot.source != PlaybackSource.PHONE || session == null || !session.signedIn ||
            session.localDataSession.isNullOrBlank() || battery.accountSession != session.localDataSession ||
            battery.accountSession in retiredSessions ||
            battery.sourceSession.isBlank() || offset == null || confirmedContact == null ||
            elapsedMs < confirmedContact.elapsedMs ||
            abs((wallMs - confirmedContact.wallMs) - (elapsedMs - confirmedContact.elapsedMs)) > AIRPODS_CLOCK_STEP_MS ||
            elapsedMs < clockElapsed ||
            abs((wallMs - clockWall) - (elapsedMs - clockElapsed)) > AIRPODS_CLOCK_STEP_MS) {
            invalidate()
            return null
        }
        val remoteNow = wallMs - offset
        // ACCOUNT may itself arrive from the Data Layer's cache. It cannot confirm a different
        // phone process, and a future/old snapshot cannot acquire a fresh monotonic receipt.
        if (session.changedAtEpochMs < minimumSnapshotTime ||
            session.changedAtEpochMs > remoteNow + AIRPODS_CLOCK_STEP_MS ||
            snapshot.sentAtEpochMs < minimumSnapshotTime || snapshot.sentAtEpochMs > remoteNow ||
            remoteNow - snapshot.sentAtEpochMs !in 0 until AIRPODS_BATTERY_LIFETIME_MS) {
            invalidate()
            return null
        }
        if (source != battery.sourceSession || previousBattery != battery) {
            invalidate()
            source = battery.sourceSession
            previousBattery = battery
        }
        fun expires(level: AirPodsBatteryLevel?): Long? {
            level ?: return null
            if (level.percent !in 0..100 || level.observedAtEpochMs <= 0 ||
                level.observedAtEpochMs > snapshot.sentAtEpochMs || level.observedAtEpochMs > remoteNow) return null
            val age = remoteNow - level.observedAtEpochMs
            // Cover tolerated clock movement at the phone RX anchor, during hello, and at the
            // watch's monotonic anchor. Transport latency is already charged by the RTT bound.
            val remaining = AIRPODS_BATTERY_LIFETIME_MS - age - 3 * AIRPODS_CLOCK_STEP_MS
            return (elapsedMs + remaining).takeIf { remaining > 0 && it >= elapsedMs }
        }
        val left = expires(battery.left)
        val right = expires(battery.right)
        val case = expires(battery.case)
        if (left == null && right == null && case == null) {
            invalidate()
            return null
        }
        return AirPodsBatteryReceipt(battery, wallMs, elapsedMs, left, right, case, lease)
    }

    private fun invalidate() {
        lease.revoke()
        lease = AirPodsBatteryLease()
        source = null
        previousBattery = null
    }

    private companion object { const val MAX_CONTACT_MS = 10_000L }
}

internal const val AIRPODS_CLOCK_STEP_MS = 2_000L
