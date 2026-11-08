// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.lifecycle

import dev.lelonio.square.airpods.runtime.storage.PairAddress
import dev.lelonio.square.airpods.runtime.storage.PendingCompanionCleanup

/** identity is historical metadata only; all authority comparisons use sameEndpoint. */
data class OwnedAssociation(val id: Int?, val address: PairAddress, val packageName: String, val identity: String? = null)
/** Public endpoint in this caller's own package/user scope, never an incarnation token. */
fun OwnedAssociation.sameEndpoint(other: OwnedAssociation) =
    id == other.id && address == other.address && packageName == other.packageName

enum class ObservationKind { ADDRESS_31, ASSOCIATION_ADDRESS_33, REQUEST_ID_36 }
data class ObservationRegistration(val kind: ObservationKind, val association: OwnedAssociation, val callbackGeneration: Long)
enum class CompanionFailure { FEATURE_ABSENT, NO_CONSENT, PERMISSION_DENIED, NOT_BONDED, ASSOCIATION_MISMATCH, REGISTRATION_DENIED, PLATFORM_ERROR, STALE_CALLBACK, WRONG_DEVICE, NOT_CONNECTED, STOP_FAILED, DISASSOCIATE_FAILED }
sealed class CompanionResult {
    data object LegacyManual : CompanionResult()
    data class Registered(val registration: ObservationRegistration) : CompanionResult()
    data class Trigger(val address: PairAddress, val consentGeneration: Long) : CompanionResult()
    data object Stopped : CompanionResult()
    data class Denied(val reason: CompanionFailure) : CompanionResult()
}
data class CompanionAdmission(val address: PairAddress, val consentGeneration: Long, val consent: Boolean)
/** Implementations query current platform state on EVERY admission. Never cached package
 * associations from another app. Confirmation means the platform call completed normally;
 * it does not certify radio behavior. No sockets/FGS in any method of this facade. */
interface CompanionRegistryFacade {
    fun hasFeature(): Boolean
    fun hasObservePermission(): Boolean
    fun isBonded(address: PairAddress): Boolean
    fun ownAssociations(): List<OwnedAssociation>
    fun start(registration: ObservationRegistration): Boolean
    fun stop(registration: ObservationRegistration): Boolean
    fun disassociate(association: OwnedAssociation): Boolean
}

class CompanionObservationPolicy(private val apiLevel: Int, private val ownPackageName: String, private val registry: CompanionRegistryFacade) {
    private var generation = 0L
    private var registration: ObservationRegistration? = null
    private var consentGeneration = -1L
    private var owned: OwnedAssociation? = null

    @Synchronized fun register(admission: CompanionAdmission): CompanionResult {
        fun refuse(reason: CompanionFailure): CompanionResult { stop(); return denied(reason) }
        return try {
            if (!registry.hasFeature()) return refuse(CompanionFailure.FEATURE_ABSENT)
            if (!admission.consent) return refuse(CompanionFailure.NO_CONSENT)
            if (!registry.isBonded(admission.address)) return refuse(CompanionFailure.NOT_BONDED)
            if (apiLevel >= 31 && !registry.hasObservePermission()) return refuse(CompanionFailure.PERMISSION_DENIED)
            val matches = registry.ownAssociations().filter { it.packageName == ownPackageName && it.address == admission.address && (apiLevel < 33 || (it.id != null && it.id > 0)) }
            if (matches.size != 1) return refuse(CompanionFailure.ASSOCIATION_MISMATCH)
            val association = matches.single()
            val current = registration
            if (current != null && current.callbackGeneration == generation && current.association.sameEndpoint(association) && consentGeneration == admission.consentGeneration) return CompanionResult.Registered(current)
            if (current != null && stop() is CompanionResult.Denied) return denied(CompanionFailure.STOP_FAILED)
            owned = association
            consentGeneration = admission.consentGeneration
            if (apiLevel < 31) return CompanionResult.LegacyManual
            val kind = when { apiLevel >= 36 -> ObservationKind.REQUEST_ID_36; apiLevel >= 33 -> ObservationKind.ASSOCIATION_ADDRESS_33; else -> ObservationKind.ADDRESS_31 }
            val next = ObservationRegistration(kind, association, ++generation)
            // Keep the actual attempted handle even if the platform fails after effect.
            registration = next
            if (!registry.start(next)) { generation++; return denied(CompanionFailure.REGISTRATION_DENIED) }
            CompanionResult.Registered(next)
        } catch (_: SecurityException) { refuse(CompanionFailure.PERMISSION_DENIED) }
        catch (_: Exception) { refuse(CompanionFailure.PLATFORM_ERROR) }
    }

    /** Callback is only a trigger candidate. Root must recheck profile/permissions and C4
     * immediately before FGS. BLE appearance alone can never pass actuallyConnected. */
    @Synchronized fun appeared(callbackGeneration: Long, address: PairAddress, associationId: Int?, actuallyConnected: Boolean): CompanionResult {
        val candidate = validateCallback(callbackGeneration, address, associationId)
        if (candidate !is CompanionResult.Trigger) return candidate
        return if (actuallyConnected) candidate else denied(CompanionFailure.NOT_CONNECTED)
    }

    /** Validates only the trusted original callback, never connection or start eligibility. */
    @Synchronized fun validateCallback(callbackGeneration: Long, address: PairAddress, associationId: Int?): CompanionResult {
        val current = registration ?: return denied(CompanionFailure.STALE_CALLBACK)
        if (callbackGeneration != generation || callbackGeneration != current.callbackGeneration) return denied(CompanionFailure.STALE_CALLBACK)
        if (address != current.association.address || (apiLevel >= 33 && associationId != current.association.id)) return denied(CompanionFailure.WRONG_DEVICE)
        return try {
            if (!registry.hasObservePermission()) return denied(CompanionFailure.PERMISSION_DENIED)
            if (!registry.isBonded(address)) return denied(CompanionFailure.NOT_BONDED)
            if (registry.ownAssociations().none { it.sameEndpoint(current.association) }) return denied(CompanionFailure.ASSOCIATION_MISMATCH)
            CompanionResult.Trigger(address, consentGeneration)
        } catch (_: SecurityException) { denied(CompanionFailure.PERMISSION_DENIED) }
        catch (_: Exception) { denied(CompanionFailure.PLATFORM_ERROR) }
    }

    /** Call C4.stop(REVOKED/UNPAIRED) first, so even already emitted triggers are stale.
     * Failed unregister retains the handle for explicit cleanup, with callbacks invalidated. */
    @Synchronized fun stop(): CompanionResult {
        generation++
        val current = registration ?: return CompanionResult.Stopped
        return try {
            if (!registry.stop(current)) return denied(CompanionFailure.STOP_FAILED)
            registration = null
            CompanionResult.Stopped
        } catch (_: Exception) { denied(CompanionFailure.STOP_FAILED) }
    }

    /** Controller calls after durable explicit selection and confirmed local stop. */
    @Synchronized fun explicitSetupSelected() {
        check(registration == null)
        owned = null
    }

    /** Capture once, before local forget. A failed query is an unresolved marker, not absence. */
    @Synchronized fun captureCleanup(address: PairAddress): PendingCompanionCleanup {
        val matches = runCatching { registry.ownAssociations().filter { it.packageName == ownPackageName && it.address == address } }
        val remembered = (registration?.association ?: owned)?.takeIf { it.address == address }
        val association = remembered ?: matches.getOrNull()?.singleOrNull()
        return PendingCompanionCleanup(java.util.UUID.randomUUID().toString(), address,
            association?.id, ownPackageName, association?.identity,
            knownAbsent = association == null && matches.getOrNull()?.isEmpty() == true, handleCaptured = association != null)
    }

    /** Public-handle authority only. No SDK atomic compare-and-delete exists: an OS
     * replacement identical in all public fields inside the final query/delete interval
     * cannot be distinguished. Caller serializes explicit setup with this operation. */
    @Synchronized fun cleanupPending(target: PendingCompanionCleanup, stillAuthorized: () -> Boolean = { true }): CompanionCleanupResult {
        if (target.packageName != ownPackageName || !stillAuthorized()) return CompanionCleanupResult.IdentityUnresolved
        return try {
            val now = registry.ownAssociations() // error is never absence
            if (target.knownAbsent) return CompanionCleanupResult.AlreadyAbsent
            // A legacy address capture cannot be promoted into modern ID authority on OS upgrade.
            if (apiLevel >= 33 && target.handleCaptured && target.associationId == null)
                return CompanionCleanupResult.IdentityUnresolved
            val original = OwnedAssociation(target.associationId, target.address, target.packageName)
            if (!target.handleCaptured) {
                return if (now.none { it.packageName == ownPackageName && it.address == target.address })
                    CompanionCleanupResult.AlreadyAbsent else CompanionCleanupResult.IdentityUnresolved
            }
            val exact = now.singleOrNull { it.sameEndpoint(original) } ?: return CompanionCleanupResult.AlreadyAbsent
            val current = registration
            if (current?.association?.sameEndpoint(exact) == true) {
                if (stop() is CompanionResult.Denied) return CompanionCleanupResult.StopFailed
            } else if (apiLevel >= 31) {
                val kind = when { apiLevel >= 36 -> ObservationKind.REQUEST_ID_36; apiLevel >= 33 -> ObservationKind.ASSOCIATION_ADDRESS_33; else -> ObservationKind.ADDRESS_31 }
                if (!registry.stop(ObservationRegistration(kind, exact, 0))) return CompanionCleanupResult.StopFailed
            }
            // Stop changes metadata. Re-read the exact public handle, never recapture an ID
            // by address or follow a different address at the old ID.
            val beforeDelete = registry.ownAssociations().singleOrNull { it.sameEndpoint(original) }
                ?: return CompanionCleanupResult.AlreadyAbsent
            if (!stillAuthorized()) return CompanionCleanupResult.IdentityUnresolved
            if (!registry.disassociate(beforeDelete)) return CompanionCleanupResult.DisassociateFailed
            if (registry.ownAssociations().any { it.sameEndpoint(original) }) return CompanionCleanupResult.DisassociateFailed
            if (owned?.sameEndpoint(original) == true) owned = null
            CompanionCleanupResult.Removed
        } catch (_: SecurityException) { CompanionCleanupResult.PermissionDenied }
        catch (_: Exception) { CompanionCleanupResult.PlatformFailed }
    }

    @Synchronized fun unpair(address: PairAddress): CompanionResult {
        val stopped = stop()
        if (stopped is CompanionResult.Denied) return stopped
        // Re-read own associations before removal, never remove a reused ID/foreign pair.
        return try {
            val ownNow = registry.ownAssociations().filter { it.packageName == ownPackageName && it.address == address }
            if (ownNow.isEmpty()) { owned = null; return stopped }
            if (ownNow.size != 1) return denied(CompanionFailure.ASSOCIATION_MISMATCH)
            val association = ownNow.single()
            if (owned != null && owned?.address == address && owned?.sameEndpoint(association) != true) return denied(CompanionFailure.ASSOCIATION_MISMATCH)
            if (!registry.disassociate(association)) return denied(CompanionFailure.DISASSOCIATE_FAILED)
            owned = null
            stopped
        } catch (_: Exception) { denied(CompanionFailure.DISASSOCIATE_FAILED) }
    }
    private fun denied(reason: CompanionFailure) = CompanionResult.Denied(reason)
}

/** Each result refers solely to the persisted original association. */
sealed class CompanionCleanupResult {
    data object Removed : CompanionCleanupResult()
    data object AlreadyAbsent : CompanionCleanupResult()
    data object IdentityUnresolved : CompanionCleanupResult()
    data object PermissionDenied : CompanionCleanupResult()
    data object PlatformFailed : CompanionCleanupResult()
    data object StopFailed : CompanionCleanupResult()
    data object DisassociateFailed : CompanionCleanupResult()
    data object PersistenceFailed : CompanionCleanupResult()
    data object NoPending : CompanionCleanupResult()
    data class Batch(val outcomes: Map<String, CompanionCleanupResult>) : CompanionCleanupResult()
}
