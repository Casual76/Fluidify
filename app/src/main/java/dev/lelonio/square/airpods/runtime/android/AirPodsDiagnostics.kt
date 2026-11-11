// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android
import dev.lelonio.square.airpods.AacpStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiagnosticKind { STAGE, ERROR, STOP, SNAPSHOT }
data class AirPodsDiagnosticRecord(val id: Long, val atMillis: Long, val kind: DiagnosticKind, val stage: AacpStage)
/** Only typed enums and local timestamps enter this bounded process-local log. No packet/identity API. */
class AirPodsSanitizedDiagnostics(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var sequence = 0L
    private val active = MutableStateFlow(false)
    val collecting = active.asStateFlow()
    private val mutable = MutableStateFlow<List<AirPodsDiagnosticRecord>>(emptyList())
    val records = mutable.asStateFlow()
    @Synchronized fun startCollection(): Boolean {
        if (active.value) return false
        active.value = true
        return true
    }
    @Synchronized fun stopCollection() { active.value = false }
    @Synchronized fun record(kind: DiagnosticKind, stage: AacpStage) {
        if (!active.value) return
        mutable.value = (mutable.value + AirPodsDiagnosticRecord(++sequence, clock(), kind, stage)).takeLast(100)
    }
    @Synchronized fun delete(id: Long) { mutable.value = mutable.value.filterNot { it.id == id } }
    @Synchronized fun clear() { mutable.value = emptyList() }
    fun export(id: Long? = null): String = records.value.filter { id == null || it.id == id }.joinToString("\n") {
        "${it.id} ${it.atMillis} ${it.kind} ${it.stage}"
    }
}
