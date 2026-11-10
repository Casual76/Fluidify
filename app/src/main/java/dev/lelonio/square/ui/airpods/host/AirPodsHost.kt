// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.android.platform.*
import dev.lelonio.square.airpods.runtime.android.*
import dev.lelonio.square.airpods.runtime.storage.*
import dev.lelonio.square.ui.airpods.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Read-only profile observer; never opens AACP, starts services or changes Bluetooth policies. */
@Suppress("MissingPermission")
internal class HostBluetooth(
    private val context: Context,
    private val requestProfile: (BluetoothProfile.ServiceListener) -> Boolean = { listener ->
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.getProfileProxy(context, listener, BluetoothProfile.A2DP) == true
    },
    private val releaseProfile: (Int, BluetoothProfile) -> Unit = { profile, proxy ->
        context.getSystemService(BluetoothManager::class.java)?.adapter?.closeProfileProxy(profile, proxy)
    },
) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var profile: BluetoothProfile? = null
    private var requested = false
    private var closed = false
    fun granted() = Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    fun prepare() {
        if (closed || requested || !granted()) return
        requested = true
        val accepted = runCatching { requestProfile(object: BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                if (closed || p != BluetoothProfile.A2DP) releaseProfile(p, proxy) else profile = proxy
            }
            override fun onServiceDisconnected(p: Int) {
                if (p == BluetoothProfile.A2DP) { profile = null; requested = false }
            }
        }) }.getOrDefault(false)
        if (!accepted) requested = false
    }
    fun enabled() = granted() && runCatching { adapter?.isEnabled == true }.getOrDefault(false)
    fun connected(pair: PairAddress?) = pair != null && granted() && runCatching {
        adapter?.isEnabled == true && profile?.connectedDevices?.any { it.address.equals(pair.value, true) } == true
    }.getOrDefault(false)
    fun label(pair: PairAddress): String? = if (!granted()) null else runCatching { adapter?.getRemoteDevice(pair.value)?.name }.getOrNull()
    fun close() { closed = true; profile?.let { releaseProfile(BluetoothProfile.A2DP, it) }; profile = null }
}

internal class AirPodsHost(private val context: Context, val owner: AirPodsAndroidController?,
    private val associationRegistry: dev.lelonio.square.airpods.runtime.lifecycle.CompanionRegistryFacade? = owner?.companionRegistry,
    private val functionalAuthority: AirPodsFunctionalUiAuthority? = null,
    private val ingressCurrent: () -> Boolean = { true },
) {
    val state = MutableStateFlow(AirPodsUiState())
    var stamp: HostStamp? = null
        private set
    private val ids = CandidateIds()
    private val bluetooth = HostBluetooth(context)
    private val commands = Mutex()
    private var candidates = emptyList<PairAddress>()
    private var historyGeneration = -1L
    private val headHistory = ArrayDeque<HeadSample>()
    private var lastHead: Received<HeadSample>? = null
    private var failure: String? = null
    @Volatile private var closed = false
    private fun text(id: Int) = context.getString(id)
    fun close() { closed = true; stamp = null; bluetooth.close() }

    suspend fun refreshCandidates() {
        candidates = owner?.bondedPairs().orEmpty()
        ids.retain(candidates.toSet())
        candidates.forEach(ids::id)
        refresh()
    }
    suspend fun permissionsChanged() {
        // Results refresh facts only: never grant consent, select, verify or enable here.
        owner?.permissionsChanged()
        refreshCandidates()
    }
    suspend fun refresh() {
        val o = owner
        val data = withContext(Dispatchers.IO) { o?.store?.snapshot()?.getOrNull() }
        if (closed || !ingressCurrent() || o == null || data == null) {
            state.value = AirPodsUiState(diagnostics = listOf(text(if(o == null) R.string.airpods_host_absent else R.string.airpods_host_storage)))
            stamp = null
            return
        }
        bluetooth.prepare()
        val domain = o.state.value
        val runtime = o.runtime.value
        val mapped = mapAirPodsUiState(domain, data, bluetooth.connected(data.selectedPair), bluetooth.granted(),
            runtime.productConformance, Build.FINGERPRINT, SystemClock.elapsedRealtime())
        val base = mapped.copy(enabled = mapped.enabled && runtime.active && !runtime.verifying, functionalAuthority = functionalAuthority)
        val nls = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        val notifications = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val selected = data.selectedPair != null
        val associations = withContext(Dispatchers.IO) { runCatching { associationRegistry?.ownAssociations() }.getOrNull() }
        val companionFeature = runCatching { associationRegistry?.hasFeature() == true }.getOrDefault(false)
        val alreadyAssociated = associations?.any { it.address == data.selectedPair && it.packageName == context.packageName } == true
        val ready = base.controlsReady && runtime.active
        val configured = base.surfaceAuthorized && base.bluetoothGranted && bluetooth.enabled() && selected
        val commonReason = when {
            !selected -> R.string.airpods_reason_selection
            !base.bluetoothGranted -> R.string.airpods_reason_bluetooth_permission
            !bluetooth.enabled() -> R.string.airpods_reason_bluetooth_off
            !base.verified && !base.testAuthorized -> R.string.airpods_reason_setup
            !base.productGatePassed && !base.testAuthorized -> R.string.airpods_reason_gate
            else -> null
        }
        val liveReason = commonReason ?: when {
            base.suppressed -> R.string.airpods_reason_resume
            !data.start.enabled -> R.string.airpods_reason_enable
            !notifications -> R.string.airpods_reason_notifications
            !base.profileConnected -> R.string.airpods_reason_connection
            !ready -> R.string.airpods_reason_session
            else -> null
        }
        // Read each shared observational selector once for this UI refresh.
        val trackingStartBlock = o.trackingUnavailableReason(true)
        val trackingStopBlock = o.trackingUnavailableReason(false)
        fun trackingReason(block: TrackingBlockReason?): Int = when (block) {
            TrackingBlockReason.NO_ADMITTED_SESSION -> R.string.airpods_reason_session
            TrackingBlockReason.HEAD_GESTURES_DISABLED -> R.string.airpods_reason_head_preference
            TrackingBlockReason.AUTOMATIONS_DISABLED -> R.string.airpods_reason_automations_off
            TrackingBlockReason.BACKGROUND_DISABLED -> R.string.airpods_reason_tracking_background
            null -> R.string.airpods_reason_capability
        }
        fun reasonFor(key: String): String {
            val specific = when (key) {
                "host.select" -> R.string.airpods_reason_bluetooth_permission
                "setup.BLUETOOTH_PERMISSION" -> R.string.airpods_reason_access_granted
                "setup.NOTIFICATION_PERMISSION" -> R.string.airpods_reason_access_granted
                "setup.MEDIA_ACCESS" -> R.string.airpods_reason_access_granted
                "setup.COMPANION_ASSOCIATION" -> when {
                    !selected -> R.string.airpods_reason_selection
                    !base.bluetoothGranted -> R.string.airpods_reason_bluetooth_permission
                    !companionFeature -> R.string.airpods_reason_companion_feature
                    associations == null -> R.string.airpods_reason_companion_query
                    alreadyAssociated -> R.string.airpods_reason_companion_present
                    else -> null
                }
                "setup.VERIFY_SELECTED" -> when {
                    !selected -> R.string.airpods_reason_selection
                    !base.bluetoothGranted -> R.string.airpods_reason_bluetooth_permission
                    !base.profileConnected -> R.string.airpods_reason_connection
                    !notifications -> R.string.airpods_reason_notifications
                    runtime.verifying -> R.string.airpods_reason_verifying
                    else -> null
                }
                "setup.DISABLE", "setup.STOP", "setup.FORGET" -> R.string.airpods_reason_selection
                "setup.RETRY_CLEANUP" -> R.string.airpods_cleanup_none
                "setup.ENABLE", "setup.RESUME" -> commonReason ?: when {
                    !base.profileConnected -> R.string.airpods_reason_connection
                    !notifications -> R.string.airpods_reason_notifications
                    else -> null
                }
                "setup.CONNECT" -> commonReason ?: when {
                    base.suppressed -> R.string.airpods_reason_resume
                    !data.start.enabled -> R.string.airpods_reason_enable
                    else -> R.string.airpods_reason_connected
                }
                "setup.DISCONNECT" -> liveReason
                "local.OTHER_APPS", "local.OTHER_APPS.enable", "status.OTHER_APPS" -> when {
                    !nls -> R.string.airpods_reason_nls
                    !runtime.mediaAvailable -> R.string.airpods_reason_media
                    else -> commonReason
                }
                "setup.START_DIAGNOSTICS" -> R.string.airpods_reason_collecting
                "setup.STOP_DIAGNOSTICS" -> R.string.airpods_reason_not_collecting
                "host.volume" -> liveReason ?: R.string.airpods_reason_volume
                "local.preferredMode" -> commonReason ?: R.string.airpods_reason_modes
                "tracking.start" -> liveReason ?: trackingReason(trackingStartBlock)
                "tracking.stop" -> liveReason ?: trackingStopBlock?.let(::trackingReason) ?: R.string.airpods_reason_tracking
                else -> when {
                    key.endsWith(".disable") -> R.string.airpods_reason_selection
                    key.contains("HEAD_GESTURES") || key.contains("ALTERNATE_PACKETS") || key.startsWith("gesture.") ->
                        commonReason ?: R.string.airpods_reason_capability
                    key.startsWith("local.") || key == "host.ca_volume" -> commonReason
                    else -> liveReason
                }
            }
            return text(specific ?: R.string.airpods_reason_session)
        }
        val availability = buildMap {
            fun putAllowed(key: String, enabled: Boolean, reason: String = reasonFor(key)) { put(key, UiAvailability(enabled, if(enabled) null else reason)) }
            putAllowed("host.select", base.bluetoothGranted && !o.isFunctionalTest)
            SetupAction.entries.forEach { action ->
                val allowed = when(action) {
                    SetupAction.BLUETOOTH_PERMISSION -> !base.bluetoothGranted
                    SetupAction.NOTIFICATION_PERMISSION -> Build.VERSION.SDK_INT >= 33 && !notifications
                    SetupAction.CALL_PERMISSION -> true
                    SetupAction.MEDIA_ACCESS -> !nls
                    SetupAction.COMPANION_ASSOCIATION -> selected && base.bluetoothGranted && companionFeature && associations != null && !alreadyAssociated
                    SetupAction.VERIFY_SELECTED -> !o.isFunctionalTest && selected && base.bluetoothGranted && base.profileConnected && notifications && !runtime.verifying
                    SetupAction.ENABLE, SetupAction.RESUME -> !o.isFunctionalTest && configured && base.profileConnected && notifications
                    SetupAction.CONNECT -> configured && !base.suppressed && data.start.enabled && !base.profileConnected
                    SetupAction.DISCONNECT -> configured && !base.suppressed && data.start.enabled && base.profileConnected
                    SetupAction.DISABLE, SetupAction.STOP -> selected
                    SetupAction.FORGET -> selected
                    SetupAction.RETRY_CLEANUP -> data.pendingCleanup.isNotEmpty()
                    SetupAction.BLUETOOTH_SETTINGS, SetupAction.TILE_INSTRUCTIONS -> true
                    SetupAction.INFORMATION -> base.controlsReady
                    SetupAction.EXPORT_DIAGNOSTICS, SetupAction.DELETE_DIAGNOSTICS -> true
                    SetupAction.START_DIAGNOSTICS -> !o.diagnostics.collecting.value
                    SetupAction.STOP_DIAGNOSTICS -> o.diagnostics.collecting.value
                }
                putAllowed("setup.$action", allowed)
            }
            LocalToggle.entries.forEach { toggle ->
                val canEnable = configured && when(toggle) {
                LocalToggle.OTHER_APPS -> nls && runtime.mediaAvailable
                LocalToggle.HEAD_GESTURES, LocalToggle.ALTERNATE_PACKETS -> base.identity?.modelDeclared?.contains(Capability.HEAD_GESTURES) == true
                LocalToggle.DISCONNECT_UNWORN -> true
                else -> true
                }
                putAllowed("local.$toggle.enable", canEnable)
                putAllowed("local.$toggle.disable", selected)
                // Activation prerequisites must never trap an existing consent ON.
                putAllowed("local.$toggle", if (base.pair.localToggleValue(toggle)) selected else canEnable)
            }
            // Readiness is observational, never a receipt of an external media action or route.
            for (toggle in listOf(LocalToggle.AUTOMATIONS, LocalToggle.BACKGROUND, LocalToggle.OTHER_APPS)) {
                val reason = liveReason?.let(::text) ?: when {
                    !base.pair.host.automationsEnabled -> text(R.string.airpods_reason_automations_off)
                    toggle == LocalToggle.OTHER_APPS && !nls -> text(R.string.airpods_reason_nls)
                    toggle == LocalToggle.OTHER_APPS && !runtime.mediaAvailable -> text(R.string.airpods_reason_media)
                    else -> null
                }
                putAllowed("status.$toggle", reason == null, reason ?: "")
            }
            putAllowed("host.ca_volume", configured)
            putAllowed("host.volume", ready && o.volume.value != null)
            putAllowed("local.preferredMode", configured && base.modes.isNotEmpty())
            GestureSide.entries.forEach { side -> putAllowed("gesture.$side", configured && when(side) {
                GestureSide.LEFT, GestureSide.RIGHT -> ready && base.stemPresentation().editable
                else -> base.identity?.modelDeclared?.contains(Capability.HEAD_GESTURES) == true
            }) }
            val stemReason = liveReason ?: base.stemPresentation().blockReason
            for (side in listOf(GestureSide.LEFT, GestureSide.RIGHT)) {
                putAllowed("gesture.$side", configured && ready && stemReason == null,
                    stemReason?.let(::text) ?: "")
            }
            putAllowed("tracking.start", ready && trackingStartBlock == null && base.identity?.modelDeclared?.contains(Capability.HEAD_GESTURES) == true)
            putAllowed("tracking.stop", ready && trackingStopBlock == null && domain.activePacketFormat != null)
            AirPodsProperties.controls.forEach { spec ->
                val reason = liveReason ?: when {
                    base.identity?.recognized != true -> R.string.airpods_reason_information
                    !spec.candidate(base.identity!!) -> R.string.airpods_reason_capability
                    spec.property in domain.pending -> R.string.airpods_ui_pending
                    else -> null
                }
                putAllowed("property.${spec.property}", reason == null, reason?.let(::text) ?: "")
            }
        }
        if (historyGeneration != domain.generation) { headHistory.clear(); lastHead = null; historyGeneration = domain.generation }
        domain.head?.takeIf { it.generation == domain.generation && it != lastHead }?.let {
            lastHead = it; headHistory.addLast(it.value); while(headHistory.size > 120) headHistory.removeFirst()
        }
        val p = base.pair
        val assignments = mapOf(GestureSide.LEFT to gestureName(p.leftLongPress), GestureSide.RIGHT to gestureName(p.rightLongPress),
            GestureSide.NOD to gestureName(p.nodAction), GestureSide.SHAKE to gestureName(p.shakeAction))
            .mapNotNull { (side, name) -> GestureAction.entries.firstOrNull { it.name == name }?.let { side to it } }.toMap()
        state.value = base.copy(pendingCleanupCount = data.pendingCleanup.size,
            candidates = candidates.mapIndexed { index, address -> PairCandidate(ids.id(address),
            bluetooth.label(address) ?: context.getString(R.string.airpods_host_candidate, index + 1), text(R.string.airpods_host_label_only)) },
            selectedCandidateId = data.selectedPair?.takeIf { it in candidates }?.let(ids::id), availability = availability,
            gestureAssignments = assignments, headHistory = headHistory.toList(),
            localVolumePercent = o.volume.value?.let { it.current * 100 / it.maximum },
            diagnostics = listOfNotNull(failure, runtime.failure, o.sanitizedDiagnostics()) + o.diagnostics.records.value.map { "${it.id} ${it.atMillis} ${it.kind} ${it.stage}" })
        stamp = HostStamp.capture(data, domain.generation)
    }

    suspend fun dispatch(captured: HostStamp?, action: AirPodsUiAction, platform: (SetupAction, PairAddress?) -> Unit) = commands.withLock {
        val o = owner ?: return@withLock
        if (closed || !ingressCurrent()) return@withLock
        val data = withContext(Dispatchers.IO) { o.store.snapshot().getOrNull() } ?: return@withLock
        if(captured == null || !captured.matches(data, o.state.value.generation)) { failure = text(R.string.airpods_host_stale); refresh(); return@withLock }
        refresh() // Recheck platform, persisted gates and pending before execution.
        val latest = withContext(Dispatchers.IO) { o.store.snapshot().getOrNull() }
        if(closed || !ingressCurrent() || latest == null || !captured.matches(latest, o.state.value.generation)) {
            failure = text(R.string.airpods_host_stale); refresh(); return@withLock
        }
        val s = state.value
        val key = if (action is AirPodsUiAction.Toggle)
            "local.${action.toggle}.${if (action.enabled) "enable" else "disable"}" else actionKey(action)
        if(key?.let { !s.allowed(it) } == true) { failure = s.availability[key]?.reason ?: text(R.string.airpods_host_requirements); refresh(); return@withLock }
        var actionFailure: String? = null
        val accepted = when(action) {
            is AirPodsUiAction.SelectPair -> ids.resolve(action.candidateId)?.takeIf { it in candidates }?.let { o.selectPair(it).isSuccess } == true
            is AirPodsUiAction.Write -> action.generation == o.state.value.generation && s.writeAllowed(action.property, action.value) && when(action.property) {
                CommandProperty.NAME -> (action.value as? PropertyValue.Name)?.let { o.rename(it.value) } == true
                CommandProperty.EQ -> (action.value as? PropertyValue.Eq)?.let(o::setEq) == true
                else -> o.setProperty(action.property, action.value)
            }
            is AirPodsUiAction.Toggle -> o.configure { toggleSettings(it, action.toggle, action.enabled) }.isSuccess
            is AirPodsUiAction.ConversationVolume -> action.percent in 10..85 && o.configure { it.copy(host = it.host.copy(conversationVolumePercent = action.percent)) }.isSuccess
            is AirPodsUiAction.LocalVolume -> action.percent in 0..100 && o.volume.value?.let { o.setVolume(it.maximum * action.percent / 100) } == true
            is AirPodsUiAction.PreferredMode -> action.mode in s.modes && action.mode != ListeningMode.ANC && o.configure {
                it.copy(secondMode = QuickMode.valueOf(action.mode.name)) }.isSuccess
            is AirPodsUiAction.AssignGesture -> when(action.side) {
                GestureSide.LEFT -> longAction(action.action)?.let { o.setLongPress(left = it) } == true
                GestureSide.RIGHT -> longAction(action.action)?.let { o.setLongPress(right = it) } == true
                GestureSide.NOD -> headAction(action.action)?.let { mapped -> o.configure { it.copy(nodAction = mapped) }.isSuccess } == true
                GestureSide.SHAKE -> headAction(action.action)?.let { mapped -> o.configure { it.copy(shakeAction = mapped) }.isSuccess } == true
            }
            is AirPodsUiAction.Tracking -> o.setTracking(action.enabled)
            is AirPodsUiAction.Setup -> when(action.action) {
                SetupAction.VERIFY_SELECTED -> o.beginVerification().let { it !is dev.lelonio.square.airpods.runtime.lifecycle.GateResult.Denied }
                SetupAction.ENABLE, SetupAction.RESUME -> o.enableOrResume().let { it !is dev.lelonio.square.airpods.runtime.lifecycle.GateResult.Denied }
                SetupAction.CONNECT -> o.requestAudio(AudioOperation.CONNECT).let { it is PlatformResult.RequestAccepted || it is PlatformResult.Queued || it is PlatformResult.SettingsRequired }
                SetupAction.DISCONNECT -> o.requestAudio(AudioOperation.DISCONNECT).let { it is PlatformResult.RequestAccepted || it is PlatformResult.Queued || it is PlatformResult.SettingsRequired }
                SetupAction.DISABLE -> o.stopControls(SuppressReason.DISABLED).let { it !is dev.lelonio.square.airpods.runtime.lifecycle.GateResult.Denied }
                SetupAction.STOP -> o.stopControls().let { it !is dev.lelonio.square.airpods.runtime.lifecycle.GateResult.Denied }
                SetupAction.FORGET -> o.forget().let { it !is dev.lelonio.square.airpods.runtime.lifecycle.GateResult.Denied }
                SetupAction.RETRY_CLEANUP -> {
                    val result = o.retryPendingCleanup()
                    actionFailure = cleanupFailureMessage(result)
                    actionFailure == null
                }
                SetupAction.DELETE_DIAGNOSTICS -> { o.diagnostics.clear(); true }
                SetupAction.INFORMATION -> o.requestInformation()
                SetupAction.START_DIAGNOSTICS -> {
                    if (o.diagnostics.startCollection()) o.diagnostics.record(
                        dev.lelonio.square.airpods.runtime.android.DiagnosticKind.SNAPSHOT,
                        o.state.value.stage,
                    )
                    true
                }
                SetupAction.STOP_DIAGNOSTICS -> { o.diagnostics.stopCollection(); true }
                else -> { platform(action.action, data.selectedPair); true }
            }
        }
        failure = if(accepted) null else actionFailure ?: text(R.string.airpods_host_rejected)
        refresh()
    }

    private fun cleanupFailureMessage(result: dev.lelonio.square.airpods.runtime.lifecycle.CompanionCleanupResult): String? {
        val cause = if (result is dev.lelonio.square.airpods.runtime.lifecycle.CompanionCleanupResult.Batch)
            result.outcomes.values.firstOrNull { cleanupFailureMessage(it) != null } ?: return null
        else result
        val resource = when (cause) {
            dev.lelonio.square.airpods.runtime.lifecycle.CompanionCleanupResult.Removed,
            dev.lelonio.square.airpods.runtime.lifecycle.CompanionCleanupResult.AlreadyAbsent,
            dev.lelonio.square.airpods.runtime.lifecycle.CompanionCleanupResult.NoPending -> return null
            dev.lelonio.square.airpods.runtime.lifecycle.CompanionCleanupResult.IdentityUnresolved -> R.string.airpods_cleanup_identity
            dev.lelonio.square.airpods.runtime.lifecycle.CompanionCleanupResult.PermissionDenied -> R.string.airpods_cleanup_permission
            dev.lelonio.square.airpods.runtime.lifecycle.CompanionCleanupResult.PersistenceFailed -> R.string.airpods_cleanup_storage
            dev.lelonio.square.airpods.runtime.lifecycle.CompanionCleanupResult.StopFailed -> R.string.airpods_cleanup_stop
            else -> R.string.airpods_cleanup_platform
        }
        return text(resource)
    }
}
