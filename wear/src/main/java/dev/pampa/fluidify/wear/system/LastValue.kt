package dev.pampa.fluidify.wear.system

/**
 * Remembers the one value computed last, and for which key.
 *
 * The tile asks for the cover of the song that is playing on every layout it draws — every press,
 * every redraw — and the answer only changes with the song. A value that was not there yet (null:
 * the cover has not arrived) is never kept, so the next ask looks again.
 */
internal class LastValue<K : Any, V : Any> {
    private var key: K? = null
    private var value: V? = null

    @Synchronized
    fun getOrCompute(forKey: K, compute: () -> V?): V? {
        if (key == forKey) value?.let { return it }
        val computed = compute() ?: return null
        key = forKey
        value = computed
        return computed
    }
}
