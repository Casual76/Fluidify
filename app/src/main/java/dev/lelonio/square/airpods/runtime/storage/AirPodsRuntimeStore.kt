// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.storage

/** One process-scoped instance. commit must be durable, synchronous and atomic;
 * false/throw means no authorization, even if a platform cache changed in memory.
 * Cross-process writers are forbidden. Do not implement using apply(). */
interface RuntimePersistence {
    fun read(): ByteArray?
    fun commit(bytes: ByteArray): Boolean
}

class AirPodsRuntimeStore(private val persistence: RuntimePersistence) {
    @Synchronized fun snapshot(): Result<AirPodsRuntimeData> = runCatching { read() }

    /** Also used by the start gate, so config updates cannot overwrite stop/consent. */
    @Synchronized fun <T> transaction(block: (AirPodsRuntimeData) -> Pair<AirPodsRuntimeData, T>): Result<T> = runCatching {
        val before = read()
        val (after, result) = block(before)
        after.validate()
        if (after != before) check(persistence.commit(AirPodsRuntimeCodec.encode(after))) { "AirPods durable commit failed" }
        result
    }

    private fun read(): AirPodsRuntimeData = persistence.read()?.let(AirPodsRuntimeCodec::decode) ?: AirPodsRuntimeData()

    fun select(address: PairAddress): Result<Unit> = transaction { data ->
        // Selection is explicit new setup intent, including reconfiguration of the same pair.
        // Cancel older removal authority atomically with selection, even if later unselected.
        val cancelled = data.pendingCleanup.filter { it.address == address }
        data.copy(selectedPair = address, pairs = data.pairs + (address to (data.pairs[address] ?: PairSettings())),
            pendingCleanup = data.pendingCleanup - cancelled.toSet(),
            cancelledCleanup = (data.cancelledCleanup + cancelled).takeLast(64)) to Unit
    }

    /** One durable transition: retain cleanup destination BEFORE erasing operational intent.
     * Failure leaves selection and its evidence available; caller must stop locally. */
    fun forgetWithCleanup(expected: PairAddress, cleanup: PendingCompanionCleanup): Result<Unit> = transaction { data ->
        require(data.selectedPair == expected && cleanup.address == expected)
        data.copy(selectedPair = null, pairs = data.pairs - expected,
            pendingCleanup = data.pendingCleanup + cleanup,
            start = data.start.copy(enabled = false, suppressUntilExplicitResume = true,
                suppressReason = SuppressReason.UNPAIRED,
                consentGeneration = Math.addExact(data.start.consentGeneration, 1))) to Unit
    }

    fun completeCleanup(cleanup: PendingCompanionCleanup): Result<Unit> = transaction { data ->
        data.copy(pendingCleanup = data.pendingCleanup.filterNot { it == cleanup }) to Unit
    }

    fun configure(address: PairAddress, update: (PairSettings) -> PairSettings): Result<Unit> = transaction { data ->
        val current = requireNotNull(data.pairs[address]) { "Select pair first" }
        val next = update(current)
        // Verification is written only via the separate setup completion API.
        require(next.verification == current.verification)
        data.copy(pairs = data.pairs + (address to next)) to Unit
    }

    /** Caller must supply evidence from INFORMATION/bootstrap/basic RX + platform probe.
     * This API stores the result, it cannot manufacture the evidence. */
    fun recordSetup(address: PairAddress, evidence: PairVerification): Result<Unit> = transaction { data ->
        require(data.selectedPair == address)
        data.copy(pairs = data.pairs + (address to requireNotNull(data.pairs[address]).copy(verification = evidence))) to Unit
    }

    fun migratePoc(cache: LegacyPocSnapshot): Result<Unit> = transaction { data ->
        if (data.pocMigrationCompleted) data to Unit else {
            val address = cache.cachedAddress?.let { runCatching { PairAddress(it) }.getOrNull() }
            val mode = cache.secondMode?.let { name -> QuickMode.entries.firstOrNull { it.name == name } }
            val pairs = if (address != null && address !in data.pairs) data.pairs + (address to PairSettings(secondMode = mode)) else data.pairs
            data.copy(pairs = pairs, pocMigrationCompleted = true) to Unit
        }
    }
}
