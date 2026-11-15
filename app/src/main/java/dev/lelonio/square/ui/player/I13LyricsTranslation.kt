package dev.lelonio.square.ui.player

import dev.lelonio.square.data.Lyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class I13TranslationState(
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val machine: List<String>? = null,
    val unchanged: Boolean = false,
)

/** One document/language owner; cached answers retain the original line indices. */
internal class I13LyricsTranslation(
    private val lyrics: Lyrics,
    private val target: String,
    private val scope: CoroutineScope,
    private val translate: suspend (List<String>, String) -> List<String>?,
) {
    private val hasOwn = lyrics.lines.any { !it.translation.isNullOrBlank() }
    private val mutable = MutableStateFlow(I13TranslationState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun setEnabled(enabled: Boolean) {
        val ticket = ++generation
        job?.cancel()
        job = null
        val cached = mutable.value.machine
        mutable.value = mutable.value.copy(enabled = enabled, busy = false, failed = false)
        if (!enabled || hasOwn || cached != null) return
        mutable.value = mutable.value.copy(busy = true)
        job = scope.launch {
            try {
                val answer = translate(lyrics.lines.map { it.text }, target)
                // A blocking provider can return after cancellation; it has no publication right.
                ensureActive()
                if (ticket != generation) return@launch
                if (answer == null || answer.size != lyrics.lines.size) {
                    mutable.value = I13TranslationState(failed = true)
                } else {
                    mutable.value = I13TranslationState(enabled = true, machine = answer.toList(),
                        unchanged = answer.all { it.isBlank() })
                }
            } catch (cancelled: CancellationException) {
                if (ticket == generation) mutable.value = mutable.value.copy(enabled = false, busy = false)
                throw cancelled
            } catch (_: Exception) {
                if (ticket == generation) mutable.value = I13TranslationState(failed = true)
            } finally {
                if (ticket == generation) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    fun dispose() {
        generation++
        job?.cancel()
        job = null
        mutable.value = mutable.value.copy(enabled = false, busy = false)
    }
}
