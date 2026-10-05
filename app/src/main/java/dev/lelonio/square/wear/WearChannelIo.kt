package dev.lelonio.square.wear

import com.google.android.gms.wearable.ChannelClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import dev.pampa.fluidify.wear.protocol.WearCodec
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The line-and-bytes framing the phone's channel servers ([WatchFileServer], [WatchThumbServer])
 * share: one JSON object per line, then raw bytes. The watch's readers parse exactly this, so the
 * two servers must not drift apart.
 */
internal object WearChannelIo {

    /**
     * One line, up to the newline, byte by byte without buffering past it: whatever follows the
     * newline on the same stream is payload. Null at the end of the stream or when the line is
     * longer than [maxLine] (a peer that never sends a newline must not grow this without limit).
     */
    fun readLine(input: InputStream, maxLine: Int): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
            bytes.write(next)
            if (bytes.size() > maxLine) return null
        }
    }

    /** Writes [value] as one JSON line; [flush] when nothing else is about to follow at once. */
    fun <T> writeLine(out: OutputStream, serializer: KSerializer<T>, value: T, flush: Boolean = true) {
        out.write(WearCodec.encode(serializer, value))
        out.write('\n'.code)
        if (flush) out.flush()
    }
}

/**
 * Ends a channel the phone has just written to, without cutting the watch off mid-read.
 *
 * Closing a [ChannelClient] channel is not "I am done writing": it tears the channel down in both
 * directions, and bytes still in flight over Bluetooth (the tail of the last megabyte, the final
 * newline of a header) can be lost with it. The watch then sees "Channel closed unexpectedly
 * before stream was finished" on a transfer that, on the phone's side, finished. The orderly end
 * is the one a socket has: closing the *output stream* (which the callers do with `use`, and
 * which the watch reads as end of file), then waiting for the watch to close its side once it has
 * read everything, and only then closing the channel for good. The watch always does close, in
 * its own `finally`, so the wait is normally a few milliseconds; a watch that has gone away
 * costs [PEER_GRACE_MS] at most, and the close is made regardless.
 *
 * Use: [start] before the first byte is written (so the peer's close cannot be missed), [finish]
 * in the `finally`, with whether the payload was handed over completely.
 */
internal class ChannelWrapUp(
    private val channels: ChannelClient,
    private val channel: ChannelClient.Channel,
) {
    private val peerClosed = CompletableDeferred<Unit>()
    @Volatile private var listening = false
    private val callback = object : ChannelClient.ChannelCallback() {
        override fun onChannelClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
            peerClosed.complete(Unit)
        }
    }

    suspend fun start() {
        listening = catchingNonCancel { channels.registerChannelCallback(channel, callback).await() }.isSuccess
    }

    /**
     * Waits (when [delivered]) for the watch to close, then closes. Never throws, except for
     * cancellation of the caller, which is why callers run it in `NonCancellable`.
     */
    suspend fun finish(delivered: Boolean) {
        if (delivered) {
            if (listening) {
                withTimeoutOrNull(PEER_GRACE_MS) { peerClosed.await() }
            } else {
                // The close cannot be seen: a short fixed wait is the next best thing.
                delay(UNLISTENED_GRACE_MS)
            }
        }
        if (listening) catchingNonCancel { channels.unregisterChannelCallback(channel, callback).await() }
        catchingNonCancel { channels.close(channel).await() }
    }

    private companion object {
        /** The longest the phone waits for a watch to say it has read it all. */
        const val PEER_GRACE_MS = 4_000L

        /** When the phone could not even listen for the watch's close. */
        const val UNLISTENED_GRACE_MS = 1_000L
    }
}
