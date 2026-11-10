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
import dev.lelonio.square.airpods.protocol.*
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
    val deviceAnnounced: Set<Capability>? = null,
    val ears: Received<EarSample>? = null,
    val stem: Received<StemSample>? = null,
    val head: Received<HeadSample>? = null,
    val activePacketFormat: HeadPacketFormat? = null,
    val mode: Received<ListeningMode>? = null,
    val conversation: Received<Boolean>? = null,
    val conversationActivity: Received<Int>? = null,
    val batteries: Map<Int, Received<BatteryValue>> = emptyMap(),
    val pending: Map<CommandProperty, PendingCommand> = emptyMap(),
    val outcomes: Map<CommandProperty, CommandOutcome> = emptyMap(),
    val diagnostic: String = "Physical compatibility and dual-channel coexistence: NV",
) {
    fun propertyObserved(property: CommandProperty) = properties[property]?.takeIf {
        it.generation == generation && stage == AacpStage.SUBSCRIBED
    }
    fun canAttemptWrite(property: CommandProperty, value: PropertyValue): Boolean {
        val id = identityReceived?.takeIf { it.generation == generation }?.value ?: return false
        if (stage != AacpStage.SUBSCRIBED || !id.recognized || property in pending) return false
        val spec = AirPodsProperties.spec(property)
        if (spec != null) {
            val number = (value as? PropertyValue.Number)?.value ?: return false
            if (!spec.candidate(id) || !spec.accepts(number)) return false
            if (property == CommandProperty.MODE && id.modes.none { it.wire == number }) return false
            val offDisabled = (propertyObserved(CommandProperty.ALLOW_OFF_OPTION)?.value as? PropertyValue.Number)?.value == 2
            if (offDisabled && ((property == CommandProperty.MODE && number == 1) ||
                (property == CommandProperty.LISTENING_MODE_CONFIGS && (number and 1) != 0))) return false
            if (property == CommandProperty.LISTENING_MODE_CONFIGS && (number and 8) != 0 && Capability.ADAPTIVE_AUDIO !in id.modelDeclared) return false
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
) : Closeable {
    private val lock = Any()
    private val writer = Mutex()
    private val tracking = Mutex()
    private val mutable = MutableStateFlow(AirPodsState())
    val state: StateFlow<AirPodsState> = mutable.asStateFlow()
    private var transport: AacpTransport? = null
    private var session: Job? = null
    private var sequence = 0L
    private var trackingRequest = 0L
    private var bootstrapGeneration: Long? = null
    private val mutableEvents = MutableSharedFlow<Received<AacpEvent>>()
    /** Subscribe before connect; live ordered events with backpressure, no replay across owners. */
    val events: SharedFlow<Received<AacpEvent>> = mutableEvents.asSharedFlow()
    private val timers = mutableMapOf<CommandProperty, Job>()

    fun construct(factory: () -> AacpTransport) {
        val generation: Long
        val job: Job
        val oldSocket: AacpTransport?; val oldJob: Job?
        synchronized(lock) {
            oldSocket = transport; transport = null; oldJob = session
            timers.values.forEach { it.cancel() }; timers.clear()
            generation = mutable.value.generation + 1
            job = SupervisorJob(scope.coroutineContext[Job])
            session = job
            mutable.value = AirPodsState(generation, AacpStage.CONSTRUCTING)
            observer.observe(QualificationEvent(QualificationStep.CONSTRUCT, QualificationPhase.REQUESTED, generation))
        }
        try { oldSocket?.close() } catch (_: Exception) { /* invalidated before close */ }
        oldJob?.cancel()
        scope.launch(job + io) {
            try {
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
            } catch (e: Exception) { fail(g, "bootstrap write", e) }
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
        val socket: AacpTransport; val job: Job; val g: Long; val pending: PendingCommand
        synchronized(lock) {
            val s = mutable.value
            if (!s.canAttemptWrite(property, value)) return false
            socket = transport ?: return false; job = session ?: return false; g = s.generation
            pending = PendingCommand((value as? PropertyValue.Number)?.value ?: 0, clock.nowMs(), ++sequence,
                value, packet.map { it.toInt() and 255 }, g)
            mutable.value = s.copy(pending = s.pending + (property to pending), outcomes = s.outcomes - property)
            observer.observe(QualificationEvent(QualificationStep.TX, QualificationPhase.REQUESTED, g, pending.sequence, property, flags = 2))
            timers[property] = scope.launch(job) {
                delay(5_000)
                finishPending(g, property, pending.sequence, CommandOutcome.NOT_CONFIRMED)
            }
        }
        scope.launch(job + io) {
            try { write(g, socket, packet, pending.sequence, property) { mutable.value.pending[property]?.sequence == pending.sequence && clock.nowMs() - pending.startedMs in 0 until 5_000 } }
            catch (e: Exception) {
                finishPending(g, property, pending.sequence, CommandOutcome.SEND_FAILED)
                fail(g, "command write", e)
            }
        }
        return true
    }
    /** Subscription refresh solicits INFORMATION/battery/control RX.
     * No unverified INFORMATION request opcode, ownership or proximity keys. */
    fun requestInformation() {
        if (state.value.stage == AacpStage.SUBSCRIBED)
            sendStage(AacpStage.SUBSCRIBED, AacpStage.SUBSCRIBED, AacpCodec.subscribe())
        else subscribe()
    }

    private suspend fun write(g: Long, socket: AacpTransport, packet: ByteArray, sequence: Long = 0, property: CommandProperty? = null, eligible: () -> Boolean = { true }) = writer.withLock {
        writeLocked(g, socket, packet, sequence, property, eligible)
    }
    /** Caller holds writer, including for the complete bootstrap sequence. */
    private fun writeLocked(g: Long, socket: AacpTransport, packet: ByteArray, sequence: Long = 0, property: CommandProperty? = null, eligible: () -> Boolean = { true }): Boolean {
        val current = synchronized(lock) { isCurrent(g) && transport === socket && eligible() }
        return if (current) {
            observer.observe(QualificationEvent(QualificationStep.TX, QualificationPhase.REQUESTED, g, sequence, property, value = packet.size))
            try {
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
    private fun finishPending(g: Long, property: CommandProperty, seq: Long, outcome: CommandOutcome) = synchronized(lock) {
        val s = mutable.value
        if (isCurrent(g) && s.pending[property]?.sequence == seq) {
            timers.remove(property)
            mutable.value = s.copy(pending = s.pending - property, outcomes = s.outcomes + (property to outcome))
        }
    }

    private suspend fun readLoop(g: Long, socket: AacpTransport) {
        while (synchronized(lock) { isCurrent(g) }) {
            val sdu = socket.readSdu() ?: throw java.io.EOFException()
            observer.observe(QualificationEvent(QualificationStep.RX_SDU, QualificationPhase.OBSERVED, g, value = sdu.size))
            AacpCodec.decodeSdu(sdu).forEach {
                receive(g, it)
                if (synchronized(lock) { isCurrent(g) }) mutableEvents.emit(Received(it,clock.nowMs(),g))
            }
        }
    }
    internal fun receive(g: Long, event: AacpEvent) = synchronized(lock) {
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
        val now = clock.nowMs()
        fun observed(property: CommandProperty, value: PropertyValue) {
            s = s.copy(properties = s.properties + (property to Received(value, now, g)))
            s.pending[property]?.let {
                timers.remove(property)?.cancel()
                val result = if (now - it.startedMs !in 0 until 5_000) CommandOutcome.NOT_CONFIRMED
                    else if (it.requested == value) CommandOutcome.OBSERVED_MATCH else CommandOutcome.OBSERVED_OTHER
                s = s.copy(pending = s.pending - property, outcomes = s.outcomes + (property to result))
            }
        }
        when (event) {
            is AacpEvent.Property -> observed(event.property, event.value)
            is AacpEvent.Ear -> s = s.copy(ears=Received(event.sample,now,g))
            is AacpEvent.Stem -> s = s.copy(stem=Received(event.sample,now,g))
            is AacpEvent.Head -> if (s.activePacketFormat != null) s = s.copy(head=Received(event.sample,now,g))
            is AacpEvent.Mode -> { observed(CommandProperty.MODE, PropertyValue.Number(event.value.wire)); s = s.copy(mode = Received(event.value, now, g)) }
            is AacpEvent.ConversationSetting -> { observed(CommandProperty.CONVERSATION, PropertyValue.Number(if (event.enabled) 1 else 2)); s = s.copy(conversation = Received(event.enabled, now, g)) }
            is AacpEvent.ConversationActivity -> s = s.copy(conversationActivity = Received(event.rawStatus, now, g))
            is AacpEvent.Battery -> s = s.copy(batteries = s.batteries + event.components.mapValues { Received(it.value, now, g) })
            is AacpEvent.Information -> {
                event.identity.name?.let { observed(CommandProperty.NAME, PropertyValue.Name(it)) }
                s = s.copy(identity=event.identity, identityReceived=Received(event.identity,now,g),
                    diagnostic=if(event.identity.recognized) "Model identified; physical gate still NV" else "Unknown model; controls unavailable")
            }
            is AacpEvent.Unknown -> s = s.copy(diagnostic = "RX opcode=${event.opcode} bytes=${event.bytes}: ${event.reason}")
        }
        mutable.value = s
    }
    private fun isCurrent(g: Long) = mutable.value.generation == g && session?.isActive == true
    private fun fail(g: Long, step: String, error: Exception?) {
        synchronized(lock) { if (!isCurrent(g)) return }
        val detail = (error as? AacpPlatformException)?.reason ?: error?.javaClass?.simpleName ?: "unknown failure"
        observer.observe(QualificationEvent(QualificationStep.OWNER, QualificationPhase.FAILED, g))
        release(g, AacpStage.ERROR, "$step: $detail; compatibility NV")
    }
    override fun close() = release(null, AacpStage.CLOSED, "Closed; physical gate NV")
    private fun release(expected: Long?, stage: AacpStage, reason: String, expectedStage: AacpStage? = null) {
        val oldSocket: AacpTransport?; val oldJob: Job?
        synchronized(lock) {
            val s = mutable.value
            if (expected != null && s.generation != expected) return
            if (expectedStage != null && s.stage != expectedStage) return
            oldSocket = transport; transport = null; oldJob = session; session = null
            timers.values.forEach { it.cancel() }; timers.clear()
            mutable.value = s.copy(generation = s.generation + 1, stage = stage, activePacketFormat=null, head=null, pending = emptyMap(),
                outcomes = s.outcomes + s.pending.mapValues { CommandOutcome.CLOSED }, diagnostic = reason)
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
