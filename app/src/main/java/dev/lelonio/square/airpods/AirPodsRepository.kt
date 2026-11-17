// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface AirPodsClock { fun nowMs(): Long }
enum class AacpStage { CLOSED, CONSTRUCTING, CONSTRUCTED, CONNECTING, CONNECTED, HANDSHAKE_SENT, SUBSCRIBED, ERROR }
enum class CommandProperty { MODE, CONVERSATION, MIC_MODE, DOUBLE_CLICK_INTERVAL, CLICK_HOLD_INTERVAL,
    LISTENING_MODE_CONFIGS, ONE_BUD_ANC_MODE, CHIME_VOLUME, VOLUME_SWIPE_INTERVAL, CALL_MANAGEMENT_CONFIG,
    VOLUME_SWIPE_MODE, ADAPTIVE_VOLUME_CONFIG, AUTO_ANC_STRENGTH, ALLOW_OFF_OPTION, SLEEP_DETECTION_CONFIG,
    EAR_DETECTION_CONFIG, AUTOMATIC_CONNECTION_CONFIG, PPE_TOGGLE_CONFIG, DYNAMIC_END_OF_CHARGE, STEM_CONFIG, EQ, NAME }
enum class CommandOutcome { OBSERVED_MATCH, OBSERVED_OTHER, NOT_CONFIRMED, SEND_FAILED, CLOSED }
data class PendingCommand(val value: Int, val startedMs: Long, val sequence: Long, val requested: PropertyValue = PropertyValue.Number(value),
    val packet: List<Int> = emptyList(), val generation: Long = 0)
data class Received<T>(val value: T, val atMs: Long, val generation: Long)
data class AirPodsState(
    val generation: Long = 0,
    val stage: AacpStage = AacpStage.CLOSED,
    val identity: AirPodsIdentity? = null,
    val identityReceived: Received<AirPodsIdentity>? = null,
    val properties: Map<CommandProperty, Received<PropertyValue>> = emptyMap(),
    val desired: Map<CommandProperty, DesiredProperty> = emptyMap(),
    val cachedValues: AirPodsPairValues = AirPodsPairValues(),
    val deviceAnnounced: Set<Capability>? = null,
    val ears: Received<EarSample>? = null,
    val stem: Received<StemSample>? = null,
    /** Compatibility only; samples are published exclusively through headSamples/events. */
    @Deprecated("Use AirPodsHeadSampleSource.headSamples for visualization, events for gestures")
    val head: Received<HeadSample>? = null,
    val activePacketFormat: HeadPacketFormat? = null,
    val mode: Received<ListeningMode>? = null,
    val conversation: Received<Boolean>? = null,
    val conversationActivity: Received<Int>? = null,
    val batteries: Map<Int, Received<BatteryValue>> = emptyMap(),
    val pending: Map<CommandProperty, PendingCommand> = emptyMap(),
    val outcomes: Map<CommandProperty, CommandOutcome> = emptyMap(),
    val diagnostic: String = "Physical compatibility and dual-channel coexistence: NV",
    /** Address of the transport that produced this state, including after it closes. */
    val sessionPair: PairAddress? = null,
) {
    fun propertyObserved(property: CommandProperty): Received<PropertyValue>? {
        val received = properties[property] ?: when (property) {
            CommandProperty.MODE -> mode?.let { Received<PropertyValue>(PropertyValue.Number(it.value.wire), it.atMs, it.generation) }
            CommandProperty.CONVERSATION -> conversation?.let { Received<PropertyValue>(PropertyValue.Number(if (it.value) 1 else 2), it.atMs, it.generation) }
            else -> null
        }
        return received?.takeIf { it.generation == generation && stage == AacpStage.SUBSCRIBED }
    }
    fun canAttemptWrite(property: CommandProperty, value: PropertyValue): Boolean {
        val id = identityReceived?.takeIf { it.generation == generation }?.value ?: return false
        if (stage != AacpStage.SUBSCRIBED || !id.recognized) return false
        val spec = AirPodsProperties.spec(property)
        if (spec != null) {
            val number = (value as? PropertyValue.Number)?.value ?: return false
            if (!spec.candidate(id) || !spec.accepts(number)) return false
            if (property == CommandProperty.MODE && id.modes.none { it.wire == number }) return false
            val offDisabled = (propertyEffective(CommandProperty.ALLOW_OFF_OPTION)?.value as? PropertyValue.Number)?.value == 2
            if (offDisabled && ((property == CommandProperty.MODE && number == 1) ||
                (property == CommandProperty.LISTENING_MODE_CONFIGS && (number and 1) != 0))) return false
            if (property == CommandProperty.LISTENING_MODE_CONFIGS) {
                if (propertyObserved(property) == null) return false
                if ((number and 8) != 0 && Capability.ADAPTIVE_AUDIO !in id.modelDeclared) return false
            }
            return true
        }
        return when (property) {
            CommandProperty.EQ -> value is PropertyValue.Eq // explicit candidate, universal support is NV
            CommandProperty.NAME -> value is PropertyValue.Name && runCatching { AacpCodec.rename(value.value) }.isSuccess
            else -> false
        }
    }
    fun liveBattery(component: Int, nowMs: Long): BatteryValue? = batteries[component]?.takeIf {
        it.generation == generation && stage in setOf(AacpStage.CONNECTED, AacpStage.HANDSHAKE_SENT, AacpStage.SUBSCRIBED) &&
            nowMs - it.atMs in 0 until 300_000
    }?.value
}

/** Internal, inert until construct() is explicitly called. Does not own application lifecycle,
 * permissions, audio routes or playback. All state transitions are serialized by a short monitor;
 * blocking socket operations never hold it. Old readers/timeouts cannot mutate a new generation. */
class AirPodsRepository(
    private val scope: CoroutineScope,
    private val clock: AirPodsClock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val observer: AirPodsQualificationObserver = AirPodsQualificationObserver.NONE,
    private val valueStore: AirPodsPairValueStore = AirPodsPairValueStore(),
    private val wallClock: AirPodsClock = AirPodsClock { System.currentTimeMillis() },
    private val rxDiagnostics: AirPodsRxDiagnostics = AirPodsRxDiagnostics.NONE,
) : Closeable, AirPodsHeadSampleSource {
    private val lock = Any()
    private val writer = Mutex()
    private val tracking = Mutex()
    private val mutable = MutableStateFlow(AirPodsState())
    val state: StateFlow<AirPodsState> = mutable.asStateFlow()
    private val mutableHead = MutableStateFlow<Received<HeadSample>?>(null)
    override val headSamples: StateFlow<Received<HeadSample>?> = mutableHead.asStateFlow()
    private val mutableCacheFailures = MutableStateFlow(0L)
    /** Optional diagnostics only; a value-cache failure is never a permission decision. */
    val valueCacheFailures: StateFlow<Long> = mutableCacheFailures.asStateFlow()
    private var transport: AacpTransport? = null
    private var session: Job? = null
    private var sequence = 0L
    private var trackingRequest = 0L
    private var bootstrapGeneration: Long? = null
    private val mutableEvents = MutableSharedFlow<Received<AacpEvent>>(extraBufferCapacity = EVENT_CAPACITY)
    /** Subscribe before connect. FIFO, no replay, no sensor sampling. The reader never suspends
     * for a collector: exceeding 1024 queued events terminates the generation explicitly instead
     * of silently dropping a sample or retaining an unbounded backlog. */
    val events: SharedFlow<Received<AacpEvent>> = mutableEvents.asSharedFlow()
    private val commandQueue = mutableMapOf<CommandProperty, PendingCommand>()
    private var commandWorker: Job? = null
    private val rxOrdinal = AtomicLong()
    private data class SentAttempt(val command: PendingCommand, val atMs: Long)
    private val sentHistory = mutableMapOf<CommandProperty, ArrayDeque<SentAttempt>>()
    internal companion object {
        const val EVENT_CAPACITY = 1024
        const val LATE_ECHO_WINDOW_MS = 1_000L
    }
    private var currentPair: PairAddress? = null
    private val pairCache = mutableMapOf<PairAddress, AirPodsPairValues>()
    private val persistenceWriter = Mutex()
    private val pendingPersistence = mutableMapOf<PairAddress, AirPodsPairValues>()
    private val persistenceWakeup = Channel<Unit>(Channel.CONFLATED)
    init {
        scope.launch(io) {
            for (ignored in persistenceWakeup) {
                persistenceWriter.withLock {
                    val updates = synchronized(lock) { pendingPersistence.toMap().also { pendingPersistence.clear() } }
                    updates.forEach { (pair, values) ->
                        if (valueStore.save(pair, values).isFailure) mutableCacheFailures.value += 1
                    }
                }
            }
        }
    }

    /** IO-only snapshots, coalesced independently for every pair, including a retired pair. */
    private fun persistLocked(s: AirPodsState) {
        // A transport can close before the selected pair's cache has been loaded.
        val values = s.cachedValues
        if (values.observed.isEmpty() && values.choices.isEmpty() && values.casePercent == null) return
        currentPair?.let {
            pairCache[it] = values
            pendingPersistence[it] = values
            persistenceWakeup.trySend(Unit)
        }
    }

    /** An offline presentation lookup never assigns the pair to a socket or admits a command. */
    internal suspend fun cachedPairValues(pair: PairAddress): AirPodsPairValues? {
        synchronized(lock) { pairCache[pair]?.let { return it } }
        return withContext(io) {
            persistenceWriter.withLock {
                synchronized(lock) { pairCache[pair] } ?: valueStore.load(pair)
                    .onFailure { mutableCacheFailures.value += 1 }.getOrNull()?.let { loaded ->
                        synchronized(lock) { pairCache.getOrPut(pair) { loaded } }
                    }
            }
        }
    }
    /** Owner calls after forgetting/unpairing. Serialized with cache writes, so a delayed save
     * cannot resurrect deleted values. Does not change consent, setup or Bluetooth ownership. */
    suspend fun forgetPairValues(pair: PairAddress): Result<Unit> = withContext(io) {
        persistenceWriter.withLock {
            synchronized(lock) {
                pairCache.remove(pair); pendingPersistence.remove(pair)
                if (currentPair == pair) {
                    currentPair = null
                    mutable.value = mutable.value.copy(cachedValues = AirPodsPairValues(), desired = emptyMap())
                }
            }
            valueStore.forget(pair).also { if (it.isFailure) mutableCacheFailures.value += 1 }
        }
    }
    fun construct(factory: () -> AacpTransport) = construct(null, factory)

    /** Owner supplies its admitted pair, never an INFORMATION name/serial or inferred alias. */
    fun construct(pair: PairAddress?, factory: () -> AacpTransport) {
        val generation: Long
        val job: Job
        val oldSocket: AacpTransport?; val oldJob: Job?
        synchronized(lock) {
            oldSocket = transport; transport = null; oldJob = session
            commandQueue.clear(); commandWorker = null; sentHistory.clear(); mutableHead.value = null
            // Preserve uncertainty even when construct directly replaces a connected generation.
            val retired = uncertain(mutable.value)
            persistLocked(retired)
            // A legacy caller without an admitted pair cannot carry values across sessions:
            // two unknown sessions might belong to different physical pairs.
            val retained = if (pair != null && pair == currentPair) retired.cachedValues else pair?.let(pairCache::get)
            currentPair = pair
            generation = mutable.value.generation + 1
            job = SupervisorJob(scope.coroutineContext[Job])
            session = job
            mutable.value = AirPodsState(generation, AacpStage.CONSTRUCTING,
                desired = retained?.restoredChoices(generation).orEmpty(),
                cachedValues = retained ?: AirPodsPairValues(), sessionPair = pair)
            observer.observe(QualificationEvent(QualificationStep.CONSTRUCT, QualificationPhase.REQUESTED, generation))
        }
        try { oldSocket?.close() } catch (_: Exception) { /* invalidated before close */ }
        oldJob?.cancel()
        scope.launch(job + io) {
            try {
                val cached = pair?.let { cachedPairValues(it) }
                synchronized(lock) {
                    if (isCurrent(generation) && currentPair == pair && cached != null && mutable.value.cachedValues == AirPodsPairValues())
                        mutable.value = mutable.value.copy(cachedValues = cached, desired = cached.restoredChoices(generation))
                }
                val created = factory()
                observer.observe(QualificationEvent(QualificationStep.CONSTRUCT, QualificationPhase.COMPLETED, generation))
                val accepted = synchronized(lock) {
                    if (isCurrent(generation)) {
                        transport = created
                        mutable.value = mutable.value.copy(stage = AacpStage.CONSTRUCTED)
                        true
                    } else false
                }
                if (!accepted) created.close()
            } catch (e: Exception) {
                observer.observe(QualificationEvent(QualificationStep.CONSTRUCT, QualificationPhase.FAILED, generation))
                fail(generation, "constructor", e)
            }
        }
    }

    fun connect() {
        val g: Long; val socket: AacpTransport; val job: Job
        synchronized(lock) {
            if (mutable.value.stage != AacpStage.CONSTRUCTED) return
            g = mutable.value.generation; socket = transport ?: return; job = session ?: return
            mutable.value = mutable.value.copy(stage = AacpStage.CONNECTING)
        }
        scope.launch(job + io) {
            // Separate coroutine: cancellation alone cannot interrupt BluetoothSocket.connect/read.
            val deadline = scope.launch(job) {
                delay(10_000)
                release(g, AacpStage.ERROR, "connect timeout: 10s deadline; compatibility NV", AacpStage.CONNECTING)
            }
            try {
                observer.observe(QualificationEvent(QualificationStep.CONNECT, QualificationPhase.REQUESTED, g))
                socket.connect()
                observer.observe(QualificationEvent(QualificationStep.CONNECT, QualificationPhase.COMPLETED, g))
                val connected = synchronized(lock) {
                    if (isCurrent(g) && mutable.value.stage == AacpStage.CONNECTING) {
                        mutable.value = mutable.value.copy(stage = AacpStage.CONNECTED); true
                    } else false
                }
                deadline.cancel()
                if (connected) readLoop(g, socket)
            } catch (e: Exception) {
                observer.observe(QualificationEvent(QualificationStep.CONNECT, QualificationPhase.FAILED, g))
                fail(g, "connect/read", e)
            }
            finally { deadline.cancel() }
        }
    }

    fun handshake() = sendStage(AacpStage.CONNECTED, AacpStage.HANDSHAKE_SENT, AacpCodec.handshake())
    fun subscribe() = sendStage(AacpStage.HANDSHAKE_SENT, AacpStage.SUBSCRIBED, AacpCodec.subscribe())
    // Explicit diagnostic step retained; bootstrap() sends the same pinned flags.
    fun featureFlags() = sendStage(AacpStage.HANDSHAKE_SENT, AacpStage.HANDSHAKE_SENT, AacpCodec.featureFlags())

    /** One ordered initialization per connected generation, using pinned LibrePods bytes.
     * This does not request proximity keys, claim connection ownership or start tracking.
     * SUBSCRIBED means local writes completed, never firmware acknowledgement/capability. */
    fun bootstrap() {
        val g: Long; val socket: AacpTransport; val job: Job
        synchronized(lock) {
            val s = mutable.value
            if (s.stage != AacpStage.CONNECTED || bootstrapGeneration == s.generation) return
            g = s.generation; socket = transport ?: return; job = session ?: return
            bootstrapGeneration = g
        }
        scope.launch(job + io) {
            try {
                writer.withLock {
                    val packets = listOf(AacpCodec.handshake(), AacpCodec.featureFlags(),
                        AacpCodec.subscribe(), AacpCodec.bootstrapExtension())
                    for ((index, packet) in packets.withIndex()) {
                        val from = if (index == 0) AacpStage.CONNECTED else AacpStage.HANDSHAKE_SENT
                        val step = if (index == 2) QualificationStep.SUBSCRIBE else QualificationStep.BOOTSTRAP
                        observer.observe(QualificationEvent(step, QualificationPhase.REQUESTED, g))
                        val written = writeLocked(g, socket, packet) { mutable.value.stage == from }
                        observer.observe(QualificationEvent(step, if (written) QualificationPhase.COMPLETED else QualificationPhase.REJECTED, g))
                        if (!written) return@withLock
                        synchronized(lock) {
                            if (isCurrent(g) && mutable.value.stage == from) {
                                val to = if (index == packets.lastIndex) AacpStage.SUBSCRIBED else AacpStage.HANDSHAKE_SENT
                                mutable.value = mutable.value.copy(stage = to)
                            }
                        }
                    }
                }
                scheduleInitializationRefresh(g, socket, job)
            } catch (e: Exception) { fail(g, "bootstrap write", e) }
        }
    }

    private fun scheduleInitializationRefresh(g: Long, socket: AacpTransport, job: Job) {
        // Pinned AirPodsService: 200ms between handshake, flags, subscribe and 0x29;
        // another 200ms, then the final handshake/flags/subscribe after 5 seconds.
        // No proximity keys, ownership requests or implicit head tracking. Delays inherit
        // the owner's scheduler; only blocking writes switch to IO.
        scope.launch(job) {
            try {
                for (packet in listOf(AacpCodec.handshake(), AacpCodec.featureFlags(),
                    AacpCodec.subscribe(), AacpCodec.bootstrapExtension())) {
                    delay(200)
                    if (!withContext(io) { write(g, socket, packet) { mutable.value.stage == AacpStage.SUBSCRIBED } }) return@launch
                }
                delay(200)
                delay(5_000)
                withContext(io) {
                    writer.withLock {
                        for (packet in listOf(AacpCodec.handshake(), AacpCodec.featureFlags(), AacpCodec.subscribe()))
                            if (!writeLocked(g, socket, packet) { mutable.value.stage == AacpStage.SUBSCRIBED }) return@withLock
                    }
                }
            } catch (e: Exception) { fail(g, "bootstrap refresh write", e) }
        }
    }

    private fun sendStage(from: AacpStage, to: AacpStage, packet: ByteArray) {
        val g: Long; val socket: AacpTransport; val job: Job
        synchronized(lock) {
            if (mutable.value.stage != from) return
            g = mutable.value.generation; socket = transport ?: return; job = session ?: return
        }
        scope.launch(job + io) {
            try {
                val step = if (to == AacpStage.SUBSCRIBED) QualificationStep.SUBSCRIBE else QualificationStep.BOOTSTRAP
                observer.observe(QualificationEvent(step, QualificationPhase.REQUESTED, g))
                val written = write(g, socket, packet)
                observer.observe(QualificationEvent(step, if (written) QualificationPhase.COMPLETED else QualificationPhase.REJECTED, g))
                synchronized(lock) { if (written && isCurrent(g) && mutable.value.stage == from) mutable.value = mutable.value.copy(stage = to) }
            } catch (e: Exception) { fail(g, "write", e) }
        }
    }

    fun setMode(mode: ListeningMode): Boolean = setProperty(CommandProperty.MODE, PropertyValue.Number(mode.wire))
    fun setConversation(enabled: Boolean): Boolean = setProperty(CommandProperty.CONVERSATION, PropertyValue.Number(if(enabled) 1 else 2))
    fun setProperty(property: CommandProperty, value: Int) = setProperty(property, PropertyValue.Number(value))
    fun rename(name: String) = setProperty(CommandProperty.NAME, PropertyValue.Name(name))
    fun setEq(value: PropertyValue.Eq) = setProperty(CommandProperty.EQ, value)
    fun setProperty(property: CommandProperty, value: PropertyValue): Boolean {
        val packet = try { when (value) {
            is PropertyValue.Number -> AacpCodec.property(property, value.value)
            is PropertyValue.Eq -> { require(property == CommandProperty.EQ); AacpCodec.eq(value) }
            is PropertyValue.Name -> { require(property == CommandProperty.NAME); AacpCodec.rename(value.value) }
        } } catch (_: IllegalArgumentException) { return false }
        synchronized(lock) {
            val s = mutable.value
            if (!s.canAttemptWrite(property, value)) return false
            val socket = transport ?: return false; val job = session ?: return false; val g = s.generation
            // Identical queued/in-flight/sent intent is already represented. Never resend on RX.
            if (s.desired[property]?.let { it.requested == value && it.status in setOf(
                    DesiredStatus.QUEUED, DesiredStatus.WRITING, DesiredStatus.SENT) } == true) return true
            val pending = PendingCommand((value as? PropertyValue.Number)?.value ?: 0, clock.nowMs(), ++sequence,
                value, packet.map { it.toInt() and 255 }, g)
            val choice = DesiredProperty(value, pending.sequence, g, DesiredStatus.QUEUED, pending.startedMs)
            commandQueue[property] = pending
            mutable.value = s.copy(pending = s.pending + (property to pending), outcomes = s.outcomes - property,
                desired = s.desired + (property to choice), cachedValues = s.cachedValues.copy(
                    choices = s.cachedValues.choices + (property to SavedChoice(value, wallClock.nowMs().coerceAtLeast(0), choice.status))))
            persistLocked(mutable.value)
            observer.observe(QualificationEvent(QualificationStep.TX, QualificationPhase.REQUESTED, g, pending.sequence, property, flags = 2))
            if (commandWorker?.isActive != true) {
                commandWorker = scope.launch(job + io, start = CoroutineStart.LAZY) { drainCommands(g, socket) }
                commandWorker?.start()
            }
        }
        return true
    }

    /** One worker per generation. Select AFTER acquiring the physical writer: queued A->B->C
     * sends only C. An already-entered write completes; its result cannot retire a newer intent. */
    private suspend fun drainCommands(g: Long, socket: AacpTransport) {
        while (true) {
            var sent: Pair<CommandProperty, PendingCommand>? = null
            try {
                val more = writer.withLock {
                    val entry = synchronized(lock) {
                        if (!isCurrent(g) || transport !== socket) return@withLock false
                        val next = commandQueue.minByOrNull { it.value.sequence }
                        if (next == null) { commandWorker = null; return@withLock false }
                        val property = next.key; val request = next.value
                        commandQueue.remove(property)
                        sent = property to request
                        val s = mutable.value
                        if (!s.canAttemptWrite(property, request.requested)) {
                            val rejected = s.desired.getValue(property).copy(status = DesiredStatus.FAILED)
                            mutable.value = rememberChoice(s.copy(pending = s.pending - property,
                                desired = s.desired + (property to rejected),
                                outcomes = s.outcomes + (property to CommandOutcome.SEND_FAILED)), property, rejected)
                            return@withLock true
                        }
                        property to request
                    }
                    val (property, request) = entry
                    val written = writeLocked(g, socket, request.packet.map { it.toByte() }.toByteArray(), request.sequence, property,
                        begin = {
                            val s = mutable.value
                            val intent = s.desired.getValue(property).copy(status = DesiredStatus.WRITING, writeAfterRx = rxOrdinal.get())
                            mutable.value = rememberChoice(s.copy(desired = s.desired + (property to intent)), property, intent)
                            sentHistory.getOrPut(property) { ArrayDeque() }.apply {
                                addLast(SentAttempt(request, clock.nowMs())); while (size > 32) removeFirst()
                            }
                        }, eligible = {
                            mutable.value.desired[property]?.sequence == request.sequence &&
                                mutable.value.canAttemptWrite(property, request.requested)
                        })
                    synchronized(lock) {
                        if (isCurrent(g)) {
                            val s = mutable.value
                            val intent = s.desired[property]
                            if (intent?.sequence == request.sequence) {
                                // An RX may have reconciled this intent while socket.write blocked.
                                val updated = if (!written) intent.copy(status = DesiredStatus.FAILED)
                                    else if (intent.status == DesiredStatus.WRITING) intent.copy(status = DesiredStatus.SENT) else intent
                                mutable.value = rememberChoice(s.copy(pending = s.pending - property,
                                    desired = s.desired + (property to updated),
                                    outcomes = when (updated.status) {
                                        DesiredStatus.SENT -> s.outcomes - property
                                        DesiredStatus.FAILED -> s.outcomes + (property to CommandOutcome.SEND_FAILED)
                                        else -> s.outcomes
                                    }), property, updated)
                            }
                        }
                    }
                    true
                }
                if (!more) return
            } catch (e: Exception) {
                synchronized(lock) {
                    sent?.let { (property, request) ->
                        val s = mutable.value
                        s.desired[property]?.takeIf { isCurrent(g) && it.sequence == request.sequence }?.let {
                            val failed = it.copy(status = DesiredStatus.FAILED)
                            mutable.value = rememberChoice(s.copy(pending = s.pending - property,
                                desired = s.desired + (property to failed), outcomes = s.outcomes + (property to CommandOutcome.SEND_FAILED)), property, failed)
                        }
                    }
                }
                fail(g, "command write", e)
                return
            }
        }
    }

    private fun rememberChoice(s: AirPodsState, property: CommandProperty, intent: DesiredProperty): AirPodsState {
        val saved = s.cachedValues.choices[property]
        val result = s.copy(cachedValues = s.cachedValues.copy(choices = s.cachedValues.choices +
            (property to SavedChoice(intent.requested, saved?.atEpochMs ?: wallClock.nowMs().coerceAtLeast(0), intent.status))))
        persistLocked(result)
        return result
    }
    /** Subscription refresh solicits INFORMATION/battery/control RX.
     * No unverified INFORMATION request opcode, ownership or proximity keys. */
    fun requestInformation() {
        if (state.value.stage == AacpStage.SUBSCRIBED)
            sendStage(AacpStage.SUBSCRIBED, AacpStage.SUBSCRIBED, AacpCodec.subscribe())
        else subscribe()
    }

    private suspend fun write(g: Long, socket: AacpTransport, packet: ByteArray, sequence: Long = 0, property: CommandProperty? = null, eligible: () -> Boolean = { true }) = writer.withLock {
        writeLocked(g, socket, packet, sequence, property, eligible = eligible)
    }
    /** Caller holds writer, including for the complete bootstrap sequence. */
    private fun writeLocked(g: Long, socket: AacpTransport, packet: ByteArray, sequence: Long = 0, property: CommandProperty? = null,
        begin: () -> Unit = {}, eligible: () -> Boolean = { true }): Boolean {
        val current = synchronized(lock) { isCurrent(g) && transport === socket && eligible() }
        return if (current) {
            observer.observe(QualificationEvent(QualificationStep.TX, QualificationPhase.REQUESTED, g, sequence, property, value = packet.size))
            try {
                val admitted = synchronized(lock) {
                    (isCurrent(g) && transport === socket && eligible()).also { if (it) begin() }
                }
                if (!admitted) {
                    observer.observe(QualificationEvent(QualificationStep.TX, QualificationPhase.REJECTED, g, sequence, property))
                    return false
                }
                AacpCodec.captureTx(packet)
                socket.write(packet) // close never acquires writer: interrupts blocked I/O
                observer.observe(QualificationEvent(QualificationStep.TX, QualificationPhase.COMPLETED, g, sequence, property, value = packet.size))
                true
            } catch (e: Exception) {
                observer.observe(QualificationEvent(QualificationStep.TX, QualificationPhase.FAILED, g, sequence, property, value = packet.size))
                throw e
            }
        } else {
            observer.observe(QualificationEvent(QualificationStep.TX, QualificationPhase.REJECTED, g, sequence, property))
            false
        }
    }
    /** Caller supplies persisted pair preference (C1); requested format is not RX capability evidence.
     * Serial stop(active), invalidate, start(requested). Stop failure closes the generation. */
    fun setHeadTracking(format: HeadPacketFormat, enabled: Boolean): Boolean {
        val g: Long; val socket: AacpTransport; val job: Job; val request: Long
        synchronized(lock) {
            val s = mutable.value
            if (s.stage != AacpStage.SUBSCRIBED || s.identityReceived?.generation != s.generation ||
                Capability.HEAD_GESTURES !in (s.identity?.modelDeclared ?: emptySet())) return false
            g=s.generation; socket=transport ?: return false; job=session ?: return false; request=++trackingRequest
        }
        scope.launch(job + io) {
            tracking.withLock {
                val active = synchronized(lock) { if (!isCurrent(g) || trackingRequest != request) return@withLock; mutable.value.activePacketFormat }
                if (enabled && active == format) return@withLock
                try {
                    if (active != null) write(g, socket, AacpCodec.headTracking(active, false))
                    synchronized(lock) { if (isCurrent(g)) mutable.value = mutable.value.copy(activePacketFormat=null, head=null) }
                    if (enabled && synchronized(lock) { isCurrent(g) && trackingRequest == request }) {
                        write(g, socket, AacpCodec.headTracking(format, true))
                        synchronized(lock) { if (isCurrent(g)) mutable.value = mutable.value.copy(activePacketFormat=format) }
                    }
                } catch (e: Exception) { fail(g, "tracking write", e) }
            }
        }
        return true
    }
    private suspend fun readLoop(g: Long, socket: AacpTransport) {
        while (synchronized(lock) { isCurrent(g) }) {
            val sdu = socket.readSdu() ?: throw java.io.EOFException()
            // The timestamp and ordinal belong to the whole SDU, before any frame is decoded.
            val ordinal = rxOrdinal.incrementAndGet()
            val ingressAt = clock.nowMs()
            if (!synchronized(lock) { isCurrent(g) && transport === socket }) return
            observer.observe(QualificationEvent(QualificationStep.RX_SDU, QualificationPhase.OBSERVED, g, value = sdu.size))
            val diagnosticFrame: ((AacpRxFrame) -> Unit)? = if (rxDiagnostics === AirPodsRxDiagnostics.NONE) null
                else { frame -> rxDiagnostics.observe(g, frame) }
            AacpCodec.decodeSdu(sdu, diagnosticFrame).forEach {
                receive(g, it, ingressAt, ordinal)
                if (synchronized(lock) { isCurrent(g) } && !mutableEvents.tryEmit(Received(it, ingressAt, g)))
                    throw IllegalStateException("EVENT_BUFFER_OVERFLOW")
            }
        }
    }
    internal fun receive(g: Long, event: AacpEvent, ingressAt: Long = clock.nowMs()) =
        receive(g, event, ingressAt, rxOrdinal.incrementAndGet())

    private fun receive(g: Long, event: AacpEvent, ingressAt: Long, ordinal: Long) = synchronized(lock) {
        if (!isCurrent(g)) return@synchronized
        var s = mutable.value
        // Only enum tags and numeric control values. INFORMATION/name/serial bytes never enter this port.
        val property = (event as? AacpEvent.Property)?.property
        val number = when(event) {
            is AacpEvent.Property -> (event.value as? PropertyValue.Number)?.value
            is AacpEvent.Mode -> event.value.wire
            is AacpEvent.ConversationSetting -> if (event.enabled) 1 else 2
            is AacpEvent.ConversationActivity -> event.rawStatus
            is AacpEvent.Ear -> event.sample.normalized.ordinal
            is AacpEvent.Information -> if (event.identity.recognized) 1 else 0
            else -> null
        }
        val numbers = when(event) {
            // Keep both bounded sensor codes even when normalization is UNKNOWN.
            // A physical transition cannot be diagnosed from the enum alone.
            is AacpEvent.Ear -> listOf(event.sample.firstRaw, event.sample.secondRaw)
            is AacpEvent.Head -> listOf(event.sample.horizontal, event.sample.vertical, event.sample.wireVariant)
            is AacpEvent.Stem -> listOf(event.sample.press, event.sample.bud)
            is AacpEvent.Battery -> event.components.toSortedMap().flatMap { (id,b) -> listOf(id, b.percent ?: -1, b.status) }.take(9)
            is AacpEvent.Property -> (event.value as? PropertyValue.Eq)?.let { listOf(if(it.enabled) 1 else 0, it.low, it.mid, it.high) }.orEmpty()
            // Only rejected ear-config controls: a fixed numeric allowlist, never identity bytes.
            // This stays Unknown; the four bytes cannot establish an observed ON/OFF value.
            is AacpEvent.Unknown -> event.retainedControlPayload
                ?.takeIf { bytes -> event.opcode == 9 && event.controlIdentifier == 0x0a &&
                    bytes.size == 4 && bytes.all { it in 0..255 } }
                ?.let { bytes -> listOf(1, 0x0a) + bytes } // schema, control ID, four bytes
                .orEmpty()
            else -> emptyList()
        }
        observer.observe(QualificationEvent(QualificationStep.RX, QualificationPhase.OBSERVED, g,
            property = property, value = number, flags = when(event) {
                is AacpEvent.Information -> 1; is AacpEvent.Battery -> 2; is AacpEvent.Mode -> 3
                is AacpEvent.ConversationSetting -> 4; is AacpEvent.ConversationActivity -> 5
                is AacpEvent.Ear -> 6; is AacpEvent.Stem -> 7; is AacpEvent.Head -> 8
                is AacpEvent.Property -> 9; is AacpEvent.Unknown -> 0
            }, values = numbers))
        val now = ingressAt
        fun observed(property: CommandProperty, value: PropertyValue) {
            s = s.copy(properties = s.properties + (property to Received(value, now, g)),
                cachedValues = s.cachedValues.copy(observed = s.cachedValues.observed +
                    (property to SavedProperty(value, wallClock.nowMs().coerceAtLeast(0)))))
            s.desired[property]?.let { intent ->
                val afterWrite = intent.writeAfterRx?.let { ordinal > it } == true
                val reconcilable = when (intent.status) {
                    DesiredStatus.UNCERTAIN, DesiredStatus.FAILED -> intent.writeAfterRx == null || afterWrite
                    DesiredStatus.WRITING, DesiredStatus.SENT -> afterWrite
                    else -> false
                }
                // AACP has no sequence. Each unobserved physical send can explain at most ONE
                // late echo within 1s of that physical send (never queue time). Silence
                // alone changes nothing. Consumed/expired evidence cannot hide a later contrary RX.
                val history = sentHistory[property]
                val latest = history?.firstOrNull { it.command.sequence == intent.sequence }
                val earlier = if (reconcilable && afterWrite && value != intent.requested &&
                    intent.status in setOf(DesiredStatus.WRITING, DesiredStatus.SENT) && latest != null &&
                    now - latest.atMs in 0..LATE_ECHO_WINDOW_MS)
                    history?.firstOrNull {
                        it.command.sequence < intent.sequence && it.command.requested == value &&
                            now - it.atMs in 0..LATE_ECHO_WINDOW_MS
                    }
                else null
                val earlierEcho = earlier != null
                if (earlier != null) history?.remove(earlier)
                if (reconcilable && !earlierEcho) history?.removeAll { it.command.requested == value }
                if (reconcilable && !earlierEcho) {
                    val matches = intent.requested == value
                    val reconciled = intent.copy(status = if (matches) DesiredStatus.CONFIRMED else DesiredStatus.CONTRADICTED)
                    s = rememberChoice(s.copy(desired = s.desired + (property to reconciled),
                        outcomes = s.outcomes + (property to if (matches) CommandOutcome.OBSERVED_MATCH else CommandOutcome.OBSERVED_OTHER)), property, reconciled)
                }
            }
        }
        when (event) {
            is AacpEvent.Property -> observed(event.property, event.value)
            is AacpEvent.Ear -> s = s.copy(ears=Received(event.sample,now,g))
            is AacpEvent.Stem -> s = s.copy(stem=Received(event.sample,now,g))
            is AacpEvent.Head -> if (s.activePacketFormat != null) mutableHead.value = Received(event.sample,now,g)
            is AacpEvent.Mode -> { observed(CommandProperty.MODE, PropertyValue.Number(event.value.wire)); s = s.copy(mode = Received(event.value, now, g)) }
            is AacpEvent.ConversationSetting -> { observed(CommandProperty.CONVERSATION, PropertyValue.Number(if (event.enabled) 1 else 2)); s = s.copy(conversation = Received(event.enabled, now, g)) }
            is AacpEvent.ConversationActivity -> s = s.copy(conversationActivity = Received(event.rawStatus, now, g))
            is AacpEvent.Battery -> {
                // Keep the current null/disconnected sample honest. Preserve last valid case
                // percentage separately, without reviving its old charging status as live.
                val case = event.components[8]?.percent?.takeIf { it in 0..100 }?.let {
                    SavedCasePercent(it, wallClock.nowMs().coerceAtLeast(0))
                } ?: s.cachedValues.casePercent
                s = s.copy(batteries = s.batteries + event.components.mapValues { Received(it.value, now, g) },
                    cachedValues = s.cachedValues.copy(casePercent = case))
            }
            is AacpEvent.Information -> {
                event.identity.name?.let { observed(CommandProperty.NAME, PropertyValue.Name(it)) }
                s = s.copy(identity=event.identity, identityReceived=Received(event.identity,now,g),
                    diagnostic=if(event.identity.recognized) "Model identified; physical gate still NV" else "Unknown model; controls unavailable")
            }
            is AacpEvent.Unknown -> s = s.copy(diagnostic = "RX opcode=${event.opcode} bytes=${event.bytes}: ${event.reason}")
        }
        mutable.value = s
        if (event is AacpEvent.Property || event is AacpEvent.Mode || event is AacpEvent.ConversationSetting ||
            event is AacpEvent.Information || event is AacpEvent.Battery) persistLocked(s)
    }
    private fun isCurrent(g: Long) = mutable.value.generation == g && session?.isActive == true
    private fun fail(g: Long, step: String, error: Exception?) {
        synchronized(lock) { if (!isCurrent(g)) return }
        val detail = (error as? AacpPlatformException)?.reason ?: error?.javaClass?.simpleName ?: "unknown failure"
        observer.observe(QualificationEvent(QualificationStep.OWNER, QualificationPhase.FAILED, g))
        release(g, AacpStage.ERROR, "$step: ${if (error?.message == "EVENT_BUFFER_OVERFLOW") "EVENT_BUFFER_OVERFLOW" else detail}; compatibility NV")
    }
    override fun close() = release(null, AacpStage.CLOSED, "Closed; physical gate NV")
    private fun uncertain(s: AirPodsState): AirPodsState {
        var result = s
        s.desired.forEach { (property, intent) ->
            if (intent.status in setOf(DesiredStatus.QUEUED, DesiredStatus.WRITING, DesiredStatus.SENT)) {
                val uncertain = intent.copy(status = DesiredStatus.UNCERTAIN)
                result = rememberChoice(result.copy(desired = result.desired + (property to uncertain),
                    outcomes = result.outcomes + (property to CommandOutcome.CLOSED)), property, uncertain)
            }
        }
        return result
    }
    private fun release(expected: Long?, stage: AacpStage, reason: String, expectedStage: AacpStage? = null) {
        val oldSocket: AacpTransport?; val oldJob: Job?
        synchronized(lock) {
            val s = mutable.value
            if (expected != null && s.generation != expected) return
            if (expectedStage != null && s.stage != expectedStage) return
            oldSocket = transport; transport = null; oldJob = session; session = null
            commandQueue.clear(); commandWorker = null; sentHistory.clear(); mutableHead.value = null
            mutable.value = uncertain(s).copy(generation = s.generation + 1, stage = stage,
                activePacketFormat=null, head=null, pending = emptyMap(), diagnostic = reason)
            persistLocked(mutable.value)
        }
        observer.observe(QualificationEvent(QualificationStep.CLOSE, QualificationPhase.REQUESTED, mutable.value.generation))
        try {
            oldSocket?.close()
            observer.observe(QualificationEvent(QualificationStep.CLOSE, QualificationPhase.COMPLETED, mutable.value.generation))
        } catch (_: Exception) {
            observer.observe(QualificationEvent(QualificationStep.CLOSE, QualificationPhase.FAILED, mutable.value.generation))
        }
        oldJob?.cancel()
    }
}
