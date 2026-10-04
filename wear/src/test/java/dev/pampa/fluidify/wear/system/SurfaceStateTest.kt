package dev.pampa.fluidify.wear.system

import dev.pampa.fluidify.wear.ui.common.NoticeWindow
import org.junit.Assert.*
import org.junit.Test

class SurfaceStateTest {
    @Test fun phoneAndWatchComparisonsRemainIndependent() {
        val signatures = SurfaceSignatures()
        assertTrue(signatures.changed("phone:a", false))
        assertTrue(signatures.changed("watch:b", true))
        repeat(10) { assertFalse(signatures.changed("phone:a", false)); assertFalse(signatures.changed("watch:b", true)) }
        assertTrue(signatures.changed("watch:c", true))
        assertFalse(signatures.changed("phone:a", false))
    }
    @Test fun clickRedeliveryAndProcessRestartDoNotRepeatASkip() {
        var saved = emptyList<String>()
        val taps = TileTapHistory(save = { saved = it })
        assertTrue(taps.claim("next@1"))
        assertFalse(taps.claim("next@1"))
        assertTrue(taps.claim("next@2"))
        assertFalse(taps.claim("next@1"))
        assertFalse(TileTapHistory(saved).claim("next@2"))
    }
    @Test fun repeatedErrorDoesNotExtendItsWindow() {
        val notice = NoticeWindow(2_000)
        assertTrue(notice.accept("offline", 0))
        repeat(19) { assertFalse(notice.accept("offline", (it + 1) * 100L)) }
        assertTrue(notice.accept("offline", 2_000))
        assertTrue(notice.accept("other-error", 2_100))
    }
}
