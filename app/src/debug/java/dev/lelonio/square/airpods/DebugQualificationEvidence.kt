// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.os.Process
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** No disk/network on the transport path. Export is a separate explicit UI gesture. */
internal class DebugQualificationEvidence(
    private val limit: Int = 2048,
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
    private val utcNow: () -> Long = System::currentTimeMillis,
) : AirPodsQualificationObserver {
    init { require(limit in 1..4096) }
    private val ownerAlias = UUID.randomUUID().toString()
    private val rows = ArrayDeque<String>()
    private var sequence = 0L
    private var lost = 0L
    private var deleted = 0L
    private var recreated = false
    private var inFlight = 0
    private var currentGeneration = 0L
    private var transportClosed = false
    private data class ConfigRx(val value: Int, val elapsedMs: Long, val utcMs: Long, val generation: Long)
    private data class EarRx(val normalized: Int, val firstRaw: Int?, val secondRaw: Int?,
        val elapsedMs: Long, val utcMs: Long, val generation: Long)
    private var configRx: ConfigRx? = null
    private var earRx: EarRx? = null
    @Synchronized fun processRecreated() { recreated = true }
    private var consent = 0L
    private val mutable = MutableStateFlow(0L)
    val changes = mutable.asStateFlow()
    @Synchronized fun consent(value: Long) { consent = value }
    @Synchronized override fun record(event: QualificationEvent) {
        val elapsedMs = elapsedNow()
        val utcMs = utcNow()
        // Projection and rows share one monitor. No controller read or hardware default is used.
        if (event.generation > currentGeneration) currentGeneration = event.generation
        if (event.generation == currentGeneration) {
            if (event.step == QualificationStep.CONSTRUCT && event.phase == QualificationPhase.REQUESTED)
                transportClosed = false
            if (event.step == QualificationStep.CLOSE) transportClosed = true
            if (!transportClosed && event.step == QualificationStep.RX && event.phase == QualificationPhase.OBSERVED) {
                if (event.flags == 9 && event.property == CommandProperty.EAR_DETECTION_CONFIG && event.value != null && event.value in 1..2)
                    configRx = ConfigRx(requireNotNull(event.value), elapsedMs, utcMs, event.generation)
                if (event.flags == 6 && event.value != null && event.value in 0..4)
                    earRx = EarRx(requireNotNull(event.value), event.values.getOrNull(0)?.takeIf { it in 0..255 },
                        event.values.getOrNull(1)?.takeIf { it in 0..255 }, elapsedMs, utcMs, event.generation)
            }
        }
        if (event.step == QualificationStep.OWNER && event.sequence > 0) consent = event.sequence
        if (event.step == QualificationStep.PRESENCE && event.token != null) consent = event.token
        val work = (event.flags == 0 && event.step in setOf(QualificationStep.CONSTRUCT, QualificationStep.CONNECT, QualificationStep.TX, QualificationStep.CLOSE)) ||
            (event.flags == 1 && event.step == QualificationStep.RX_SDU)
        if (work && event.phase == QualificationPhase.REQUESTED) inFlight++
        if (work && (event.phase in setOf(QualificationPhase.COMPLETED, QualificationPhase.FAILED) ||
            event.flags == 1 && event.phase == QualificationPhase.UNCONFIRMED)) inFlight = (inFlight - 1).coerceAtLeast(0)
        val ingressDetail = if (event.step != QualificationStep.RX) "" else when (event.flags) {
            31 -> ";ingressOpcode=${event.values.getOrNull(3) ?: -1};ingressBytes=${event.values.getOrNull(4) ?: 0};" +
                "ingressReason=${DebugIngressReason.fromCode(event.values.getOrNull(5)).name}"
            33 -> ";ingressReason=INGRESS_PRIVATE_PERSIST_FAILED"
            34 -> ";ingressReason=INGRESS_HOOK_FAILED"
            else -> ""
        }
        val line = "id=${++sequence};utcMs=$utcMs;elapsedMs=$elapsedMs;" +
            "owner=$ownerAlias;pair=PAIR_1;uid=${Process.myUid()};pid=${Process.myPid()};consent=$consent;" +
            "socket=GEN_${event.generation};step=${event.step};phase=${event.phase};generation=${event.generation};" +
            "sequence=${event.sequence};property=${event.property};value=${event.value};token=${event.token};flags=${event.flags};values=${event.values.take(9).joinToString(",")}" + ingressDetail
        if (rows.size == limit) { rows.removeFirst(); lost++ }
        rows.addLast(line)
        mutable.value = sequence
    }
    @Synchronized fun clear() {
        deleted += rows.size; rows.clear(); configRx = null; earRx = null; mutable.value = ++sequence
    }
    @Synchronized fun export(): String = snapshot().journal
    /** One linearization point for counters, rows and the RX-only projection. Call from IO. */
    @Synchronized fun snapshot(): DebugQualificationSnapshot {
        val journal = "TEST;physical=NV;appVersionCode=${dev.lelonio.square.BuildConfig.VERSION_CODE};osApi=${android.os.Build.VERSION.SDK_INT};productConformance=false;lost=$lost;deleted=$deleted;processRecreated=$recreated;inFlight=$inFlight;" +
        "evidence=${if(lost == 0L && deleted == 0L && !recreated && inFlight == 0) "UNQUALIFIED" else "INSUFFICIENT"};" +
        "completedWriteIsNotAck=true;observedMatchIsCorrelation=true\n" + rows.joinToString("\n")
        val config = configRx
        val ear = earRx
        val configCurrent = !transportClosed && config?.generation == currentGeneration
        val earCurrent = !transportClosed && ear?.generation == currentGeneration
        val configJson = config?.let {
            """{"value":${it.value},"recordedElapsedMs":${it.elapsedMs},"recordedUtcMs":${it.utcMs},"generation":${it.generation}}"""
        } ?: "null"
        val earJson = ear?.let {
            """{"normalized":${it.normalized},"firstRaw":${it.firstRaw},"secondRaw":${it.secondRaw},"recordedElapsedMs":${it.elapsedMs},"recordedUtcMs":${it.utcMs},"generation":${it.generation}}"""
        } ?: "null"
        // Fixed keys and numeric/boolean/null fields only; no name, identity, account or wire dump.
        val projection = """{"schema":1,"physical":"NV","productConformance":false,
"snapshotSequence":$sequence,"journalRows":${rows.size},"currentGeneration":$currentGeneration,"transportClosed":$transportClosed,
"earConfig":{"sourceRx":${config != null},"currentValue":${if (configCurrent) config?.value else null},"lastRx":$configJson},
"ear":{"sourceRx":${ear != null},"current":$earCurrent,"lastRx":$earJson}}
"""
        return DebugQualificationSnapshot(sequence, rows.size, journal, projection)
    }
}

internal data class DebugQualificationSnapshot(val sequence: Long, val rows: Int,
    val journal: String, val earProjection: String)
