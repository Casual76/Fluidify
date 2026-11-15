package dev.pampa.fluidify.wear.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.pampa.fluidify.wear.protocol.AccountState
import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.CommandAck
import dev.pampa.fluidify.wear.protocol.CommandEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Synthetic accounts and transport; the production preparation/delivery/ACK code stays real. */
internal class I12CommandHarness : CommandChannel {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    val accounts = PhoneAccountSession(context.getSharedPreferences("i12-${UUID.randomUUID()}", Context.MODE_PRIVATE))
    override val status = MutableStateFlow(LinkStatus.CONNECTED)
    val sent = mutableListOf<CommandEnvelope>()
    val timeouts = mutableListOf<Long>()
    var discover: suspend () -> String? = { "phone" }
    var afterSend: suspend (CommandEnvelope) -> Unit = {}
    val delivery = I12CommandDelivery(accounts, AtomicLong(100),
        resolveNode = { discover() },
        transmit = { _, envelope, current ->
            if (!current()) false else {
                sent += envelope
                afterSend(envelope)
                true
            }
        },
    )

    init { account("a", "a-1", 1) }

    fun account(namespace: String, session: String, at: Long) {
        accounts.accept(AccountState(true, "synthetic-$namespace", at, session, namespace))
    }

    fun ack(envelope: CommandEnvelope = sent.last(), ok: Boolean = true) =
        delivery.onAck(CommandAck(envelope.id, ok, error = if (ok) null else "transfer"))

    override fun prepare(command: Command, timeoutMs: Long): I12CommandRequest {
        timeouts += timeoutMs
        return delivery.prepare(command, timeoutMs)
    }

    override suspend fun send(command: Command) = send(command, I12CommandTimeouts.forCommand(command))
    override suspend fun send(command: Command, timeoutMs: Long) = prepare(command, timeoutMs).await()
}
