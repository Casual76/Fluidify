package dev.pampa.fluidify.wear.link

import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.CommandAck
import dev.pampa.fluidify.wear.protocol.CommandEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One captured intent, awaited at most once. No timeout or null reply authorizes another send. */
class I12CommandRequest internal constructor(
    private val reply: suspend () -> CommandAck?,
    private val admit: (() -> Unit) -> Boolean,
) {
    private val started = AtomicBoolean()

    suspend fun await(): CommandAck? {
        if (!started.compareAndSet(false, true)) return null
        return reply()
    }

    /** Short, synchronous result publication under the same phone-account authority. */
    fun ifCurrent(effect: () -> Unit): Boolean = admit(effect)
}

/** Shared by all command entry points, including transfers outside the player screen. */
internal object I12CommandTimeouts {
    const val TRANSFER_ACK_MS = 11_000L
    const val WRITE_ACK_MS = 10_000L
    private const val COMMAND_ACK_MS = 10_000L // phone command budget 8 s, listener 9 s
    private const val SLOW_ACK_MS = 12_000L

    fun forCommand(command: Command): Long = when (command) {
        is Command.Transfer -> TRANSFER_ACK_MS // requestHere 7 s, consumer 4 s
        is Command.SetLiked, is Command.AddToPlaylist -> WRITE_ACK_MS
        is Command.PlayContext, is Command.AddToQueue, is Command.StartRadio, is Command.PlayQueueIndex,
        Command.Play, Command.TogglePlay -> SLOW_ACK_MS
        else -> COMMAND_ACK_MS
    }
}

/** Transport-independent part of PhoneLink, so races exercise the production send/ACK path. */
internal class I12CommandDelivery(
    private val accounts: PhoneAccountSession,
    private val ids: AtomicLong,
    private val resolveNode: suspend () -> String?,
    private val transmit: suspend (String, CommandEnvelope, () -> Boolean) -> Boolean,
) {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<CommandAck>>()

    fun prepare(command: Command, timeoutMs: Long): I12CommandRequest {
        // Capture before discovery and before PhoneRemote launches its app-scope coroutine.
        val account = accounts.capture()
        val envelope = account?.let { CommandEnvelope(ids.incrementAndGet(), command, it.session) }
        return I12CommandRequest(
            reply = {
                if (account == null || envelope == null || !accounts.isCurrent(account)) null
                else deliver(envelope, timeoutMs) { accounts.isCurrent(account) }
            },
            admit = { effect -> account != null && accounts.useIfCurrent(account, effect) },
        )
    }

    private suspend fun deliver(envelope: CommandEnvelope, timeoutMs: Long, current: () -> Boolean): CommandAck? {
        val waiter = CompletableDeferred<CommandAck>()
        pending[envelope.id] = waiter
        try {
            // The budget includes discovery and transport, not just the last waiter.await().
            return withTimeoutOrNull(timeoutMs) {
                val node = resolveNode() ?: return@withTimeoutOrNull null
                currentCoroutineContext().ensureActive()
                if (!current() || !transmit(node, envelope, current)) return@withTimeoutOrNull null
                waiter.await().takeIf { current() }
            }
        } finally {
            pending.remove(envelope.id, waiter)
            waiter.cancel()
        }
    }

    fun onAck(ack: CommandAck) {
        pending.remove(ack.id)?.complete(ack)
    }
}
