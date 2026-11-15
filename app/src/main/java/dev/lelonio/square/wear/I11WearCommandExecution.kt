package dev.lelonio.square.wear

import dev.pampa.fluidify.wear.protocol.Command
import kotlinx.coroutines.withTimeoutOrNull

private data class I11AppliedCommand(val error: String?)

/** Null is successful application, distinct from the timeout's null result. */
internal suspend fun i11WearCommandResult(
    command: Command,
    budgetMs: Long,
    apply: suspend () -> String?,
): String? {
    val applied = withTimeoutOrNull(budgetMs) { I11AppliedCommand(apply()) }
    return if (applied == null) PhoneWearBridge.commandFailure(command) else applied.error
}
