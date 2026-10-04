package dev.pampa.fluidify.wear.system

/** A renderer may redeliver an older click even after another click or a process restart. */
class TileTapHistory(initial: Collection<String> = emptyList(), private val save: (List<String>) -> Unit = {}) {
    private val handled = LinkedHashSet(initial)
    @Synchronized fun claim(id: String): Boolean {
        if (!handled.add(id)) return false
        while (handled.size > 64) handled.remove(handled.first())
        save(handled.toList())
        return true
    }
}
