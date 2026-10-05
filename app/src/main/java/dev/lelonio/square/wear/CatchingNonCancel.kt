package dev.lelonio.square.wear

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] that lets cancellation through.
 *
 * `runCatching` catches everything, a [CancellationException] included, and a coroutine that has
 * been cancelled while suspended inside it comes out as an ordinary failure: the caller logs it,
 * carries on, and the work that should have stopped (a transfer the watch walked away from, a
 * request whose budget ran out) goes on running, or the next step runs on a scope that is already
 * dead. Wherever the block calls something that suspends, this is the one to use; for a block that
 * cannot suspend `runCatching` is as good.
 *
 * Inline, so the block may itself call suspend functions from a suspend caller, exactly as with
 * `runCatching`.
 */
internal inline fun <T> catchingNonCancel(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
