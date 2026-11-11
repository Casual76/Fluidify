// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.content.Context
import android.os.SystemClock
import dev.lelonio.square.airpods.runtime.android.*
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.media.AndroidAirPodsMediaRuntime
import dev.lelonio.square.airpods.runtime.storage.*
import dev.lelonio.square.playback.PlaybackAirPodsMediaBridge
import dev.lelonio.square.airpods.runtime.AirPodsForegroundState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Created by explicit Start, or a NEW process authority under finite durable C3/C4 opt-in.
 * Intent/saved state/binding never select a pair or grant consent. */
internal class AirPodsFunctionalHarness private constructor(
    context: Context, val visible: AirPodsForegroundState,
    private val qualification: DebugQualificationAuthority? = null,
    val audioOnly: Boolean = false,
) {
    private val permit = qualification?.let {
        AirPodsFunctionalTestPermit.qualification(SystemClock::elapsedRealtime, it.end)
    } ?: if (audioOnly) AirPodsFunctionalTestPermit.audio(SystemClock::elapsedRealtime, requireNotNull(lastAudioSetup).end)
        else AirPodsFunctionalTestPermit.issue(SystemClock::elapsedRealtime)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val evidence = DebugQualificationEvidence()
    private val informationIngress = if (qualification == null && !audioOnly) DebugInformationIngress(evidence) else null
    private val informationCaptureFile = java.io.File(context.noBackupFilesDir, "airpods_information_candidate_v1.bin")
    private val scratch = AirPodsRuntimeStore(qualification?.let { DebugQualificationPersistence(context) } ?: object : RuntimePersistence {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes.clone(); return true }
    })
    val controller: AirPodsAndroidController = AirPodsAndroidController(context.applicationContext, visible, { false },
        { AndroidAirPodsMediaRuntime(context.applicationContext, local = PlaybackAirPodsMediaBridge, observer = evidence, authority = { controller.functionalIngressCurrent() }) },
        permit, scratch, onTestStopped = { requestClose() }, qualificationAuthority = qualification, observer = evidence,
        debugInformationIngress = informationIngress?.let { capture -> { generation, sdu -> capture.observe(generation, sdu) } })
    private var entry: AirPodsIngressSelection? = null
    private var expiry: Job? = null
    @Volatile private var closed = false
    @Volatile private var hostTransfer = false
    private var diagnosticVisible = visible.value
    private var realHostVisible = false
    @Synchronized private fun updateVisibility(diagnostic: Boolean? = null, host: Boolean? = null) {
        val leavingSource = diagnostic == false && diagnosticVisible || host == false && realHostVisible
        diagnostic?.let { diagnosticVisible = it }
        host?.let { realHostVisible = it }
        val actual = diagnosticVisible || hostTransfer && realHostVisible
        if (leavingSource && actual) visible.invalidateForeground()
        visible.value = actual
        if (!actual && !permit.allowed(false)) requestClose()
    }
    fun enteredVisibleUi() { if (!closed) updateVisibility(diagnostic = true) }
    val backgroundArmed get() = permit.backgroundArmed()
    val isQualification get() = qualification != null
    val running get() = !closed && permit.allowed(visible.value)
    private fun scheduleExpiry() {
        expiry?.cancel()
        expiry = scope.launch {
            delay((permit.deadline() - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            close()
        }
    }
    private fun publish() {
        if (!audioOnly) entry = AirPodsIngressResolver.publish(controller, { running }, { hostVisible ->
            if (hostTransfer && !closed) {
                updateVisibility(host = hostVisible)
            }
        }, ::requestClose, uiAllowed = { hostTransfer })
        scope.launch {
            controller.runtime.collect {
                controller.audioSetupReceipt()?.let { observed -> lastAudioSetup = observed }
            }
        }
        scheduleExpiry()
    }
    /** One explicit UI handoff, bounded to 3 seconds. A failed launch cannot leave a grant alive. */
    fun transferToHost(): Boolean {
        if (closed || audioOnly || !controller.functionalUiAuthorized() || !permit.handoff(visible.value)) return false
        hostTransfer = true
        scope.launch {
            delay(3_001)
            if (!closed && !visible.value && !backgroundArmed) close()
        }
        return true
    }
    fun armBackground(minutes: Int): Boolean {
        if (closed || audioOnly || !controller.runtime.value.setupVerified || !controller.runtime.value.active) return false
        val duration = minutes * 60_000L
        if (!permit.armBackground(duration, visible.value)) return false
        scheduleExpiry()
        return true
    }
    fun leftVisibleUi() {
        updateVisibility(diagnostic = false)
    }
    fun requestClose() {
        informationIngress?.disarm()
        // Immediate source-side revocation; durable debug opt-in is retired before returning.
        permit.revoke()
        AirPodsIngressResolver.detach(entry)
        qualification?.revoke()
        scope.launch { close() }
    }
    suspend fun close() = withContext(NonCancellable) {
        informationIngress?.disarm()
        val first = synchronized(this@AirPodsFunctionalHarness) { if (closed) false else { closed = true; true } }
        if (!first) return@withContext
        // Remove presentation authority BEFORE blocking cleanup; stale collectors cannot dispatch.
        AirPodsIngressResolver.detach(entry)
        permit.revoke()
        qualification?.revoke()
        try { controller.disposeFunctionalTest() }
        finally {
            if (qualification?.cleanupObservation() == false) evidence.record(QualificationEvent(QualificationStep.CLOSE, QualificationPhase.FAILED, flags = 2))
            informationIngress?.let { capture ->
                withContext(Dispatchers.IO) {
                    runCatching { capture.persistTo(informationCaptureFile) }.onFailure {
                        evidence.record(QualificationEvent(QualificationStep.RX, QualificationPhase.FAILED,
                            flags = 33, values = listOf(1, 1)))
                    }
                }
            }
            lastEvidence.value = evidence
            synchronized(Companion) { if (active.value === this@AirPodsFunctionalHarness) active.value = null }
            scope.cancel()
        }
    }
    companion object {
        private val operations = Mutex()
        private val active = MutableStateFlow<AirPodsFunctionalHarness?>(null)
        val sessions = active.asStateFlow()
        private val lastEvidence = MutableStateFlow<DebugQualificationEvidence?>(null)
        val completedEvidence = lastEvidence.asStateFlow()
        fun revokeAudioSetup() { lastAudioSetup?.revoke(); lastAudioSetup = null }
        @Volatile private var lastAudioSetup: AirPodsAudioSetupReceipt? = null
        fun existing(): AirPodsFunctionalHarness? = active.value
        fun hasAudioSetup(context: Context, pair: PairAddress): Boolean = airPodsQualificationBuild(context)?.let { lastAudioSetup?.valid(pair, it) } == true
        suspend fun start(context: Context, pair: PairAddress, visible: AirPodsForegroundState,
            qualification: Boolean = false, audioOnly: Boolean = false): Pair<AirPodsFunctionalHarness?, GateResult> = operations.withLock {
            check(visible.value && active.value == null)
            withContext(Dispatchers.IO) {
                DebugQualificationAuthority.load(context.applicationContext)?.let { previous ->
                    previous.revoke()
                    check(previous.cleanupObservation()) { "Previous presence cleanup unresolved" }
                }
            }
            val session = synchronized(this) {
                check(!audioOnly || AirPodsQualificationBootstrap.receiptObservationInstalled)
                check(visible.value && active.value == null) { "Visible action and no previous test required" }
                val q = if (qualification) DebugQualificationAuthority.create(context.applicationContext, pair) else null
                check(!qualification || !audioOnly)
                AirPodsFunctionalHarness(context.applicationContext, AirPodsForegroundState().apply { value = true }, q, audioOnly).also { active.value = it }
            }
            try {
                session.controller.selectPair(pair).getOrThrow()
                if (qualification) session.controller.configure { it.copy(host = HostSettings()) }.getOrThrow()
                val result = when {
                    qualification -> session.controller.beginQualificationTest(explicit = true)
                    audioOnly -> session.controller.beginFunctionalAudio(requireNotNull(lastAudioSetup))
                    else -> session.controller.beginFunctionalTest()
                }
                val started = result is GateResult.Started || audioOnly && result is GateResult.Resumed
                if (started && session.running && visible.value) {
                    session.evidence.consent(session.controller.store.snapshot().getOrThrow().start.consentGeneration)
                    session.publish(); session to result
                } else { session.close(); null to result }
            } catch (error: Throwable) { session.close(); throw error }
        }
        /** Application only restores isolated data/observation. No attempt(), FGS or socket here. */
        suspend fun restoreQualification(context: Context) { operations.withLock {
            val q = DebugQualificationAuthority.load(context.applicationContext) ?: return@withLock
            if (!q.fresh()) { q.revoke(); q.cleanupObservation(); return@withLock }
            val session = synchronized(this) {
                if (active.value != null) return@withLock
                AirPodsFunctionalHarness(context.applicationContext, AirPodsForegroundState(), q).also { active.value = it }
            }
            try {
                session.evidence.processRecreated()
                val result = session.controller.beginQualificationTest(explicit = false)
                if (result is GateResult.Denied) session.close()
                else {
                    session.evidence.consent(session.controller.store.snapshot().getOrThrow().start.consentGeneration)
                    session.publish()
                }
            } catch (_: Exception) { session.close() }
        } }
        fun presence(address: String?, associationId: Int?) { active.value?.takeIf { it.isQualification && it.running }?.controller?.presence(address, associationId) }
    }
}
