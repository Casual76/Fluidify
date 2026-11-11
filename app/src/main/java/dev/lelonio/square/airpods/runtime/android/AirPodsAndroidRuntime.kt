// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.*
import android.os.*
import android.media.AudioManager
import dev.lelonio.square.airpods.runtime.android.platform.*
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.*
import dev.lelonio.square.airpods.runtime.automation.AutomationSession
import dev.lelonio.square.airpods.runtime.automation.MediaVolume
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

object AirPodsAndroidRuntime {
    @Volatile private var installed: AirPodsAndroidController? = null
    @Synchronized fun install(context: Context, visibility: StateFlow<Boolean>,
        conformance: () -> Boolean = { false }, mediaFactory: (() -> AirPodsMediaPort)? = null): AirPodsAndroidController {
        return installed ?: AirPodsAndroidController(context.applicationContext, visibility, conformance, mediaFactory).also { installed = it }
    }
    fun get(): AirPodsAndroidController? = installed
}

data class AndroidRuntimeState(
    val active: Boolean = false,
    val verifying: Boolean = false,
    val setupVerified: Boolean = false,
    val productConformance: Boolean = false,
    val mediaAvailable: Boolean = false,
    val failure: String? = null, // fixed symbolic codes only; never exception text
)

/** All UI functions are process-local; no binder or exported command surface. Call suspending
 * operations from UI coroutines. Platform I/O, preferences and admission execute off main. */
class AirPodsAndroidController internal constructor(
    private val context: Context,
    private val visibility: StateFlow<Boolean>,
    private val conformance: () -> Boolean,
    private val mediaFactory: (() -> AirPodsMediaPort)?,
    private val testPermit: AirPodsFunctionalTestPermit? = null,
    private val scratchStore: AirPodsRuntimeStore? = null,
    private val onTestStopped: (() -> Unit)? = null,
    private val audioProfileFactory: () -> AudioProfilePort = { AndroidOrdinaryAudioProfile(context) },
    private val assistantLaunch: AssistantLaunchPort = AndroidAssistantLaunchPort(context),
    callPort: OrdinaryCallPort = AndroidOrdinaryCallPort(context),
    factsOverride: BluetoothRuntimeFacts? = null,
    registryOverride: CompanionRegistryFacade? = null,
    exitHistoryOverride: ExitHistoryProvider? = null,
    runtimeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    persistenceOverride: RuntimePersistence? = null,
    private val qualificationAuthority: AirPodsQualificationLifecycleAuthority? = null,
    private val observer: AirPodsQualificationObserver = AirPodsQualificationObserver.NONE,
    private val debugInformationIngress: ((Long, ByteArray) -> Unit)? = null,
) {
    private fun productConformance(): Boolean = testPermit == null && conformance()
    internal val isFunctionalTest get() = testPermit != null
    private val processScope = CoroutineScope(SupervisorJob() + runtimeDispatcher)
    val store = scratchStore ?: AirPodsRuntimeStore(persistenceOverride ?: SharedPreferencesRuntimePersistence(context.getSharedPreferences("airpods_runtime_v1", Context.MODE_PRIVATE)))
    private val repository = AirPodsRepository(processScope, AirPodsClock { SystemClock.elapsedRealtime() }, observer = observer)
    val state: StateFlow<AirPodsState> = repository.state
    private val mutableRuntime = MutableStateFlow(AndroidRuntimeState(
        productConformance = runCatching { productConformance() }.getOrDefault(false),
    ))
    val runtime = mutableRuntime.asStateFlow()
    private val mutableVolume = MutableStateFlow<MediaVolume?>(null)
    val volume = mutableVolume.asStateFlow()
    val diagnostics = AirPodsSanitizedDiagnostics()
    private val facts = factsOverride ?: AirPodsProcessBluetoothFacts.get(context)
    val companionRegistry = AndroidCompanionRegistry(context, facts)
    private val presenceRegistry = registryOverride ?: companionRegistry
    private val companion = CompanionObservationPolicy(Build.VERSION.SDK_INT, context.packageName, presenceRegistry)
    private val lifetimes = ConnectionLifetimes()
    private val gate = AirPodsStartGate(store, exitHistoryOverride ?: AndroidExitHistoryProvider(context), RuntimeClock { System.currentTimeMillis() },
        UUID.randomUUID().toString(), Process.myPid(), if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else context.packageName, Build.VERSION.SDK_INT, object : RuntimeOwnerFacade {
            override fun start(admission: ConnectionAdmission, generation: Long) = startOwner(admission)
            override fun stop() = stopOwner()
            override fun cleanup(): OwnerCleanup = ownerCleanup()
        }, testEntitled = { a -> functionalStartAuthorized(a) },
        qualificationEntitled = { a -> dev.lelonio.square.BuildConfig.DEBUG && testPermit?.allowed(visibility.value) == true &&
            qualificationAuthority?.valid(a.address, a.expectedConsentGeneration) == true })
    private val lock = Any()
    @Volatile private var setupVisible = false
    @Volatile private var ticket: ConnectionAdmission? = null
    private var sessionScope: CoroutineScope? = null
    private var service: AirPodsControlService? = null
    private var transport: GuardedTransport? = null
    private var transportLease: AirPodsTransportOwnership.Lease? = null
    @Volatile private var media: AirPodsMediaPort? = null
    private var observation: ObservationRegistration? = null
    private data class PendingPresence(val registration: ObservationRegistration, val pair: PairAddress,
        val associationId: Int?, val consent: Long, val epoch: Long)
    private var presenceEpoch = 0L
    private var pendingPresence: PendingPresence? = null
    private var readiness: AutoCloseable? = null
    private class PendingFunctionalStart(val pair: PairAddress, val consent: Long, val actionCurrent: () -> Boolean) {
        val ready = CompletableDeferred<Boolean>()
    }
    private var pendingFunctionalStart: PendingFunctionalStart? = null
    private var functionalReadiness: AutoCloseable? = null
    private class PendingAppOpening(val pair: PairAddress, val consent: Long,
        val foreground: AirPodsForegroundState, val epoch: Long) {
        val ready = CompletableDeferred<Boolean>()
    }
    private var pendingAppOpening: PendingAppOpening? = null
    private var openingReadiness: AutoCloseable? = null
    // Set only around gate.attempt(APP_OPEN), never retained by an established session.
    private var startingAppOpening: PendingAppOpening? = null
    private fun invalidateAppOpening() = synchronized(gate) {
        pendingAppOpening?.ready?.complete(false); pendingAppOpening = null
        openingReadiness?.close(); openingReadiness = null
    }
    private fun appOpeningCurrent(p: PendingAppOpening): Boolean {
        val data = store.snapshot().getOrNull() ?: return false
        return pendingAppOpening === p && !disposed && testPermit == null &&
            p.foreground.isCurrentForeground(p.epoch) && data.selectedPair == p.pair &&
            data.start.enabled && !data.start.suppressUntilExplicitResume &&
            data.start.consentGeneration == p.consent
    }
    private fun functionalStartAuthorized(a: ConnectionAdmission): Boolean {
        val pending = pendingFunctionalStart ?: return false
        return !disposed && setupVisible && testPermit?.allowed(visibility.value) == true &&
            pending.pair == a.address && pending.consent == a.expectedConsentGeneration && pending.actionCurrent()
    }
    private class PendingExplicitStart(val pair: PairAddress, val consent: Long,
        val presence: Long, val purpose: ConnectionPurpose, val visibleActionCurrent: () -> Boolean) {
        val ready = CompletableDeferred<Boolean>()
    }
    private var pendingExplicitStart: PendingExplicitStart? = null
    private var explicitReadiness: AutoCloseable? = null
    // Exists only across the synchronous gate attempt, including its durable marker I/O.
    private var startingExplicitStart: PendingExplicitStart? = null
    private fun invalidateExplicitStart() {
        pendingExplicitStart?.ready?.complete(false); pendingExplicitStart = null
        explicitReadiness?.close(); explicitReadiness = null
    }
    private fun explicitStartCurrent(p: PendingExplicitStart): Boolean {
        val data = store.snapshot().getOrNull() ?: return false
        return pendingExplicitStart === p && !disposed && presenceEpoch == p.presence &&
            p.visibleActionCurrent() && (p.purpose != ConnectionPurpose.VERIFICATION_ONLY || setupVisible) &&
            (p.purpose != ConnectionPurpose.PRODUCT || productConformance()) &&
            data.selectedPair == p.pair && data.start.enabled && !data.start.suppressUntilExplicitResume &&
            data.start.consentGeneration == p.consent
    }
    private fun invalidatePresence() = synchronized(gate) {
        invalidateExplicitStart()
        invalidateAppOpening()
        presenceEpoch++; pendingPresence = null; readiness?.close(); readiness = null
        pendingFunctionalStart?.ready?.complete(false); pendingFunctionalStart = null
        functionalReadiness?.close(); functionalReadiness = null
    }
    private var detector = PinnedHeadGestureDetector()
    private val calls = OrdinaryCallOperations(callPort)
    private var gestureContext: Any? = null
    private val platformEpoch = java.util.concurrent.atomic.AtomicLong()
    private var audioDeadline: Job? = null
    @Volatile private var audioReceipt: AirPodsAudioSetupReceipt? = null
    @Volatile private var observedSetupReceipt: AirPodsAudioSetupReceipt? = null
    @Volatile private var observedSetupAt: Long? = null
    private val mutableAudioResult = MutableStateFlow<PlatformResult?>(null)
    val audioResult = mutableAudioResult.asStateFlow()
    private val mutableAudioProfile = MutableStateFlow(ProfileConnection.UNRESOLVED)
    val audioProfile = mutableAudioProfile.asStateFlow()
    private fun observeAudioProfile(pair: PairAddress?) {
        val observed = if (pair != null && facts.permitted() && facts.bonded(pair)) facts.connection(pair) else ProfileConnection.UNRESOLVED
        if (mutableAudioProfile.value != observed) {
            mutableAudioProfile.value = observed
            observer.observe(QualificationEvent(QualificationStep.AUDIO, QualificationPhase.OBSERVED,
                state.value.generation, value = observed.ordinal, flags = 10))
        }
    }
    @Volatile private var latestAudio: PlatformAction? = null
    private fun createAudio() = OrdinaryAudioOperations(audioProfileFactory(), SystemClock::elapsedRealtime) { action ->
        val p = store.snapshot().getOrNull()?.pairs?.get(action.pair)
        AudioAuthorization(facts.bonded(action.pair), facts.permitted(), platformCurrent(action), noCallOrMix(),
            p?.host?.let { it.automationsEnabled && it.disconnectUnworn && (visibility.value || it.fluidifyBackgroundEnabled) } == true,
            state.value.ears?.let { it.generation == state.value.generation && SystemClock.elapsedRealtime() - it.atMs in 0..5_000 && it.value.normalized == EarState.NONE } == true)
    }
    /** Lock order: gate -> platformLock -> audio monitor, never the reverse. OrdinaryAudioOperations
     * delivers completions (record) only after releasing its monitor, so record may take platformLock. */
    private val platformLock = Any()
    private var audio = createAudio()
    private fun cancelPlatform() = synchronized(platformLock) {
        // Lock-free revocation first: a cold callback already inside its live checks holds the audio
        // monitor; it must not reach the platform after this point, and close() then waits for it.
        audio.revoke()
        platformEpoch.incrementAndGet(); latestAudio = null; mutableAudioResult.value = null
        audioDeadline?.cancel(); audioDeadline = null; audio.close(); audio = createAudio()
        detector = PinnedHeadGestureDetector(); gestureContext = null
    }
    private fun platformCurrent(action: PlatformAction): Boolean {
        val d = store.snapshot().getOrNull() ?: return false
        val verified = d.pairs[action.pair]?.verification?.conformanceBuild == buildFingerprint
        return !disposed && gate.platformAuthorization(action.pair, action.consentGeneration) == null && d.selectedPair == action.pair && d.start.enabled && !d.start.suppressUntilExplicitResume &&
            d.start.consentGeneration == action.consentGeneration && state.value.generation == action.sessionGeneration &&
            facts.permitted() && facts.bonded(action.pair) &&
            (if (testPermit != null) testPermit.allowed(visibility.value) && (qualificationAuthority == null || qualificationAuthority.valid(action.pair, action.consentGeneration)) && (runtime.value.setupVerified || airPodsQualificationBuild(context)?.let { audioReceipt?.valid(action.pair, it) } == true) else productConformance() && verified)
    }
    @Suppress("DEPRECATION")
    private fun noCallOrMix(): Boolean = runCatching {
        val manager = context.getSystemService(AudioManager::class.java)
        val c = calls.observe()
        manager != null && manager.mode == AudioManager.MODE_NORMAL && !manager.isBluetoothScoOn &&
            manager.activePlaybackConfigurations.size <= 1 &&
            c.state in setOf(OrdinaryCallState.UNKNOWN, OrdinaryCallState.IDLE) && !c.ambiguous
    }.getOrDefault(false)
    suspend fun requestAudio(operation: AudioOperation, origin: AudioOrigin = AudioOrigin.MANUAL): PlatformResult = withContext(Dispatchers.IO) {
        val d = store.snapshot().getOrNull() ?: return@withContext PlatformResult.Denied(PlatformFailure.NOT_ENTITLED)
        val pair = d.selectedPair ?: return@withContext PlatformResult.Denied(PlatformFailure.NOT_SELECTED_BONDED)
        val action = PlatformAction(pair, state.value.generation, d.start.consentGeneration, SystemClock.elapsedRealtime())
        val epoch = platformEpoch.get()
        val foreground = visibility as? AirPodsForegroundState
        val foregroundEpoch = if (origin == AudioOrigin.MANUAL) foreground?.captureForeground() else null
        val guard = { (origin != AudioOrigin.MANUAL || (if (foreground != null) foregroundEpoch != null && foreground.isCurrentForeground(foregroundEpoch) else testPermit == null && visibility.value)) && platformEpoch.get() == epoch && platformCurrent(action) && (if (origin == AudioOrigin.MANUAL) visibility.value else writable()) }
        // Called from the audio completion outside its monitor, or from here; never under the audio monitor.
        fun record(result: PlatformResult) { processScope.launch { synchronized(platformLock) {
            if (latestAudio === action && guard()) {
                if (result == PlatformResult.Queued && mutableAudioResult.value != null && mutableAudioResult.value != PlatformResult.Queued) return@synchronized
                mutableAudioResult.value = result
                observer.observe(QualificationEvent(QualificationStep.AUDIO, QualificationPhase.UNCONFIRMED,
                    action.sessionGeneration, token = action.consentGeneration, value = operation.ordinal, flags = when(result) {
                        PlatformResult.Queued -> 1; PlatformResult.RequestAccepted -> 2
                        is PlatformResult.SettingsRequired -> 3; is PlatformResult.Denied -> 4
                    }))
                mutableRuntime.value = mutableRuntime.value.copy(failure = "AUDIO_${operation}_${platformCode(result)}")
            }
        } } }
        val result = synchronized(platformLock) {
            val previous = latestAudio
            val previousResult = mutableAudioResult.value
            latestAudio = action
            mutableAudioResult.value = null
            audio.request(action, operation, origin, guard) { record(it) }.also {
                if (it == PlatformResult.Denied(PlatformFailure.BUSY)) { latestAudio = previous; mutableAudioResult.value = previousResult }
            }
        }
        record(result)
        if (result == PlatformResult.Queued) synchronized(platformLock) {
            // Armed only while this action is still current; a Stop/revocation in between already won.
            if (platformEpoch.get() == epoch && latestAudio === action) {
                audioDeadline?.cancel()
                audioDeadline = processScope.launch { delay(5_001); synchronized(platformLock) { if (platformEpoch.get() == epoch && latestAudio === action) audio.cancelPending() } }
            }
        }
        result
    }
    private fun platformCode(result: PlatformResult): String = when (result) {
        PlatformResult.RequestAccepted -> "REQUEST_SUBMITTED_UNCONFIRMED"
        PlatformResult.Queued -> "QUEUED_UNCONFIRMED"
        is PlatformResult.Denied -> "DENIED_${result.reason}"
        is PlatformResult.SettingsRequired -> "SETTINGS_REQUIRED_${result.reason}"
    }
    private fun armTracking() {
        val p = ticket?.address?.let { store.snapshot().getOrNull()?.pairs?.get(it) } ?: return
        if (writable()) repository.setHeadTracking(if (p.headPacketAlternate) HeadPacketFormat.ALTERNATE else HeadPacketFormat.STANDARD,
            p.host.headGestures && p.host.automationsEnabled && (visibility.value || p.host.fluidifyBackgroundEnabled))
    }
    fun tileUnavailableReason(): String? {
        if (!writable()) return "NO_ADMITTED_SESSION"
        val s = state.value
        if (s.mode?.takeIf { it.generation == s.generation } == null) return "NO_CURRENT_MODE"
        return if (s.nextTileListeningMode() != null) null else "MODE_UNAVAILABLE_OR_PENDING"
    }
    fun cycleTile(): Boolean {
        if (tileUnavailableReason() != null) return false
        val next = state.value.nextTileListeningMode() ?: return false
        return setProperty(CommandProperty.MODE, PropertyValue.Number(next.wire))
    }
    private var retainObservationForDisconnect = false
    private val failedTransports = mutableListOf<GuardedTransport>()
    private val failedMedia = mutableListOf<AirPodsMediaPort>()
    // Stop/lifecycle. Revoked ports whose serial stop() is queued on main: guarded by lock, the
    // count mirrored for waiters that hold neither lock nor gate. Declared before init on purpose.
    private val retiringMedia = mutableListOf<AirPodsMediaPort>()
    private val retiringCount = MutableStateFlow(0)
    private val migrationLock = Mutex()
    @Volatile private var migrationReady = false
    private suspend fun migrate(): Boolean = migrationLock.withLock {
        if (!migrationReady) migrationReady = if (testPermit != null) true else AirPodsPreferences(context).migrateInto(store).isSuccess
        migrationReady
    }
    private val buildFingerprint get() = Build.FINGERPRINT

    @Volatile private var disposed = false
    private val receiver: BroadcastReceiver
    init {
        require((testPermit == null) == (scratchStore == null))
        require(qualificationAuthority == null || (dev.lelonio.square.BuildConfig.DEBUG && testPermit != null))
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { processScope.launch {
                if (i?.action == android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                    calls.observe(); detector = PinnedHeadGestureDetector(); gestureContext = null; armTracking()
                } else refresh(StartIngress.RECEIVER)
            } }
        }
        // Bluetooth broadcasts originate from the privileged Bluetooth process, not necessarily system UID.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        processScope.launch { visibility.collect { visible -> withContext(Dispatchers.Main.immediate) { media?.setUiVisible(visible) }
            if (visible && ticket != null) refresh(StartIngress.APP_OPEN, allowAcquisition = false)
            if (!visible && !visibility.value) invalidateAppOpening()
            armTracking()
            if (!visible && (ticket?.purpose == ConnectionPurpose.VERIFICATION_ONLY ||
                (testPermit != null && !testPermit.allowed(false)))) stopControls(SuppressReason.USER_STOP)
        } }
    }

    suspend fun bondedPairs(): List<PairAddress> = withContext(Dispatchers.IO) {
        if (!facts.permitted()) return@withContext emptyList()
        try {
            facts.adapter?.bondedDevices.orEmpty().map { PairAddress(it.address) }
        } catch (_: SecurityException) {
            // Permission can be revoked after permitted(); never expose a partial bonded list.
            emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }
    suspend fun selectPair(address: PairAddress): Result<Unit> = withContext(Dispatchers.IO) {
        if (!migrate()) return@withContext Result.failure(IllegalStateException("MIGRATION_FAILED"))
        // Stop/lifecycle: a live owner is stopped as its own step, so its queued media teardown is
        // confirmed off-gate. The selection below then finds no ticket and never waits for main.
        if (ticket != null && stopControls(SuppressReason.USER_STOP) is GateResult.Denied)
            return@withContext Result.failure(IllegalStateException("CLEANUP_FAILED"))
        synchronized(gate) {
            invalidatePresence()
            if (ticket != null && gate.stop(SuppressReason.USER_STOP) is GateResult.Denied)
                return@synchronized Result.failure<Unit>(IllegalStateException("CLEANUP_FAILED"))
            if (companion.stop() is CompanionResult.Denied)
                return@synchronized Result.failure<Unit>(IllegalStateException("CLEANUP_FAILED"))
            observation = null
            cancelPlatform(); calls.reset()
            store.select(address).onSuccess { companion.explicitSetupSelected() }
        }
    }
    fun setSetupVisible(visible: Boolean) {
        setupVisible = visible
        if (!visible) synchronized(gate) {
            if (pendingExplicitStart?.purpose == ConnectionPurpose.VERIFICATION_ONLY) invalidateExplicitStart()
        }
        if (!visible && ticket?.purpose == ConnectionPurpose.VERIFICATION_ONLY) processScope.launch { stopControls() }
    }
    internal suspend fun beginFunctionalTest(): GateResult = withContext(Dispatchers.IO) {
        val foreground = visibility as? AirPodsForegroundState
        val foregroundEpoch = foreground?.captureForeground()
        fun visibleActionCurrent() = if (foreground != null)
            foregroundEpoch != null && foreground.isCurrentForeground(foregroundEpoch) else visibility.value
        if (testPermit == null || !testPermit.allowed(visibility.value) || disposed || !visibleActionCurrent())
            return@withContext GateResult.Denied(GateReason.SETUP_REQUIRED)
        if (!migrate()) return@withContext GateResult.Denied(GateReason.PERSISTENCE_FAILED)
        val actionContext = currentCoroutineContext()
        val pending = synchronized(gate) {
            actionContext.ensureActive()
            if (disposed || !visibleActionCurrent() || !testPermit.allowed(visibility.value))
                return@withContext GateResult.Denied(GateReason.SETUP_REQUIRED)
            invalidatePresence()
            setupVisible = true
            // Consent belongs to this explicit action, never to the readiness callback.
            val consent = gate.explicitConsent(StartIngress.EXPLICIT_ENABLE)
            if (consent is GateResult.Denied) return@withContext consent
            val data = store.snapshot().getOrNull()
                ?: return@withContext GateResult.Denied(GateReason.PERSISTENCE_FAILED)
            val pair = data.selectedPair ?: return@withContext GateResult.Denied(GateReason.SETUP_REQUIRED)
            PendingFunctionalStart(pair, data.start.consentGeneration) {
                actionContext.isActive && visibleActionCurrent()
            }.also { pendingFunctionalStart = it }
        }
        fun stillCurrent(): Boolean {
            val data = store.snapshot().getOrNull() ?: return false
            return pendingFunctionalStart === pending && !disposed && setupVisible && visibleActionCurrent() &&
                testPermit.allowed(visibility.value) && data.selectedPair == pending.pair &&
                data.start.consentGeneration == pending.consent && data.start.enabled && !data.start.suppressUntilExplicitResume
        }
        try {
            val accepted = synchronized(gate) {
                actionContext.ensureActive()
                if (!stillCurrent()) false
                else if (facts.permitted() && facts.bonded(pending.pair) && facts.connection(pending.pair) == ProfileConnection.UNRESOLVED) {
                    // Completion is only a wake-up. Duplicate/late callbacks cannot admit or consent.
                    functionalReadiness = facts.whenProfileReady { pending.ready.complete(true) }
                    functionalReadiness != null // refusal wins even if a faulty request called back inline
                } else { pending.ready.complete(true); true }
            }
            if (!accepted || withTimeoutOrNull(5_000) { pending.ready.await() } != true)
                return@withContext GateResult.Denied(GateReason.NOT_CONNECTED)
            synchronized(gate) {
                actionContext.ensureActive()
                if (!stillCurrent()) GateResult.Denied(GateReason.STALE_GENERATION)
                // Re-read all facts and run C4. A terminal unresolved callback must not request again.
                else admit(StartIngress.EXPLICIT_ENABLE, ConnectionPurpose.FUNCTIONAL_TEST, prepareProfile = false)
            }
        } finally {
            synchronized(gate) {
                if (pendingFunctionalStart === pending) {
                    pendingFunctionalStart = null
                    functionalReadiness?.close(); functionalReadiness = null
                }
            }
        }
    }
    internal fun functionalIngressCurrent(): Boolean = isFunctionalTest && current(ticket) &&
        synchronized(lock) { transportLease?.released == false && AirPodsServiceOwnerRegistry.resolve() === this }
    internal fun functionalUiAuthorized(): Boolean = functionalIngressCurrent() && runtime.value.setupVerified &&
        state.value.stage == AacpStage.SUBSCRIBED && state.value.identityReceived?.let {
            it.generation == state.value.generation && it.value.recognized
        } == true
    internal fun audioSetupReceipt(): AirPodsAudioSetupReceipt? {
        if (!functionalUiAuthorized()) return null
        val a = ticket ?: return null
        val build = airPodsQualificationBuild(context) ?: return null
        val observed = observedSetupReceipt
        if (observed != null && observed.observedGeneration == state.value.generation) return observed
        val observedAt = observedSetupAt ?: return null
        return AirPodsAudioSetupReceipt(a.address, build, state.value.generation,
            observedAt, SystemClock::elapsedRealtime).also { observedSetupReceipt = it }
    }
    /** Explicit manual platform-only grant. No socket, FGS, setupVerified or product verification. */
    internal suspend fun beginFunctionalAudio(receipt: AirPodsAudioSetupReceipt): GateResult = withContext(Dispatchers.IO) {
        if (testPermit == null || disposed || !visibility.value || !testPermit.allowed(true) || !migrate())
            return@withContext GateResult.Denied(GateReason.SETUP_REQUIRED)
        val pair = store.snapshot().getOrNull()?.selectedPair
            ?: return@withContext GateResult.Denied(GateReason.SETUP_REQUIRED)
        val build = airPodsQualificationBuild(context) ?: return@withContext GateResult.Denied(GateReason.SETUP_REQUIRED)
        if (!receipt.valid(pair, build) || !facts.permitted() || !facts.bonded(pair))
            return@withContext GateResult.Denied(GateReason.SETUP_REQUIRED)
        val result = gate.explicitConsent(StartIngress.EXPLICIT_RESUME)
        if (result is GateResult.Resumed) {
            audioReceipt = receipt
            observer.observe(QualificationEvent(QualificationStep.OWNER, QualificationPhase.OBSERVED,
                receipt.observedGeneration, sequence = result.generation, token = receipt.observedAt, flags = 2))
            facts.prepare()
            observeAudioProfile(pair)
        }
        result
    }
    /** Initial explicit action seals a separate finite grant. Restore only reconciles/registers;
     * it never clears suppression or acquires. A fresh C3/receiver event must pass C4 again. */
    internal suspend fun beginQualificationTest(explicit: Boolean): GateResult = withContext(Dispatchers.IO) {
        val q = qualificationAuthority ?: return@withContext GateResult.Denied(GateReason.SETUP_REQUIRED)
        if (testPermit?.allowed(visibility.value) != true || disposed || !migrate() || (explicit && !visibility.value))
            return@withContext GateResult.Denied(GateReason.SETUP_REQUIRED)
        synchronized(gate) {
            if (explicit) {
                val result = gate.explicitConsent(StartIngress.EXPLICIT_RESUME)
                if (result !is GateResult.Resumed) return@synchronized result
                val pair = store.snapshot().getOrNull()?.selectedPair ?: return@synchronized GateResult.Denied(GateReason.SETUP_REQUIRED)
                if (!q.sealExplicitConsent(pair, result.generation)) { gate.stop(SuppressReason.REVOKED); return@synchronized GateResult.Denied(GateReason.PERSISTENCE_FAILED) }
            }
            val d = store.snapshot().getOrNull() ?: return@synchronized GateResult.Denied(GateReason.PERSISTENCE_FAILED)
            val pair = d.selectedPair ?: return@synchronized GateResult.Denied(GateReason.SETUP_REQUIRED)
            val denied = gate.platformAuthorization(pair, d.start.consentGeneration)
            if (denied != null || !q.valid(pair, d.start.consentGeneration)) {
                gate.stop(store.snapshot().getOrNull()?.start?.suppressReason ?: SuppressReason.REVOKED)
                return@synchronized GateResult.Denied(denied ?: GateReason.SETUP_REQUIRED)
            }
            registerObservation()
            if (explicit) admit(StartIngress.EXPLICIT_RESUME, ConnectionPurpose.QUALIFICATION_TEST)
            else GateResult.Resumed(d.start.consentGeneration) // registration only, not an acquisition receipt
        }
    }
    internal suspend fun disposeFunctionalTest() {
        check(testPermit != null)
        testPermit.revoke()
        audioReceipt = null
        try { withContext(NonCancellable) { stopControls() } }
        finally {
            disposed = true
            runCatching { context.unregisterReceiver(receiver) }
            diagnostics.stopCollection()
            synchronized(platformLock) { audio.close() }
            processScope.cancel()
        }
    }
    suspend fun beginVerification(): GateResult = withContext(Dispatchers.IO) {
        if (!migrate()) return@withContext GateResult.Denied(GateReason.PERSISTENCE_FAILED)
        if (!setupVisible || !visibility.value) return@withContext GateResult.Denied(GateReason.SETUP_REQUIRED)
        explicitProfileAdmission(StartIngress.EXPLICIT_ENABLE, ConnectionPurpose.VERIFICATION_ONLY)
    }
    suspend fun enableOrResume(): GateResult = withContext(Dispatchers.IO) {
        if (!migrate()) return@withContext GateResult.Denied(GateReason.PERSISTENCE_FAILED)
        if (!visibility.value || !productConformance()) return@withContext GateResult.Denied(GateReason.SETUP_REQUIRED)
        explicitProfileAdmission(StartIngress.EXPLICIT_RESUME, ConnectionPurpose.PRODUCT)
    }
    /** One explicit action may await the Android profile once. Its callback grants nothing:
     * foreground, pair, consent, permissions and connection are checked again at admission.
     * No poll, automatic retry, extra consent or background acquisition is introduced. */
    private suspend fun explicitProfileAdmission(ingress: StartIngress, purpose: ConnectionPurpose): GateResult {
        val foreground = visibility as? AirPodsForegroundState
        val foregroundEpoch = foreground?.captureForeground()
        val actionContext = currentCoroutineContext()
        fun visibleActionCurrent() = actionContext.isActive &&
            (if (foreground != null) foregroundEpoch != null && foreground.isCurrentForeground(foregroundEpoch)
             else visibility.value)
        val pair = store.snapshot().getOrNull()?.selectedPair ?: return GateResult.Denied(GateReason.SETUP_REQUIRED)
        invalidatePresence()
        val consent = explicitConsentSettled(ingress) {
            visibleActionCurrent() && store.snapshot().getOrNull()?.selectedPair == pair &&
                (purpose != ConnectionPurpose.VERIFICATION_ONLY || setupVisible) &&
                (purpose != ConnectionPurpose.PRODUCT || productConformance())
        }
        if (consent !is GateResult.Resumed) return consent
        val pending = synchronized(gate) {
            val data = store.snapshot().getOrNull() ?: return GateResult.Denied(GateReason.PERSISTENCE_FAILED)
            if (!visibleActionCurrent() || data.selectedPair != pair || !data.start.enabled ||
                data.start.suppressUntilExplicitResume || data.start.consentGeneration != consent.generation)
                return GateResult.Denied(GateReason.STALE_GENERATION)
            PendingExplicitStart(pair, consent.generation, presenceEpoch, purpose, ::visibleActionCurrent)
                .also { invalidateExplicitStart(); pendingExplicitStart = it }
        }
        try {
            val early = synchronized(gate) {
                when {
                    !explicitStartCurrent(pending) -> GateReason.STALE_GENERATION
                    !facts.permitted() -> GateReason.PERMISSION_DENIED
                    !facts.bonded(pair) -> GateReason.NOT_BONDED
                    facts.connection(pair) == ProfileConnection.UNRESOLVED -> {
                        explicitReadiness = facts.whenProfileReady { pending.ready.complete(true) }
                        if (explicitReadiness == null) GateReason.NOT_CONNECTED else null
                    }
                    else -> { pending.ready.complete(true); null }
                }
            }
            val result = if (early != null) GateResult.Denied(early)
            else if (withTimeoutOrNull(5_000) { pending.ready.await() } != true)
                GateResult.Denied(GateReason.NOT_CONNECTED)
            else synchronized(gate) {
                actionContext.ensureActive()
                if (!explicitStartCurrent(pending)) GateResult.Denied(GateReason.STALE_GENERATION)
                else {
                    if (purpose == ConnectionPurpose.PRODUCT) registerObservation()
                    startingExplicitStart = pending
                    try { admit(ingress, purpose, prepareProfile = false) }
                    finally { startingExplicitStart = null }
                }
            }
            synchronized(gate) {
                // A stale completion must not replace a later Stop or another action's diagnosis.
                if (pendingExplicitStart === pending) {
                    if (result is GateResult.Denied) fail("ADMISSION_${result.reason}")
                    else if (result is GateResult.Started && mutableRuntime.value.failure?.let {
                            it.startsWith("ADMISSION_") || it.startsWith("COMPANION_") } == true)
                        mutableRuntime.value = mutableRuntime.value.copy(failure = null)
                }
            }
            return result
        } finally {
            synchronized(gate) { if (pendingExplicitStart === pending) invalidateExplicitStart() }
        }
    }
    suspend fun connect(): GateResult = withContext(Dispatchers.IO) {
        admit(StartIngress.TILE_USER_ACTION, ConnectionPurpose.PRODUCT)
    }
    /** A real MainActivity opening restores only an already opted-in, verified product session.
     * Does not call explicitConsent, register presence, retry acquisition or restore media state.
     * Activity-result returns use permissionsChanged(), and Boolean visibility stays passive. */
    suspend fun appOpened() {
        val foreground = visibility as? AirPodsForegroundState ?: return
        val epoch = foreground.captureForeground() ?: return
        withContext(Dispatchers.IO) {
            if (disposed || testPermit != null || !migrate() || !foreground.isCurrentForeground(epoch)) return@withContext
            refresh(StartIngress.APP_OPEN, allowAcquisition = false)
            val pending = synchronized(gate) {
                invalidateAppOpening()
                if (ticket != null || disposed || !foreground.isCurrentForeground(epoch)) return@withContext
                val data = store.snapshot().getOrNull() ?: return@withContext
                val pair = data.selectedPair ?: return@withContext
                PendingAppOpening(pair, data.start.consentGeneration, foreground, epoch).also { pendingAppOpening = it }
            }
            val actionContext = currentCoroutineContext()
            try {
                val accepted = synchronized(gate) {
                    if (!appOpeningCurrent(pending) || !productConformance() ||
                        gate.platformAuthorization(pending.pair, pending.consent) != null ||
                        store.snapshot().getOrNull()?.pairs?.get(pending.pair)?.verification?.conformanceBuild != buildFingerprint ||
                        !facts.permitted() || !facts.bonded(pending.pair)) false
                    else if (facts.connection(pending.pair) == ProfileConnection.UNRESOLVED) {
                        // One profile completion belongs to this opening. Refusal/timeout consumes
                        // it; late callbacks cannot become a receiver or a new user action.
                        openingReadiness = facts.whenProfileReady { pending.ready.complete(true) }
                        openingReadiness != null
                    } else { pending.ready.complete(true); true }
                }
                if (accepted && withTimeoutOrNull(5_000) { pending.ready.await() } == true) synchronized(gate) {
                    actionContext.ensureActive()
                    if (ticket == null && appOpeningCurrent(pending)) {
                        startingAppOpening = pending
                        try { admit(StartIngress.APP_OPEN, ConnectionPurpose.PRODUCT, prepareProfile = false) }
                        finally { startingAppOpening = null }
                    }
                }
            } finally {
                synchronized(gate) { if (pendingAppOpening === pending) invalidateAppOpening() }
            }
        }
    }
    suspend fun stopControls(reason: SuppressReason = SuppressReason.USER_STOP): GateResult = withContext(Dispatchers.IO) { settled(synchronized(gate) {
        if (reason in setOf(SuppressReason.REVOKED, SuppressReason.UNPAIRED)) { observedSetupReceipt?.revoke(); audioReceipt?.revoke() }
        invalidatePresence(); cancelPlatform(); gate.stop(reason)
    }) }
    suspend fun forget(): GateResult = withContext(Dispatchers.IO) {
        settled(synchronized(gate) { unpairSelected() })
    }
    private fun unpairSelected(): GateResult {
        val data = store.snapshot().getOrNull() ?: return GateResult.Denied(GateReason.PERSISTENCE_FAILED)
        val address = data.selectedPair ?: return GateResult.Stopped(data.start.consentGeneration)
        invalidatePresence(); cancelPlatform()
        val target = companion.captureCleanup(address)
        if (store.forgetWithCleanup(address, target).isFailure) {
            // Stop locally even if disk is unavailable, retaining the original destination.
            gate.stop(SuppressReason.USER_STOP)
            return GateResult.Denied(GateReason.PERSISTENCE_FAILED)
        }
        val stopped = gate.stop(SuppressReason.UNPAIRED)
        val cleanup = performCleanup(target)
        if (cleanup !is CompanionCleanupResult.Removed && cleanup !is CompanionCleanupResult.AlreadyAbsent) {
            fail("COMPANION_CLEANUP_FAILED")
            return GateResult.Denied(GateReason.CLEANUP_FAILED)
        }
        observation = null
        return stopped
    }

    /** Explicit UI action, independent from forget(). Never stop/admit the current selection. */
    suspend fun retryPendingCleanup(): CompanionCleanupResult = withContext(Dispatchers.IO) {
        synchronized(gate) {
            val targets = store.snapshot().getOrNull()?.pendingCleanup
                ?: return@synchronized CompanionCleanupResult.PersistenceFailed
            if (targets.isEmpty()) return@synchronized CompanionCleanupResult.NoPending
            CompanionCleanupResult.Batch(targets.associate { it.key to performCleanup(it) })
        }
    }
    private fun performCleanup(target: PendingCompanionCleanup): CompanionCleanupResult {
        return synchronized(store) {
            val data = store.snapshot().getOrNull() ?: return CompanionCleanupResult.PersistenceFailed
            if (target !in data.pendingCleanup || data.selectedPair == target.address) return CompanionCleanupResult.IdentityUnresolved
            val result = companion.cleanupPending(target) {
                store.snapshot().getOrNull()?.let { target in it.pendingCleanup && it.selectedPair != target.address } == true
            }
            if (result is CompanionCleanupResult.Removed || result is CompanionCleanupResult.AlreadyAbsent) {
                if (store.completeCleanup(target).isFailure) return CompanionCleanupResult.PersistenceFailed
            }
            result
        }
    }
    suspend fun configure(update: (PairSettings) -> PairSettings): Result<Unit> = withContext(Dispatchers.IO) {
        val address = store.snapshot().getOrThrow().selectedPair ?: return@withContext Result.failure(IllegalStateException("NO_PAIR"))
        invalidatePresence()
        cancelPlatform()
        store.configure(address, update).onSuccess {
            val pair = store.snapshot().getOrThrow().pairs.getValue(address)
            media?.let { currentMedia ->
                withContext(Dispatchers.Main.immediate) {
                    if (media === currentMedia) currentMedia.updateSettings(pair.host)
                }
            }
            detector = PinnedHeadGestureDetector()
            armTracking()
        }
    }
    fun setProperty(property: CommandProperty, value: PropertyValue): Boolean = property != CommandProperty.STEM_CONFIG && writable() && repository.setProperty(property, value)
    fun rename(name: String): Boolean = writable() && repository.rename(name)
    /** Reissues the verified bootstrap subscription; receipt of information is not guaranteed. */
    fun requestInformation(): Boolean {
        if (!writable() || state.value.stage != AacpStage.SUBSCRIBED) return false
        repository.requestInformation()
        return true
    }
    fun setEq(value: PropertyValue.Eq): Boolean = writable() && repository.setEq(value)
    fun setVolume(value: Int): Boolean {
        // Capture the original action before mainCall; a fresh writable ticket cannot replace it.
        // Boolean StateFlow collection can conflate hide/show, so only a source-side fence is valid.
        val admitted = ticket ?: return false
        val generation = state.value.generation
        val epoch = platformEpoch.get()
        val port = media ?: return false
        val foreground = visibility as? AirPodsForegroundState ?: return false
        val foregroundEpoch = foreground.captureForeground() ?: return false
        val stillCurrent = {
            foreground.isCurrentForeground(foregroundEpoch) && platformEpoch.get() == epoch &&
                ticket === admitted && state.value.generation == generation && media === port &&
                current(admitted) && writable()
        }
        if (!stillCurrent()) return false
        return mainCall { stillCurrent() && port.setUserVolume(value, stillCurrent) }
    }
    private data class TrackingAvailability(val pair: PairSettings?, val reason: TrackingBlockReason?)

    /** Observes authorization only; repository capability, live session and format checks remain final. */
    fun trackingUnavailableReason(enabled: Boolean): TrackingBlockReason? = synchronized(gate) {
        trackingAvailability(enabled).reason
    }

    private fun trackingAvailability(enabled: Boolean): TrackingAvailability {
        val p = ticket?.address?.let { store.snapshot().getOrNull()?.pairs?.get(it) }
        val reason = when {
            p == null || !writable() -> TrackingBlockReason.NO_ADMITTED_SESSION
            !enabled -> null // Stop remains authorized when preferences have been revoked.
            !p.host.headGestures -> TrackingBlockReason.HEAD_GESTURES_DISABLED
            !p.host.automationsEnabled -> TrackingBlockReason.AUTOMATIONS_DISABLED
            !visibility.value && !p.host.fluidifyBackgroundEnabled -> TrackingBlockReason.BACKGROUND_DISABLED
            else -> null
        }
        return TrackingAvailability(p, reason)
    }

    fun setTracking(enabled: Boolean): Boolean = synchronized(gate) {
        val availability = trackingAvailability(enabled)
        if (availability.reason != null) return@synchronized false
        val p = availability.pair ?: return@synchronized false
        repository.setHeadTracking(if (p.headPacketAlternate) HeadPacketFormat.ALTERNATE else HeadPacketFormat.STANDARD, enabled)
    }
    /** Explicit local intent; one shared STEM command, no replay on reconnect.
     * Null leaves that side unchanged, read under the same admission lock as persistence. */
    suspend fun setLongPress(left: LongPressAction? = null, right: LongPressAction? = null): Boolean {
        val admitted = ticket
        val generation = state.value.generation
        return withContext(Dispatchers.IO) { synchronized(gate) {
            if (admitted == null || ticket !== admitted || state.value.generation != generation ||
                !writable() || (left == null && right == null)) return@synchronized false
            val before = state.value
            // Reject duplicates before touching preferences, including direct concurrent calls.
            if (CommandProperty.STEM_CONFIG in before.pending) return@synchronized false
            val mask = (before.propertyObserved(CommandProperty.STEM_CONFIG)?.value as? PropertyValue.Number)?.value
                ?: return@synchronized false
            val address = admitted.address
            val previous = store.snapshot().getOrNull()?.pairs?.get(address) ?: return@synchronized false
            val newLeft = left ?: previous.leftLongPress
            val newRight = right ?: previous.rightLongPress
            val custom = newLeft != LongPressAction.CYCLE_NOISE_CONTROL_MODES ||
                newRight != LongPressAction.CYCLE_NOISE_CONTROL_MODES
            val wanted = PropertyValue.Number((mask and 7) or (if (custom) 8 else 0))
            if (!before.canAttemptWrite(CommandProperty.STEM_CONFIG, wanted)) return@synchronized false
            if (store.configure(address) { it.copy(leftLongPress = newLeft, rightLongPress = newRight) }.isFailure)
                return@synchronized false
            if (ticket !== admitted || state.value.generation != generation || !writable() ||
                !repository.setProperty(CommandProperty.STEM_CONFIG, wanted)) {
                // Only immediate rejection rolls back. Async failure keeps explicitly labelled intent.
                store.configure(address) { it.copy(leftLongPress = previous.leftLongPress, rightLongPress = previous.rightLongPress) }
                return@synchronized false
            }
            true
        } }
    }
    private fun handleLongPress(sample: StemSample) {
        if (!writable() || sample.press != 8 || sample.bud !in 1..2) return
        val mask = (state.value.propertyObserved(CommandProperty.STEM_CONFIG)?.value as? PropertyValue.Number)?.value ?: return
        if ((mask and 8) == 0) return // hardware default already handled it
        val a = ticket ?: return
        val pair = store.snapshot().getOrNull()?.pairs?.get(a.address) ?: return
        val action = if (sample.bud == 1) pair.leftLongPress else pair.rightLongPress
        if (action == LongPressAction.DIGITAL_ASSISTANT) {
            val d = store.snapshot().getOrNull() ?: return
            val request = PlatformAction(a.address, state.value.generation, d.start.consentGeneration, SystemClock.elapsedRealtime())
            val epoch = platformEpoch.get()
            val operation = OrdinaryAssistantOperation(assistantLaunch, SystemClock::elapsedRealtime) {
                AssistantAuthorization(facts.bonded(a.address), facts.permitted(), writable(), noCallOrMix(), visibility.value,
                    pair.host.automationsEnabled && pair.host.fluidifyBackgroundEnabled,
                    hasOwnCompanionAssociation(context, a.address), true)
            }
            processScope.launch(Dispatchers.Main) {
                val guard = { platformEpoch.get() == epoch && current(a) && platformCurrent(request) }
                synchronized(platformLock) {
                    val result = operation.request(request, guard)
                    if (guard()) fail("ASSISTANT_${platformCode(result)}")
                }
            }
        } else {
            val cycle = (state.value.propertyObserved(CommandProperty.LISTENING_MODE_CONFIGS)?.value as? PropertyValue.Number)?.value ?: return
            val modes = listOf(ListeningMode.OFF, ListeningMode.ANC, ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE).filter { (cycle and (1 shl (it.wire - 1))) != 0 }
            val current = state.value.mode?.takeIf { it.generation == state.value.generation }?.value ?: return
            if (modes.size < 2 || current !in modes) return
            repository.setProperty(CommandProperty.MODE, PropertyValue.Number(modes[(modes.indexOf(current) + 1) % modes.size].wire))
        }
    }
    fun requestAudioConnection(): Intent = facts.audioSettingsIntent() // UI launches explicitly
    fun sanitizedDiagnostics(): String = "stage=${state.value.stage};active=${runtime.value.active};verification=${runtime.value.setupVerified};media=${runtime.value.mediaAvailable};failure=${runtime.value.failure ?: "NONE"}"
    private fun writable(): Boolean = current(ticket) && when (ticket?.purpose) {
        ConnectionPurpose.PRODUCT -> productConformance()
        ConnectionPurpose.FUNCTIONAL_TEST, ConnectionPurpose.QUALIFICATION_TEST -> runtime.value.setupVerified && testPermit?.allowed(visibility.value) == true
        else -> false
    }
    private fun current(a: ConnectionAdmission?): Boolean {
        if (disposed || a == null || ticket !== a) return false
        val s = store.snapshot().getOrNull() ?: return false
        return s.start.enabled && !s.start.suppressUntilExplicitResume && s.start.consentGeneration == a.expectedConsentGeneration &&
            s.selectedPair == a.address && facts.permitted() && facts.bonded(a.address) && facts.connected(a.address) &&
            (when (a.purpose) {
                ConnectionPurpose.VERIFICATION_ONLY -> setupVisible && visibility.value
                ConnectionPurpose.FUNCTIONAL_TEST -> testPermit?.allowed(visibility.value) == true
                ConnectionPurpose.QUALIFICATION_TEST -> testPermit?.allowed(visibility.value) == true &&
                    qualificationAuthority?.valid(a.address, a.expectedConsentGeneration) == true
                ConnectionPurpose.PRODUCT -> productConformance()
            })
    }
    private fun admit(ingress: StartIngress, purpose: ConnectionPurpose, prepareProfile: Boolean = true): GateResult {
        if (!migrationReady) return GateResult.Denied(GateReason.PERSISTENCE_FAILED)
        val data = store.snapshot().getOrElse { return GateResult.Denied(GateReason.PERSISTENCE_FAILED) }
        val address = data.selectedPair ?: return GateResult.Denied(GateReason.SETUP_REQUIRED)
        if (prepareProfile) facts.prepare()
        val connected = facts.connected(address)
        val id = lifetimes.observe(address, connected) ?: "disconnected"
        val backgroundAllowed = ingress == StartIngress.COMPANION && observation != null &&
            data.pairs[address]?.host?.fluidifyBackgroundEnabled == true &&
            Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(android.Manifest.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return gate.attempt(ingress, ConnectionAdmission(address, id, facts.bonded(address), facts.permitted(), connected,
            visibility.value || backgroundAllowed, buildFingerprint, data.start.consentGeneration, purpose,
            setupVisible && visibility.value, if (testPermit != null) false else productConformance()),
            acquisitionCurrent = { startingAppOpening?.let(::appOpeningCurrent) != false &&
                startingExplicitStart?.let(::explicitStartCurrent) != false })
    }
    internal suspend fun refresh(ingress: StartIngress, allowAcquisition: Boolean = true) { synchronized(gate) {
        if (!migrationReady || disposed) return@synchronized
        val selected = store.snapshot().getOrNull()?.selectedPair
        observeAudioProfile(selected)
        if (!facts.permitted()) {
            observedSetupReceipt?.revoke(); audioReceipt?.revoke()
            invalidatePresence(); gate.stop(SuppressReason.REVOKED); return@synchronized
        }
        if (selected != null && facts.bond(selected) == BondFact.NONE) {
            observedSetupReceipt?.revoke(); audioReceipt?.revoke()
            unpairSelected(); return@synchronized
        }
        val a = ticket
        if (a != null && !current(a)) {
            if (!facts.permitted()) gate.stop(SuppressReason.REVOKED)
            else {
                lifetimes.observe(a.address, false)
                synchronized(gate) {
                    retainObservationForDisconnect = a.purpose in setOf(ConnectionPurpose.PRODUCT, ConnectionPurpose.QUALIFICATION_TEST)
                    try { gate.disconnected(a.connectionId, a.expectedConsentGeneration) } finally { retainObservationForDisconnect = false }
                }
            }
            return@synchronized
        }
        if (allowAcquisition && a == null && (testPermit == null || qualificationAuthority != null)) {
            val result = admit(ingress, if (qualificationAuthority != null) ConnectionPurpose.QUALIFICATION_TEST else ConnectionPurpose.PRODUCT)
            if (result is GateResult.Denied && result.reason in setOf(GateReason.SUPPRESSED, GateReason.HISTORY_FAILED, GateReason.PERSISTENCE_FAILED, GateReason.DISABLED)) {
                if (companion.stop() !is CompanionResult.Denied) observation = null
            }
        }
        Unit
    }
        // Stop/lifecycle: a disconnect/revocation above only queued the media teardown on main.
        // The worker concludes it here, outside the gate; never from the main thread itself.
        if (gate.cleanupUnconfirmed && Looper.myLooper() != Looper.getMainLooper()) settleMediaRetirement()
    }
    private fun registerObservation() { synchronized(gate) {
        val d = store.snapshot().getOrNull() ?: return
        val a = d.selectedPair ?: return
        if (qualificationAuthority != null && (testPermit?.allowed(visibility.value) != true || gate.platformAuthorization(a, d.start.consentGeneration) != null ||
                !qualificationAuthority.valid(a, d.start.consentGeneration))) return
        observer.observe(QualificationEvent(QualificationStep.PRESENCE, QualificationPhase.REQUESTED, token = d.start.consentGeneration))
        val result = companion.register(CompanionAdmission(a, d.start.consentGeneration, d.start.enabled && !d.start.suppressUntilExplicitResume))
        observation = (result as? CompanionResult.Registered)?.registration
        observer.observe(QualificationEvent(QualificationStep.PRESENCE,
            if (observation != null) QualificationPhase.COMPLETED else QualificationPhase.REJECTED, token = d.start.consentGeneration))
        if (observation != null && qualificationAuthority?.sealObservation(observation) == false) {
            gate.stop(SuppressReason.REVOKED); fail("QUALIFICATION_OBSERVATION_PERSIST_FAILED"); return
        }
        if (result is CompanionResult.Denied) fail("COMPANION_${result.reason}")
    } }
    internal fun presence(address: String?, associationId: Int?) { processScope.launch {
        observer.observe(QualificationEvent(QualificationStep.PRESENCE, QualificationPhase.UNCONFIRMED, flags = 20))
        if (!migrate()) return@launch
        synchronized(gate) {
            if (disposed) return@synchronized
            if (observation == null) {
                val selected = store.snapshot().getOrNull()?.selectedPair ?: return@synchronized
                val own = runCatching { presenceRegistry.ownAssociations() }.getOrDefault(emptyList())
                    .singleOrNull { it.packageName == context.packageName && it.address == selected && (associationId == null || associationId == it.id) } ?: return@synchronized
                if (address != null && !own.address.value.equals(address, true)) return@synchronized
                registerObservation()
            }
            val r = observation ?: return@synchronized
            val pair = address?.let { runCatching { PairAddress(it) }.getOrNull() } ?: r.association.address
            val data = store.snapshot().getOrNull() ?: return@synchronized
            if (data.selectedPair != pair || !data.start.enabled || data.start.suppressUntilExplicitResume) return@synchronized
            // Validate the original C3 callback even while the profile is unresolved.
            if (companion.validateCallback(r.callbackGeneration, pair, associationId ?: r.association.id) !is CompanionResult.Trigger) return@synchronized
            observer.observe(QualificationEvent(QualificationStep.PRESENCE, QualificationPhase.OBSERVED,
                state.value.generation, token = data.start.consentGeneration, flags = 21))
            val p = PendingPresence(r, pair, associationId ?: r.association.id, data.start.consentGeneration, presenceEpoch)
            if (facts.connection(pair) == ProfileConnection.UNRESOLVED) {
                if (pendingPresence == p) return@synchronized
                readiness?.close(); pendingPresence = p
                readiness = facts.whenProfileReady { processScope.launch { synchronized(gate) readyCallback@ {
                    if (pendingPresence != p) return@readyCallback
                    pendingPresence = null; readiness?.close(); readiness = null
                    resumePresence(p)
                } } }
                if (readiness == null) pendingPresence = null
            } else resumePresence(p)
        }
    } }
    private fun resumePresence(p: PendingPresence) {
        val d = store.snapshot().getOrNull() ?: return
        if (disposed || p.epoch != presenceEpoch || observation != p.registration || d.selectedPair != p.pair ||
            d.start.consentGeneration != p.consent || !d.start.enabled || d.start.suppressUntilExplicitResume) return
        if (companion.appeared(p.registration.callbackGeneration, p.pair, p.associationId, facts.connected(p.pair)) !is CompanionResult.Trigger) return
        // admit rechecks current connection, permission, eligibility and C4 before FGS/factory.
        admit(StartIngress.COMPANION, if (qualificationAuthority != null) ConnectionPurpose.QUALIFICATION_TEST else ConnectionPurpose.PRODUCT)
    }
    private fun startOwner(admission: ConnectionAdmission): Boolean {
        synchronized(lock) {
            if (startingAppOpening?.let { !appOpeningCurrent(it) } == true) return false
            if (startingExplicitStart?.let { !explicitStartCurrent(it) } == true) return false
            if (admission.purpose == ConnectionPurpose.FUNCTIONAL_TEST && !functionalStartAuthorized(admission)) return false
            if (ticket != null) return false
            val reserved = AirPodsTransportOwnership.process.reserve(if (testPermit == null) AirPodsTransportOwnership.Owner.PRODUCTION else AirPodsTransportOwnership.Owner.DIAGNOSTIC)
                ?: run { fail("TRANSPORT_OWNER_BUSY"); return false }
            transportLease = reserved
            ticket = admission
            check(AirPodsServiceOwnerRegistry.claim(this, reserved))
            observer.observe(QualificationEvent(QualificationStep.OWNER, QualificationPhase.REQUESTED,
                state.value.generation, sequence = admission.expectedConsentGeneration, token = reserved.generation))
        }
        return try {
            if (startingAppOpening?.let { !appOpeningCurrent(it) } == true) return false
            if (startingExplicitStart?.let { !explicitStartCurrent(it) } == true) return false
            if (admission.purpose == ConnectionPurpose.FUNCTIONAL_TEST && !functionalStartAuthorized(admission)) return false
            context.startForegroundService(Intent(context, AirPodsControlService::class.java)); true
        }
        catch (_: Exception) { fail("FGS_START_DENIED"); false }
    }
    internal val serviceStopAction: String? get() = synchronized(lock) { transportLease?.let { "dev.lelonio.square.airpods.STOP.${it.generation}" } }
    internal fun hasAdmission(): Boolean = current(ticket)
    /** The production collector and host regression use the same bootstrap decisions.
     * A stage notification cannot acquire an owner or bypass the current admission. */
    internal fun advanceBootstrap(stage: AacpStage) {
        if (!current(ticket) || state.value.stage != stage) return
        when (stage) {
            AacpStage.CONSTRUCTED -> repository.connect()
            AacpStage.CONNECTED -> repository.bootstrap()
            else -> Unit
        }
    }
    suspend fun permissionsChanged() = withContext(Dispatchers.IO) { invalidatePresence(); cancelPlatform(); calls.observe(); refresh(StartIngress.APP_OPEN, allowAcquisition = false); if (qualificationAuthority != null) registerObservation(); armTracking() }
    internal fun attach(host: AirPodsControlService): Boolean {
        val a = ticket ?: return false
        if (!current(a)) return false
        synchronized(lock) {
            if (service != null) return service === host
            val lease = transportLease ?: return false
            if (lease.released) return false
            service = host
            val handler = CoroutineExceptionHandler { _, _ -> fail("OWNER_WORKER_FAILED"); processScope.launch { stopIfCurrent(a, SuppressReason.START_FAILED) } }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler); sessionScope = scope
            // Same connection's latest live ear observation, confined to main. Bootstrap may
            // receive it before the media port exists; it seeds a baseline, never an action.
            var bootstrapEar: Received<AacpEvent>? = null
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var previousEar: EarState? = null
                var earEpoch = platformEpoch.get()
                var lastHeadAt = 0L
                repository.events.collect { event ->
                    if (current(a) && event.generation == state.value.generation && a.purpose != ConnectionPurpose.VERIFICATION_ONLY) {
                        withContext(Dispatchers.Main.immediate) { if (current(a)) {
                            if (event.value is AacpEvent.Ear) bootstrapEar = event
                            media?.onEvent(event)
                        } }
                        if (event.value is AacpEvent.Stem && SystemClock.elapsedRealtime() - event.atMs in 0..1_000) handleLongPress((event.value as AacpEvent.Stem).sample)
                        if (event.value is AacpEvent.Ear) {
                            if (earEpoch != platformEpoch.get()) { previousEar = null; earEpoch = platformEpoch.get() }
                            val normalized = (event.value as AacpEvent.Ear).sample.normalized
                            val removal = previousEar != null && previousEar != EarState.NONE && previousEar != EarState.UNKNOWN && normalized == EarState.NONE
                            previousEar = normalized
                            val cfg = store.snapshot().getOrNull()?.pairs?.get(a.address)?.host
                            if (removal && SystemClock.elapsedRealtime() - event.atMs in 0..5_000 && cfg?.automationsEnabled == true && cfg.disconnectUnworn && (visibility.value || cfg.fluidifyBackgroundEnabled) &&
                                (event.value as AacpEvent.Ear).sample.normalized == dev.lelonio.square.airpods.protocol.EarState.NONE) {
                                requestAudio(AudioOperation.DISCONNECT, AudioOrigin.UNWORN)
                            }
                        }
                        val p = store.snapshot().getOrNull()?.pairs?.get(a.address)
                        if (event.value is AacpEvent.Head && p?.host?.headGestures == true && p.host.automationsEnabled && (visibility.value || p.host.fluidifyBackgroundEnabled) && state.value.activePacketFormat != null) {
                            if (SystemClock.elapsedRealtime() - event.atMs !in 0..1_000) return@collect
                            val headEpoch = platformEpoch.get()
                            if (event.atMs - lastHeadAt !in 0..1_000) { detector = PinnedHeadGestureDetector(); gestureContext = null }
                            lastHeadAt = event.atMs
                            val call = calls.observe()
                            val contextKey = Triple(calls.epoch, state.value.generation, mainCall { media?.gestureContext() })
                            if (gestureContext != contextKey) { detector = PinnedHeadGestureDetector(); gestureContext = contextKey }
                            val h = (event.value as AacpEvent.Head).sample
                            detector.processHeadOrientation(h.horizontal, h.vertical)
                            detector.detect()?.let { yes ->
                                val callEpoch = calls.epoch
                                if (call.state == OrdinaryCallState.RINGING) {
                                    val result = synchronized(platformLock) { calls.request(yes, callEpoch) { platformEpoch.get() == headEpoch && current(a) && writable() && gate.platformAuthorization(a.address, a.expectedConsentGeneration) == null &&
                                        store.snapshot().getOrNull()?.pairs?.get(a.address)?.host?.let {
                                            it.headGestures && it.automationsEnabled && (visibility.value || it.fluidifyBackgroundEnabled)
                                        } == true } }
                                    synchronized(platformLock) { if (platformEpoch.get() == headEpoch && current(a)) fail("CALL_${platformCode(result)}") }
                                    detector = PinnedHeadGestureDetector()
                                    return@collect
                                }
                                if (!noCallOrMix()) { detector = PinnedHeadGestureDetector(); return@collect }
                                val action = if (yes) p.nodAction else p.shakeAction
                                val gesture = when (action) { HeadAction.TOGGLE_PLAYBACK -> AirPodsMediaGesture.TOGGLE_PLAYBACK; HeadAction.NEXT -> AirPodsMediaGesture.NEXT; HeadAction.PREVIOUS -> AirPodsMediaGesture.PREVIOUS; else -> null }
                                if (gesture != null) withContext(Dispatchers.Main.immediate) { if (platformEpoch.get() == headEpoch && current(a) && noCallOrMix() && gestureContext == contextKey && contextKey.third != null && media?.gestureContext() == contextKey.third) media?.gesture(gesture) }
                                detector = PinnedHeadGestureDetector()
                            }
                        }
                    }
                }
            }
            scope.launch {
                var mediaStarted = false
                var verifiedSetup = false
                repository.state.collect { s ->
                    diagnostics.record(DiagnosticKind.STAGE, s.stage)
                    if (!current(a)) return@collect
                    advanceBootstrap(s.stage)
                    when (s.stage) {
                        AacpStage.ERROR -> { fail("TRANSPORT_FAILED"); processScope.launch { stopIfCurrent(a, SuppressReason.START_FAILED) } }
                        else -> Unit
                    }
                    val identity = s.identityReceived?.takeIf { it.generation == s.generation }?.value
                    // NAME is projected from INFORMATION itself; it is not an independent basic RX.
                    val basic = s.batteries.values.any { it.generation == s.generation } ||
                        s.properties.any { (property, value) -> property != CommandProperty.NAME && value.generation == s.generation }
                    if (s.stage == AacpStage.SUBSCRIBED && identity?.recognized == true && basic && transport?.connected == true) {
                        val verified = verifiedSetup || if (testPermit != null) true else store.recordSetup(a.address, PairVerification(identity.modelNumber, buildFingerprint, System.currentTimeMillis())).isSuccess
                        if (testPermit != null && verified && !verifiedSetup) observedSetupAt = SystemClock.elapsedRealtime()
                        verifiedSetup = verified
                        mutableRuntime.value = mutableRuntime.value.copy(setupVerified = verified)
                        if (!mediaStarted && a.purpose != ConnectionPurpose.VERIFICATION_ONLY) {
                            mediaStarted = true
                            armTracking()
                            val p = store.snapshot().getOrThrow().pairs.getValue(a.address)
                            val published = withContext(Dispatchers.Main.immediate) { publishMedia(a, s.generation, p.host, scope, bootstrapEar) }
                            // A Stop that already cleared the ticket owns the runtime state: no late adapter verdict.
                            if (ticket === a) mutableRuntime.value = mutableRuntime.value.copy(mediaAvailable = published, failure = if (!published) "MEDIA_ADAPTER_UNAVAILABLE" else null)
                        }
                    }
                }
            }
            if (a.purpose == ConnectionPurpose.VERIFICATION_ONLY) scope.launch {
                delay(30_000)
                if (current(a)) { fail("VERIFICATION_WINDOW_ENDED"); stopIfCurrent(a, SuppressReason.USER_STOP) }
            }
            repository.construct {
                check(current(a))
                observer.observe(QualificationEvent(QualificationStep.OWNER, QualificationPhase.REQUESTED,
                    state.value.generation, token = lease.generation))
                val raw = lease.construct {
                    check(current(a))
                    AirPodsSocket.create(requireNotNull(facts.adapter), requireNotNull(facts.device(a.address)))
                }
                val socketGeneration = state.value.generation
                val socketObserver = AirPodsQualificationObserver { event ->
                    observer.observe(event.copy(generation = socketGeneration, token = event.token ?: lease.generation))
                }
                val ingress = debugInformationIngress?.takeIf {
                    dev.lelonio.square.BuildConfig.DEBUG && testPermit != null &&
                        qualificationAuthority == null && a.purpose == ConnectionPurpose.FUNCTIONAL_TEST
                }
                val guarded = GuardedTransport(raw, socketObserver, onSdu = ingress?.let { capture ->
                    { sdu: ByteArray ->
                        if (current(a) && testPermit?.allowed(visibility.value) == true)
                            capture(socketGeneration, sdu)
                    }
                }) { current(a) }
                synchronized(lock) { if (!current(a)) { guarded.close(); error("STALE_OWNER") }; transport = guarded }
                guarded
            }
            mutableRuntime.value = AndroidRuntimeState(active = true, verifying = a.purpose == ConnectionPurpose.VERIFICATION_ONLY, productConformance = productConformance())
        }
        return true
    }
    private fun stopOwner() {
        invalidatePresence()
        cancelPlatform()
        calls.reset()
        val oldTransport: GuardedTransport?; val oldScope: CoroutineScope?; val oldMedia: AirPodsMediaPort?; val oldService: AirPodsControlService?
        val oldLease: AirPodsTransportOwnership.Lease?
        synchronized(lock) {
            oldLease = transportLease
            ticket = null; oldTransport = transport; transport = null; oldScope = sessionScope; sessionScope = null
            oldMedia = media; media = null; oldService = service; service = null
        }
        diagnostics.record(DiagnosticKind.STOP, state.value.stage)
        val began = SystemClock.elapsedRealtime()
        // Stop/lifecycle: the old media authority ends here, on this thread, before any blocking
        // close. Its serial teardown is only queued on main: the caller holds the gate, and
        // main-thread readers (trackingUnavailableReason, setTracking) take that same gate.
        val mediaClose = runCatching {
            check(retireMedia(synchronized(lock) { (failedMedia + listOfNotNull(oldMedia)).distinct().also { failedMedia.clear() } }))
        }
        val leaseClose = runCatching { oldLease?.close() }
        val close = runCatching {
            val resources = synchronized(lock) { (failedTransports + listOfNotNull(oldTransport)).distinct().also { failedTransports.clear() } }
            var failed = false
            resources.forEach { t -> if (runCatching { t.close() }.isFailure) { synchronized(lock) { failedTransports.add(t) }; failed = true } }
            check(!failed)
        }
        repository.close() // closes before cancellation; blocked Bluetooth I/O must be interrupted
        oldScope?.cancel()
        synchronized(lock) { if (oldLease?.released != false && transportLease === oldLease) { transportLease = null; AirPodsServiceOwnerRegistry.release(this, oldLease, oldService != null) } }
        mutableVolume.value = null
        observedSetupAt = null
        val observationStop = if (retainObservationForDisconnect) CompanionResult.Stopped else companion.stop()
        val observationPersisted = if (!retainObservationForDisconnect && observationStop !is CompanionResult.Denied) {
            val persisted = observation == null || qualificationAuthority?.sealObservation(null) != false
            observation = null
            persisted
        } else true
        oldService?.finishFromOwner()
        if (testPermit != null && !(qualificationAuthority != null && retainObservationForDisconnect)) {
            testPermit.revoke(); audioReceipt = null; onTestStopped?.invoke()
        }
        mutableRuntime.value = AndroidRuntimeState(productConformance = productConformance())
        check(observationPersisted && leaseClose.isSuccess && oldLease?.released != false && close.isSuccess && mediaClose.isSuccess && observationStop !is CompanionResult.Denied && SystemClock.elapsedRealtime() - began < 2000) {
            fail("CLEANUP_FAILED"); "AirPods cleanup failed"
        }
        // Stop/lifecycle: a teardown that main already ran and failed must survive the state reset above.
        check(ownerCleanup() != OwnerCleanup.FAILED) { fail("CLEANUP_FAILED"); "AirPods media teardown failed" }
    }

    // ---- Stop/lifecycle: media retirement ------------------------------------------------------
    // Lock order: gate -> lock. The main thread may take either, so nothing waits for the main
    // thread while holding one of them, and main never waits for a worker. A waiter suspends
    // holding nothing; the gate is concluded off main.

    /** Revokes [ports] now, on the calling thread, and queues their serial teardown on main
     * without waiting for it. False only when a teardown already failed here: a main-thread
     * caller runs it inline, exactly as before, or the main looper refused the task. */
    private fun retireMedia(ports: List<AirPodsMediaPort>): Boolean {
        if (ports.isEmpty()) return true
        ports.forEach { port -> runCatching { port.revoke() } }
        if (Looper.myLooper() == Looper.getMainLooper()) return stopRetired(ports)
        synchronized(lock) { retiringMedia.addAll(ports); retiringCount.value = retiringMedia.size }
        // Never removed: a busy main delays the teardown, it cannot skip it.
        val queued = runCatching { Handler(Looper.getMainLooper()).post {
            val stopped = stopRetired(ports) // a failed port is recorded before it leaves retiringMedia
            synchronized(lock) { retiringMedia.removeAll(ports); retiringCount.value = retiringMedia.size }
            if (!stopped) fail("CLEANUP_FAILED")
            // No durable write and no gate wait on the UI thread.
            Dispatchers.IO.asExecutor().execute { synchronized(gate) { gate.settleCleanup() } }
        } }.getOrDefault(false)
        if (!queued) synchronized(lock) {
            retiringMedia.removeAll(ports); failedMedia.addAll(ports); retiringCount.value = retiringMedia.size
        }
        return queued
    }
    /** Main thread only. A port that fails stays in failedMedia for the next explicit stop. */
    private fun stopRetired(ports: List<AirPodsMediaPort>): Boolean {
        var stopped = true
        ports.forEach { port -> if (runCatching { port.stop() }.isFailure) { synchronized(lock) { failedMedia.add(port) }; stopped = false } }
        return stopped
    }
    /** Main thread only; the bootstrap collector and the host regressions use this same step.
     * The port is created outside every lock and becomes the session's port only under lock and
     * only while [a] is still the ticket. Stop clears the ticket and captures `media` in one lock
     * section, so it either captures this port or this publication is refused: no port is ever
     * live outside a Stop's reach. A refused port is revoked here and never started.
     * The queued teardown of a Stop that wins later runs on main, hence after this call returns.
     * Not asserted here: the port enforces its own serial domain. */
    internal fun publishMedia(a: ConnectionAdmission, generation: Long, settings: HostSettings, scope: CoroutineScope,
        baselineEar: Received<AacpEvent>? = null): Boolean {
        val port = (if (current(a)) mediaFactory?.invoke() else null) ?: return false
        val published = synchronized(lock) { if (ticket === a) { media = port; true } else false }
        if (!published) {
            runCatching { port.revoke() }
            return false
        }
        // From here a Stop may capture and revoke the port at any time: every step asks again.
        if (media === port) port.start(AutomationSession(a.connectionId, generation), a.address, settings)
        if (media === port) port.setUiVisible(visibility.value)
        if (media === port && current(a) && baselineEar?.generation == generation && baselineEar.value is AacpEvent.Ear)
            port.onEvent(baselineEar)
        if (media === port) scope.launch(Dispatchers.Main) {
            port.volume.collect { value ->
                // On main, like the teardown and the next publication: a callback that outlived
                // its port cannot leave a value behind, and its retraction cannot touch a newer port.
                if (media === port) {
                    mutableVolume.value = value
                    if (media !== port) mutableVolume.compareAndSet(value, null)
                }
            }
        }
        return true
    }
    private fun ownerCleanup(): OwnerCleanup = synchronized(lock) {
        when {
            failedMedia.isNotEmpty() || failedTransports.isNotEmpty() -> OwnerCleanup.FAILED
            retiringMedia.isNotEmpty() -> OwnerCleanup.OUTSTANDING
            else -> OwnerCleanup.COMPLETE
        }
    }
    /** Off the gate and off the main thread: suspends, bounded, until main ran the queued
     * teardown, then asks the gate for its final answer. The teardown and the gate conclude on
     * their own if this gives up first. */
    private suspend fun settleMediaRetirement(): GateResult {
        withTimeoutOrNull(2_000) { retiringCount.first { it == 0 } } // the synchronous cleanup budget
        return synchronized(gate) { gate.settleCleanup() }
    }
    private suspend fun settled(result: GateResult): GateResult =
        if (result == GateResult.Denied(GateReason.CLEANUP_PENDING)) settleMediaRetirement() else result
    /** explicitConsent for the explicit entries that are not under the gate. When it had to stop
     * a live owner whose media teardown is still queued it grants nothing; this waits off-gate
     * and asks once more, provided the action is still the visible explicit one. */
    private suspend fun explicitConsentSettled(ingress: StartIngress, stillExplicit: () -> Boolean): GateResult {
        val first = gate.explicitConsent(ingress)
        if (first != GateResult.Denied(GateReason.CLEANUP_PENDING)) return first
        val cleanup = settleMediaRetirement()
        if (cleanup is GateResult.Denied) return cleanup
        return if (stillExplicit()) gate.explicitConsent(ingress) else GateResult.Denied(GateReason.SETUP_REQUIRED)
    }

    private fun stopIfCurrent(admission: ConnectionAdmission, reason: SuppressReason): Unit = synchronized(gate) {
        if (ticket === admission) gate.stop(reason)
        Unit
    }
    internal fun serviceDestroyed(host: AirPodsControlService) {
        val admitted = ticket ?: return
        if (service === host) processScope.launch {
            synchronized(gate) { if (service === host && ticket === admitted) gate.stop(SuppressReason.USER_STOP) }
        }
    }
    /** Bounded wait for main. Never call it while holding the gate: main-thread readers take it. */
    private fun <T> mainCall(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1)
        var outcome: Result<T>? = null
        val handler = Handler(Looper.getMainLooper())
        val task = Runnable { outcome = runCatching(block); latch.countDown() }
        handler.post(task)
        if (!latch.await(750, java.util.concurrent.TimeUnit.MILLISECONDS)) { handler.removeCallbacks(task); error("MEDIA_MAIN_TIMEOUT") }
        return requireNotNull(outcome).getOrThrow()
    }
    private fun fail(code: String) { diagnostics.record(DiagnosticKind.ERROR, state.value.stage); mutableRuntime.value = mutableRuntime.value.copy(failure = code) }
}

internal class GuardedTransport(private val delegate: AacpTransport, private val observer: AirPodsQualificationObserver = AirPodsQualificationObserver.NONE,
    private val onSdu: ((ByteArray) -> Unit)? = null,
    private val authorized: () -> Boolean = { true }) : AacpTransport {
    @Volatile var connected = false; private set
    @Volatile var closeFailed = false; private set
    @Volatile private var closed = false
    override fun connect() {
        check(!closed && authorized())
        try { delegate.connect(); check(!closed && authorized()); connected = true }
        finally { observer.observe(QualificationEvent(QualificationStep.CONNECT, QualificationPhase.UNCONFIRMED, flags = 1)) }
    }
    override fun readSdu(): ByteArray? {
        observer.observe(QualificationEvent(QualificationStep.RX_SDU, QualificationPhase.REQUESTED, flags = 1))
        try {
            return delegate.readSdu()?.also { sdu ->
                onSdu?.let { capture ->
                    runCatching { if (!closed && authorized()) capture(sdu) }.onFailure {
                        runCatching {
                            observer.observe(QualificationEvent(QualificationStep.RX, QualificationPhase.FAILED,
                                flags = 34, values = listOf(1, 1)))
                        }
                    }
                }
            }
        }
        finally { observer.observe(QualificationEvent(QualificationStep.RX_SDU, QualificationPhase.UNCONFIRMED, flags = 1)) }
    }
    override fun write(packet: ByteArray) {
        check(!closed && authorized())
        observer.observe(QualificationEvent(QualificationStep.TX, QualificationPhase.REQUESTED, flags = 1))
        try { delegate.write(packet) }
        finally { observer.observe(QualificationEvent(QualificationStep.TX, QualificationPhase.UNCONFIRMED, flags = 1)) }
    }
    override fun close() { closed = true; connected = false; try { delegate.close() } catch (e: Exception) { closeFailed = true; throw e } }
}
