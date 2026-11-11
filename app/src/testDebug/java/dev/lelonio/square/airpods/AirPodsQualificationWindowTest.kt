// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.app.Application
import dev.lelonio.square.airpods.runtime.android.*
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PREPARED ONLY. Clock and logging regressions, not physical qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsQualificationWindowTest {
    private val pair = PairAddress("12:34:56:78:9A:BC")
    @Test fun durableOptInIsBoundToBootBuildConsentAgeAndClock() {
        val w = DebugQualificationWindow(pair, "build", 2, 100, 900_100, 1000, 7)
        assertTrue(w.valid("build", 2, 200, 1100))
        assertFalse(w.copy(consent = -1).valid("build", 2, 200, 1100))
        assertFalse(w.copy(enabled = false).valid("build", 2, 200, 1100))
        assertFalse(w.valid("other", 2, 200, 1100))
        assertFalse(w.valid("build", 3, 200, 1100))
        assertFalse(w.valid("build", 2, 900_100, 901_000))
        assertFalse(w.valid("build", 2, 99, 999))
        assertFalse(w.valid("build", 2, 200, 99_000))
    }
    @Test fun recreationIssuesFreshPermitWithOriginalDeadlineAndNoExtension() {
        var now = 100L
        val first = AirPodsFunctionalTestPermit.qualification({ now }, 600_100)
        now += 1000
        val recreated = AirPodsFunctionalTestPermit.qualification({ now }, 600_100)
        assertNotSame(first, recreated)
        first.revoke(); assertTrue(recreated.allowed(false))
        assertFalse(recreated.armBackground(900_000, true))
        now = 600_100; assertFalse(recreated.allowed(true)); assertFalse(recreated.allowed(false))
    }
    @Test fun uiHandoffIsOneShortExplicitTransition() {
        var now = 1L; val p = AirPodsFunctionalTestPermit.issue { now }
        assertFalse(p.handoff(false)); assertTrue(p.handoff(true)); assertFalse(p.handoff(true))
        assertTrue(p.allowed(false)); now += 3000; assertFalse(p.allowed(false))
    }
    @Test fun overlappingUiHandoffKeepsVisibilityButRetiresQueuedActions() {
        val foreground = dev.lelonio.square.airpods.runtime.AirPodsForegroundState().apply { value = true }
        val old = requireNotNull(foreground.captureForeground())
        foreground.invalidateForeground()
        assertTrue(foreground.value)
        assertFalse(foreground.isCurrentForeground(old))
        assertNotNull(foreground.captureForeground())
    }
    @Test fun audioReceiptIsRevocableAndCannotExtendItsObservedSetupAge() {
        var now = 100L
        val receipt = AirPodsAudioSetupReceipt(pair, "build", 7, now) { now }
        assertTrue(receipt.valid(pair, "build")); assertFalse(receipt.valid(pair, "other"))
        now += AirPodsFunctionalTestPermit.MAX_WINDOW
        assertFalse(receipt.valid(pair, "build"))
        now = 100; receipt.revoke(); assertFalse(receipt.valid(pair, "build"))
    }
    @Test fun boundedLossDeletionAndRecreationStayInsufficientAndNeverPass() {
        val evidence = DebugQualificationEvidence(2)
        repeat(3) { evidence.record(QualificationEvent(QualificationStep.TX, QualificationPhase.COMPLETED, sequence = it.toLong())) }
        val export = evidence.export()
        assertTrue(export.contains("lost=1")); assertTrue(export.contains("INSUFFICIENT"))
        assertFalse(export.contains("PASS")); assertTrue(export.contains("physical=NV"))
        assertFalse(export.contains(pair.value))
        evidence.clear(); assertTrue(evidence.export().contains("deleted=2"))
        val recreated = DebugQualificationEvidence(); recreated.processRecreated()
        assertTrue(recreated.export().contains("INSUFFICIENT"))
    }
}
