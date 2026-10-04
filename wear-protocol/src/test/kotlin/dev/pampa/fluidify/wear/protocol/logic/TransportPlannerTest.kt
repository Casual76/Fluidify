package dev.pampa.fluidify.wear.protocol.logic

import dev.pampa.fluidify.wear.protocol.logic.Transport.PHONE_FILE
import dev.pampa.fluidify.wear.protocol.logic.Transport.PHONE_STAGED
import dev.pampa.fluidify.wear.protocol.logic.Transport.WATCH_ALONE
import dev.pampa.fluidify.wear.protocol.logic.Transport.WATCH_WITH_PHONE_KEY
import dev.pampa.fluidify.wear.protocol.logic.TransferPreference.BLUETOOTH_FIRST
import dev.pampa.fluidify.wear.protocol.logic.TransferPreference.WIFI_FIRST
import org.junit.Assert.assertEquals
import org.junit.Test

/** The user's download rules, as a table. */
class TransportPlannerTest {

    private data class Row(
        val reachable: Boolean,
        val hasFile: Boolean,
        val sameQuality: Boolean,
        val preference: TransferPreference,
        val expected: List<Transport>,
    )

    private val table = listOf(
        // The phone has the same file: the setting decides, each way falling back to the other.
        Row(true, true, true, WIFI_FIRST, listOf(WATCH_WITH_PHONE_KEY, PHONE_FILE)),
        Row(true, true, true, BLUETOOTH_FIRST, listOf(PHONE_FILE, WATCH_WITH_PHONE_KEY)),
        // The phone has it at another quality: it would be fetched again either way, so the
        // watch's Wi-Fi first, then the phone fetching it at the watch's quality.
        Row(true, true, false, WIFI_FIRST, listOf(WATCH_ALONE, PHONE_STAGED)),
        Row(true, true, false, BLUETOOTH_FIRST, listOf(PHONE_STAGED, WATCH_ALONE)),
        // The phone does not have it.
        Row(true, false, false, WIFI_FIRST, listOf(WATCH_ALONE, PHONE_STAGED)),
        Row(true, false, true, BLUETOOTH_FIRST, listOf(PHONE_STAGED, WATCH_ALONE)),
        // The phone is away: only the watch's own network.
        Row(false, true, true, WIFI_FIRST, listOf(WATCH_ALONE)),
        Row(false, false, false, BLUETOOTH_FIRST, listOf(WATCH_ALONE)),
    )

    @Test
    fun everyRowOfTheRules() {
        for (row in table) {
            val plan = TransportPlanner.plan(TransportFacts(row.reachable, row.hasFile, row.sameQuality, row.preference))
            assertEquals("$row", row.expected, plan)
        }
    }

    @Test
    fun onlyTheWatchsOwnAttemptsNeedItsNetwork() {
        assertEquals(
            listOf(WATCH_WITH_PHONE_KEY, WATCH_ALONE),
            Transport.entries.filter(TransportPlanner::needsWatchNetwork),
        )
    }
}
