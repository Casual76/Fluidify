// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.bluetooth.*
import android.companion.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*

class AndroidExitHistoryProvider(private val context: Context) : ExitHistoryProvider {
    override fun query(): ExitHistory {
        if (Build.VERSION.SDK_INT < 30) return ExitHistory.Unavailable
        return try {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return ExitHistory.QueryFailed
            ExitHistory.Available(manager.getHistoricalProcessExitReasons(context.packageName, 0, 0).map {
                val cause = when (it.reason) {
                    ApplicationExitInfo.REASON_USER_REQUESTED -> ExitCause.USER_REQUESTED
                    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ExitCause.RELIABLE_OTHER
                    else -> ExitCause.UNKNOWN
                }
                OwnProcessExit(ExitKey(it.timestamp, it.pid, it.processName, it.reason), cause)
            })
        } catch (_: Exception) { ExitHistory.QueryFailed }
    }
}

enum class BondFact { BONDED, NONE, BONDING, UNKNOWN }
enum class ProfileConnection { UNRESOLVED, CONNECTED, DISCONNECTED }

/** Fakeable platform facts. A readiness callback belongs only to its subscribing ingress. */
interface BluetoothRuntimeFacts {
    val adapter: BluetoothAdapter?
    fun permitted(): Boolean
    fun device(address: PairAddress): BluetoothDevice? = if (permitted()) adapter?.getRemoteDevice(address.value) else null
    fun bond(address: PairAddress): BondFact
    fun bonded(address: PairAddress) = bond(address) == BondFact.BONDED
    fun connection(address: PairAddress): ProfileConnection
    fun connected(address: PairAddress) = connection(address) == ProfileConnection.CONNECTED
    fun prepare()
    /** null means request refused/failed; there is no automatic retry. */
    fun whenProfileReady(ready: () -> Unit): AutoCloseable?
    fun audioSettingsIntent(): Intent
}

/** Profile state is queried from Android; broadcast payloads are never admission facts. */
@Suppress("MissingPermission")
class AndroidBluetoothFacts @JvmOverloads constructor(private val context: Context,
    private val profileRequest: ((BluetoothProfile.ServiceListener) -> Boolean)? = null,
) : BluetoothRuntimeFacts {
    override val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var a2dp: BluetoothProfile? = null
    private var requested = false
    private val waiting = mutableSetOf<() -> Unit>()
    @Synchronized override fun prepare() { requestProfile() }
    private fun requestProfile(): Boolean {
        if (a2dp != null || requested) return true
        if (!permitted()) return false
        requested = true
        val accepted = runCatching {
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.A2DP) return
                    val callbacks = synchronized(this@AndroidBluetoothFacts) {
                        a2dp = proxy; requested = false
                        waiting.toList().also { waiting.clear() }
                    }
                    callbacks.forEach { runCatching { it() } }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile != BluetoothProfile.A2DP) return
                    val callbacks = synchronized(this@AndroidBluetoothFacts) {
                        a2dp = null; requested = false
                        waiting.toList().also { waiting.clear() }
                    }
                    callbacks.forEach { runCatching { it() } } // terminal notification consumes pending ingress; no retry
                }
            }
            profileRequest?.invoke(listener) ?: (adapter?.getProfileProxy(context, listener, BluetoothProfile.A2DP) == true)
        }.getOrDefault(false)
        if (!accepted) { requested = false; waiting.clear() }
        return accepted
    }
    @Synchronized override fun whenProfileReady(ready: () -> Unit): AutoCloseable? {
        if (a2dp != null) { ready(); return AutoCloseable {} }
        waiting.add(ready)
        if (!requestProfile()) { waiting.remove(ready); return null }
        return AutoCloseable { synchronized(this) { waiting.remove(ready) } }
    }
    override fun permitted() = Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    override fun device(address: PairAddress): BluetoothDevice? = if (permitted()) adapter?.getRemoteDevice(address.value) else null
    override fun bond(address: PairAddress): BondFact = runCatching {
        if (!permitted()) return BondFact.UNKNOWN
        when (device(address)?.bondState) {
            BluetoothDevice.BOND_BONDED -> BondFact.BONDED
            BluetoothDevice.BOND_NONE -> BondFact.NONE
            BluetoothDevice.BOND_BONDING -> BondFact.BONDING
            else -> BondFact.UNKNOWN
        }
    }.getOrDefault(BondFact.UNKNOWN)
    override fun connection(address: PairAddress): ProfileConnection {
        if (!permitted()) return ProfileConnection.DISCONNECTED
        val proxy = a2dp ?: return ProfileConnection.UNRESOLVED
        return if (runCatching { adapter?.isEnabled == true && proxy.connectedDevices.any { it.address.equals(address.value, true) } }.getOrDefault(false))
            ProfileConnection.CONNECTED else ProfileConnection.DISCONNECTED
    }
    /** Public A2DP API has no ordinary connect/disconnect method. Explicit Settings fallback. */
    override fun audioSettingsIntent() = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
}

@Suppress("DEPRECATION", "MissingPermission")
class AndroidCompanionRegistry(private val context: Context, private val facts: BluetoothRuntimeFacts) : CompanionRegistryFacade {
    constructor(context: Context, facts: AndroidBluetoothFacts) : this(context, facts as BluetoothRuntimeFacts)
    private val manager get() = context.getSystemService(CompanionDeviceManager::class.java)
    private val requests = mutableMapOf<ObservationRegistration, ObservingDevicePresenceRequest>()
    override fun hasFeature() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
    override fun hasObservePermission() = Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE) == PackageManager.PERMISSION_GRANTED
    override fun isBonded(address: PairAddress) = facts.bonded(address)
    override fun ownAssociations(): List<OwnedAssociation> {
        val cdm = manager ?: error("COMPANION_UNAVAILABLE")
        return if (Build.VERSION.SDK_INT >= 33) cdm.myAssociations.mapNotNull { a ->
            a.deviceMacAddress?.toString()?.let { OwnedAssociation(a.id, PairAddress(it), context.packageName) }
        } else cdm.associations.map { OwnedAssociation(null, PairAddress(it), context.packageName) }
    }
    override fun start(registration: ObservationRegistration): Boolean {
        val cdm = manager ?: return false
        if (Build.VERSION.SDK_INT >= 36) {
            val request = ObservingDevicePresenceRequest.Builder().setAssociationId(requireNotNull(registration.association.id)).build()
            requests[registration] = request; cdm.startObservingDevicePresence(request)
        } else if (Build.VERSION.SDK_INT >= 31) cdm.startObservingDevicePresence(registration.association.address.value)
        else return false
        return true
    }
    override fun stop(registration: ObservationRegistration): Boolean {
        // A successful current own-association query can prove system cleanup already occurred.
        val own = ownAssociations()
        if (own.none { it.sameEndpoint(registration.association) }) { requests.remove(registration); return true }
        if (registration.association.packageName != context.packageName) return false
        // Address-based stop must not resolve another ID sharing this address.
        if (registration.kind != ObservationKind.REQUEST_ID_36 &&
            own.count { it.packageName == context.packageName && it.address == registration.association.address } != 1) return false
        val cdm = manager ?: return false
        if (Build.VERSION.SDK_INT >= 36) {
            cdm.stopObservingDevicePresence(requests[registration] ?: ObservingDevicePresenceRequest.Builder().setAssociationId(requireNotNull(registration.association.id)).build()); requests.remove(registration)
        } else if (Build.VERSION.SDK_INT >= 31) cdm.stopObservingDevicePresence(registration.association.address.value)
        return true
    }
    override fun disassociate(association: OwnedAssociation): Boolean {
        if (association.packageName != context.packageName) return false
        val current = ownAssociations()
        if (current.none { it.sameEndpoint(association) }) return current.none {
            it.packageName == association.packageName && (it.id == association.id || it.address == association.address)
        } // mismatch is not confirmed absence; never remove a replacement
        val cdm = manager ?: return false
        if (Build.VERSION.SDK_INT >= 33) cdm.disassociate(requireNotNull(association.id)) else cdm.disassociate(association.address.value)
        return true
    }
    /** UI launches callback's IntentSender; association completion must be re-read, never inferred. */
    fun associate(address: PairAddress, callback: CompanionDeviceManager.Callback, handler: android.os.Handler) {
        require(hasFeature() && facts.bonded(address))
        val filter = BluetoothDeviceFilter.Builder().setAddress(address.value).build()
        requireNotNull(manager).associate(AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build(), callback, handler)
    }
}

/** Stable lifetime identity is shared across callback sources, never derived from callback IDs. */
class ConnectionLifetimes {
    private var next = 0L
    private val active = mutableMapOf<PairAddress, String>()
    @Synchronized fun observe(address: PairAddress, connected: Boolean): String? {
        if (!connected) { active.remove(address); return null }
        return active.getOrPut(address) { "link-${++next}" }
    }
}
