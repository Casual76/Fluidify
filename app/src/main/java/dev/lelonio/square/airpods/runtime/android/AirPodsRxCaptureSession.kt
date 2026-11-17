// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.content.Context
import dev.lelonio.square.airpods.runtime.AirPodsRawRxBuffer
import dev.lelonio.square.airpods.runtime.AirPodsRxCapture
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.nio.file.AtomicMoveNotSupportedException
import kotlinx.coroutines.*

/** Development opt-in: external files/airpods-rx.enabled. Bounded current and previous session;
 * no identifier, logcat or disk work on the transport path. Reconnection retires the old writer
 * before rotating, so its delayed finally cannot replace the new session's file. */
internal object AirPodsRxCaptureSession {
    private val fileLock = Any()
    private class Session(val directory: File, val buffer: AirPodsRawRxBuffer) {
        val output = File(directory, "airpods-rx.txt")
        fun export(text: String = buffer.export()) {
            val temporary = File(directory, "airpods-rx.tmp")
            temporary.writeText(text)
            move(temporary, output)
        }
    }
    private var active: Session? = null
    private val starts = java.util.concurrent.atomic.AtomicLong()
    private val pending = java.util.concurrent.atomic.AtomicReference<Session?>(null)
    private fun move(from: File, to: File) {
        try { Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), REPLACE_EXISTING) }
    }
    fun open(context: Context, scope: CoroutineScope): Job? =
        open(context.getExternalFilesDir(null), scope, dev.lelonio.square.BuildConfig.BUILD_TYPE)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun open(directory: File?, scope: CoroutineScope, buildType: String, intervalMs: Long = 1_000,
        io: CoroutineDispatcher = Dispatchers.IO): Job? {
        if (buildType !in setOf("dev", "debug") || directory == null) return null
        val flag = File(directory, "airpods-rx.enabled")
        if (!flag.isFile) return null
        val mine = Session(directory, AirPodsRawRxBuffer())
        val request = starts.incrementAndGet()
        // Ready before the owner can construct/connect: even the first TX is captured while
        // file rotation is queued on IO. Old removals use CAS and cannot remove this buffer.
        AirPodsRxCapture.install(mine.buffer)
        // A reconnect may precede the old IO worker's first turn. Retain its bounded memory
        // snapshot too, so rotation does not depend on the old file already existing.
        val retired = pending.getAndSet(mine)?.let { it to it.buffer.export() }
        return scope.launch(io, start = CoroutineStart.ATOMIC) {
            try {
                synchronized(fileLock) {
                    if (starts.get() != request) return@launch
                    active?.let { old ->
                        runCatching { old.export() }
                        AirPodsRxCapture.remove(old.buffer)
                    }
                    active = null
                    retired?.let { (old, text) -> old.export(text) }
                    if (mine.output.isFile) move(mine.output, File(directory, "airpods-rx.prev.txt"))
                    mine.export()
                    active = mine
                }
                while (isActive && flag.isFile) {
                    delay(intervalMs)
                    val owned = synchronized(fileLock) {
                        if (active !== mine) false else { runCatching { mine.export() }; true }
                    }
                    if (!owned) break
                }
            } catch (_: Exception) {
                // Optional diagnostics never fail the controls owner (including rotation I/O).
            } finally {
                synchronized(fileLock) {
                    AirPodsRxCapture.remove(mine.buffer)
                    if (active === mine) {
                        active = null
                        runCatching { mine.export() }
                    }
                }
            }
        }.also { job -> job.invokeOnCompletion {
            AirPodsRxCapture.remove(mine.buffer)
            pending.compareAndSet(mine, null)
        } }
    }
}
