package dev.lelonio.square.playback

import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeout

/** One bounded acquisition, including retirement. Release also covers futures completing late. */
internal suspend fun <C, T> i06WithLeasedController(
    gate: PlaybackConnectionGate,
    timeoutMs: Long,
    connect: () -> ListenableFuture<C>,
    release: (ListenableFuture<C>) -> Unit,
    isCurrent: () -> Boolean,
    command: suspend (C) -> T,
): T = withTimeout(timeoutMs) {
    gate.withClient {
        if (!isCurrent()) throw CancellationException("Playback account changed")
        val future = connect()
        try {
            val controller = future.await()
            if (!isCurrent()) throw CancellationException("Playback account changed")
            command(controller)
        } finally { release(future) }
    }
}
