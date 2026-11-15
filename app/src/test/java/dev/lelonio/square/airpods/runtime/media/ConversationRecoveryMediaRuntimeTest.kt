// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/** Real core/coordinator/C6 provider with independently ordered profile/state/volume callbacks.
 * Platform facts and renderer receipts are simulated; this does not qualify physical AirPods.
 */
class ConversationRecoveryMediaRuntimeTest {
    private class Queue : MediaRuntimeQueue {
        val work = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<() -> Unit>()
        override fun post(block: () -> Unit) { work.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit): Closeable {
            timers.add(block)
            return Closeable { timers.remove(block) }
        }
        fun drain() {
            var count = 0
            while (work.isNotEmpty()) {
                check(++count < 1_000)
                work.removeFirst()()
            }
        }
        fun fire() { val due = timers.toList(); timers.clear(); due.forEach { it() } }
    }

    private data class Command(val action: MediaAction, val value: Int?, val id: Long?)

    private class Env(val external: Boolean, private val clock: AutomationClock) : MediaRuntimeEnvironment {
        val provider = ExternalBestEffortProvider(clock)
        var main = MediaCandidate(MediaTarget(if (external) "other.music" else "fluidify",
            "session", 1, if (external) "unverified" else "pair-route", "account"),
            Playback.PLAYING, MediaVolume(50, 100), !external, true, !external, false, false)
        var facts = ExternalMediaFacts(true, true, true, true, "pair-route", false)
        var present = true
        var deferVolumeReceipt = false
        var pauseProfileFirst = false
        var resumeProfileDelayed = false
        var pausedAt = 0L
        var observer: (() -> Unit)? = null
        var retained: MediaTarget? = null
        var beforeRead: (() -> Unit)? = null
        val gestures = mutableListOf<Pair<MediaTarget, AirPodsMediaGesture>>()
        val sent = mutableListOf<Command>()
        val pendingVolumes = ArrayDeque<Command>()
        override fun configureExternal(enabled: Boolean) = provider.configure(enabled)
        override fun retainAutomation(target: MediaTarget?) {
            retained = target
            provider.retainAutomation(target)
        }
        override fun read(): List<MediaCandidate> {
            val hook = beforeRead; beforeRead = null; hook?.invoke()
            return provider.candidates(listOfNotNull(main.takeIf { present }), facts)
        }
        override fun readPhoneVolume() = main.volume?.let { PhoneStreamVolume("pair", it) }
        override fun writePhoneVolume(expected: PhoneStreamVolume, value: Int): Boolean {
            if (readPhoneVolume() != expected) return false
            main = main.copy(volume = MediaVolume(value, expected.volume.maximum))
            signal()
            return true
        }
        override fun observe(changed: () -> Unit): Closeable {
            observer = changed
            return Closeable { if (observer === changed) observer = null }
        }
        fun signal() { observer?.invoke() }
        private fun callback(state: Playback, updatedAt: Long = clock.nowMillis()) {
            // Android rechecks the candidate before passing the callback to the provider.
            read()
            if (external) provider.currentTarget()?.let {
                provider.playbackObserved(it, state, updatedAt)
            }
            signal()
        }
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            val current = read().singleOrNull { it.target == target } ?: return false
            if (!current.permitted || !current.routedToPair || current.callActive) return false
            val command = Command(action, value, effectId)
            sent.add(command)
            if (external) provider.requested(current, action, value, effectId)
            when (action) {
                MediaAction.PAUSE -> {
                    facts = facts.copy(exactPairStreaming = false)
                    if (pauseProfileFirst) { read(); signal() }
                    pausedAt = clock.nowMillis()
                    main = main.copy(playback = Playback.PAUSED,
                        observedPauseEffect = effectId.takeUnless { external })
                    callback(Playback.PAUSED, pausedAt)
                }
                MediaAction.PLAY -> {
                    main = main.copy(playback = Playback.PLAYING)
                    facts = facts.copy(exactPairStreaming = !resumeProfileDelayed)
                    callback(Playback.PLAYING)
                }
                MediaAction.SET_VOLUME -> {
                    main = main.copy(volume = MediaVolume(value!!, 100))
                    if (deferVolumeReceipt) pendingVolumes.addLast(command) else receipt(command)
                }
            }
            return true
        }
        private fun receipt(command: Command) {
            if (external) {
                read()
                provider.currentTarget()?.let { provider.volumeObserved(it, command.value!!) }
            } else main = main.copy(observedVolumeEffect = command.id)
            signal()
        }
        fun completeVolume() = receipt(pendingVolumes.removeFirst())
        fun duplicatePaused() = callback(Playback.PAUSED, pausedAt)
        fun manualPause() {
            if (external) callback(Playback.PAUSED) else {
                main = main.copy(transportRevision = main.transportRevision + 1)
                signal()
            }
        }
        fun resumeStreaming() { facts = facts.copy(exactPairStreaming = true); signal() }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture): Boolean {
            if (read().none { it.target == target }) return false
            gestures.add(target to action)
            if (external) provider.manualTransport(target)
            else main = main.copy(transportRevision = main.transportRevision + 1)
            val state = when (action) {
                AirPodsMediaGesture.PAUSE -> Playback.PAUSED
                AirPodsMediaGesture.PLAY -> Playback.PLAYING
                AirPodsMediaGesture.TOGGLE_PLAYBACK ->
                    if (main.playback == Playback.PLAYING) Playback.PAUSED else Playback.PLAYING
                else -> main.playback
            }
            main = main.copy(playback = state)
            facts = facts.copy(exactPairStreaming = state == Playback.PLAYING)
            signal()
            return true
        }
    }

    private class Rig(external: Boolean, pause: Boolean = true) {
        var now = 100L
        val queue = Queue()
        val clock = AutomationClock { now }
        val env = Env(external, clock)
        val core = MediaRuntimeCore(env, queue, clock)
        init {
            core.start(AutomationSession("pair", 1), HostSettings(automationsEnabled = true,
                fluidifyBackgroundEnabled = true, otherAppsEnabled = external,
                conversationPause = pause, conversationDuck = true, conversationVolumePercent = 40))
            core.visible(true)
            queue.drain()
        }
        fun conversation(active: Boolean) { core.conversation(active); queue.drain() }
        fun step(millis: Long) { now += millis; queue.fire(); queue.drain() }
        fun volumes() = env.sent.filter { it.action == MediaAction.SET_VOLUME }.map { it.value }
        fun plays() = env.sent.count { it.action == MediaAction.PLAY }
    }

    @Test fun longConversationAndIdenticalPausedRedeliveryRetainOwnedPauseAndOriginalVolume() {
        for (external in listOf(false, true)) {
            val r = Rig(external)
            r.conversation(true); r.step(300)
            assertEquals(listOf(20), r.volumes())
            assertTrue("confirmed duck is idle", r.queue.timers.isEmpty())
            repeat(20) {
                r.now += 30_000
                if (external) r.env.duplicatePaused() else r.env.signal()
                r.queue.drain()
                r.conversation(true)
                assertTrue("long conversation must not poll", r.queue.timers.isEmpty())
            }
            r.conversation(false); r.step(300)
            assertEquals("external=$external", listOf(20, 50), r.volumes())
            assertEquals(1, r.plays())
            assertTrue(r.queue.timers.isEmpty())
        }
    }

    @Test fun conversationEndBetweenAppliedVolumeAndReceiptWaitsThenRestores() {
        for (external in listOf(false, true)) for (pause in listOf(false, true)) {
            val r = Rig(external, pause)
            r.env.deferVolumeReceipt = true
            r.conversation(true); r.step(150)
            assertEquals(35, r.env.main.volume!!.current)
            r.conversation(false)
            r.step(100)
            assertEquals("no uncorroborated restore", listOf(35), r.volumes())
            r.env.deferVolumeReceipt = false
            r.env.completeVolume(); r.queue.drain(); r.step(300)
            assertEquals("external=$external pause=$pause", listOf(35, 50), r.volumes())
            assertEquals(if (pause) 1 else 0, r.plays())
            assertTrue(r.queue.timers.isEmpty())
        }
    }

    @Test fun newSpeechDuringAppliedUnconfirmedRestoreKeepsFirstConversationBaseline() {
        for (external in listOf(false, true)) {
            val r = Rig(external, pause = false)
            r.conversation(true); r.step(300)
            r.env.deferVolumeReceipt = true
            r.conversation(false); r.step(150)
            assertEquals(listOf(20, 35), r.volumes())
            r.conversation(true); r.step(100)
            assertEquals(listOf(20, 35), r.volumes())
            r.env.deferVolumeReceipt = false
            r.env.completeVolume(); r.queue.drain(); r.step(300)
            r.conversation(false); r.step(300)
            assertEquals("external=$external", listOf(20, 35, 20, 50), r.volumes())
        }
    }

    @Test fun ownPauseProfileMayStopBeforeMediaSessionPausedCallback() {
        val r = Rig(external = true)
        r.env.pauseProfileFirst = true
        r.conversation(true); r.step(300)
        r.now += 600_000
        r.conversation(false); r.step(300)
        assertEquals(listOf(20, 50), r.volumes())
        assertEquals(1, r.plays())
    }

    @Test fun ownResumeMediaSessionMayPlayBeforeA2dpStreamingCallback() {
        val r = Rig(external = true)
        r.conversation(true); r.step(300)
        r.env.resumeProfileDelayed = true
        r.conversation(false); r.step(150)
        assertEquals(listOf(20, 35), r.volumes())
        assertEquals(RouteEvidence.EXTERNAL_HISTORICAL_INFERENCE, r.env.read().single().routeEvidence)
        r.env.resumeStreaming(); r.queue.drain(); r.step(150)
        assertEquals(listOf(20, 35, 50), r.volumes())
        assertEquals(RouteEvidence.EXTERNAL_STREAMING_INFERENCE, r.env.read().single().routeEvidence)
        assertTrue(r.queue.timers.isEmpty())
    }

    @Test fun sameOrderingWithoutResumedStreamingStillExpiresAndDoesNotRestoreAfterward() {
        val r = Rig(external = true)
        r.conversation(true); r.step(300)
        r.env.resumeProfileDelayed = true
        r.conversation(false); r.step(2_000)
        assertEquals(listOf(20), r.volumes())
        r.env.resumeStreaming(); r.queue.drain(); r.step(300)
        assertEquals(listOf(20), r.volumes())
        assertTrue(r.queue.timers.isEmpty())
    }

    @Test fun freshManualPauseDuringLongConversationRevokesResumeAndRestoresOriginalVolume() {
        for (external in listOf(false, true)) {
            val r = Rig(external)
            r.conversation(true); r.step(300); r.now += 600_000
            r.env.manualPause(); r.queue.drain()
            val target = r.env.read().single().target
            assertNotNull("volume ownership survives manual transport", r.env.retained)
            r.conversation(true)
            r.conversation(false); r.step(300)
            assertEquals(listOf(20, 50), r.volumes())
            assertEquals(50, r.env.main.volume!!.current)
            assertEquals(target, r.env.main.target.copy(routeId = target.routeId))
            assertEquals(Playback.PAUSED, r.env.main.playback)
            assertEquals(0, r.plays())
            assertNull(r.env.retained)
            assertTrue(r.queue.timers.isEmpty())
        }
    }

    @Test fun pendingWriteDirectionChangeDoesNotExtendReceiptDeadline() {
        for (external in listOf(false, true)) {
            val r = Rig(external, pause = false)
            r.env.deferVolumeReceipt = true
            r.conversation(true); r.step(150)
            r.conversation(false); r.step(1_900)
            r.conversation(true); r.step(100)
            r.env.completeVolume(); r.queue.drain()
            r.conversation(false); r.step(300)
            assertEquals(listOf(35), r.volumes())
            assertTrue(r.queue.timers.isEmpty())
        }
    }

    @Test fun disconnectDuringUnconfirmedWriteCancelsEveryTimerAndCannotRestoreLateReceipt() {
        for (external in listOf(false, true)) {
            val r = Rig(external)
            r.env.deferVolumeReceipt = true
            r.conversation(true); r.step(150)
            r.env.present = false; r.env.signal(); r.queue.drain()
            assertTrue(r.queue.timers.isEmpty())
            r.env.completeVolume(); r.queue.drain(); r.step(600_000)
            r.conversation(false)
            assertEquals(listOf(35), r.volumes())
            assertEquals(0, r.plays())
            r.core.stop()
            assertTrue(r.queue.timers.isEmpty())
        }
    }

    @Test fun ownedLocalConversationRetainsPlaybackAcrossIdleDeadlineAndReleasesAfterRestore() {
        val r = Rig(external = false)
        r.conversation(true); r.step(300)
        assertEquals(r.env.main.target, r.env.retained)
        r.step(11 * 60_000L)
        assertEquals("a long owned conversation is still active", r.env.main.target, r.env.retained)
        r.conversation(false); r.step(300)
        assertEquals(listOf(20, 50), r.volumes())
        assertNull("completed restoration releases retention", r.env.retained)
    }

    @Test fun policyCancellationReleasesRetentionDespiteLingeringRendererPauseReceipt() {
        for (cancel in 0..2) {
            val r = Rig(external = false)
            r.conversation(true); r.step(300)
            assertNotNull(r.env.retained)
            assertNotNull(r.env.main.observedPauseEffect)
            when (cancel) {
                0 -> assertTrue(r.core.gesture(AirPodsMediaGesture.PAUSE))
                1 -> r.core.stop()
                2 -> r.core.configure(HostSettings(automationsEnabled = false))
            }
            if (cancel == 0) {
                assertEquals("transport cancels pause ownership, preserving duck", r.env.main.target, r.env.retained)
            } else assertNull("cancellation=$cancel", r.env.retained)
            assertNotNull("receipt persistence is not transport ownership", r.env.main.observedPauseEffect)
            r.conversation(false); r.step(300)
            assertEquals(if (cancel == 0) listOf(20, 50) else listOf(20), r.volumes())
            assertEquals(0, r.plays())
            assertNull(r.env.retained)
            assertTrue(r.queue.timers.isEmpty())
        }
    }

    @Test fun everyManualGestureDuringConfirmedDuckKeepsVolumeAndLatestTransportIntent() {
        for (external in listOf(false, true)) for (pause in listOf(false, true))
            for (action in AirPodsMediaGesture.values()) {
                val r = Rig(external, pause)
                r.conversation(true); r.step(300)
                val target = r.env.read().single().target
                assertTrue(r.core.gesture(action)); r.queue.drain()
                val manualState = r.env.main.playback
                assertEquals(listOf(target to action), r.env.gestures)
                repeat(3) {
                    r.now += 600_000
                    r.conversation(true)
                    assertEquals(listOf(20), r.volumes())
                    assertTrue("stable owned duck must not poll", r.queue.timers.isEmpty())
                }
                r.conversation(false); r.step(300)
                assertEquals("external=$external pause=$pause action=$action", listOf(20, 50), r.volumes())
                assertEquals(50, r.env.main.volume!!.current)
                assertEquals(manualState, r.env.main.playback)
                assertEquals(0, r.plays())
                assertEquals(if (pause) 1 else 0, r.env.sent.count { it.action == MediaAction.PAUSE })
                assertNull(r.env.retained)
                assertTrue(r.queue.timers.isEmpty())
            }
    }

    @Test fun manualTransportDuringAppliedUnconfirmedWriteWaitsForOriginalReceipt() {
        for (external in listOf(false, true)) {
            val r = Rig(external, pause = false)
            r.env.deferVolumeReceipt = true
            r.conversation(true); r.step(150)
            assertEquals(listOf(35), r.volumes())
            assertTrue(r.core.gesture(AirPodsMediaGesture.PAUSE)); r.queue.drain()
            r.conversation(false); r.step(100)
            assertEquals("transport must not turn a value into a receipt", listOf(35), r.volumes())
            r.env.deferVolumeReceipt = false
            r.env.completeVolume(); r.queue.drain(); r.step(300)
            assertEquals(listOf(35, 50), r.volumes())
            assertEquals(Playback.PAUSED, r.env.main.playback)
            assertEquals(0, r.plays())
            assertNull(r.env.retained)
            assertTrue(r.queue.timers.isEmpty())
        }
    }

    @Test fun manualTransportDoesNotExtendPendingVolumeReceiptDeadline() {
        for (external in listOf(false, true)) {
            val r = Rig(external, pause = false)
            r.env.deferVolumeReceipt = true
            r.conversation(true); r.step(150)
            assertTrue(r.core.gesture(AirPodsMediaGesture.PAUSE)); r.queue.drain()
            r.conversation(false); r.step(2_000)
            r.env.completeVolume(); r.queue.drain(); r.step(300)
            assertEquals(listOf(35), r.volumes())
            assertEquals(0, r.plays())
            assertNull(r.env.retained)
            assertTrue(r.queue.timers.isEmpty())
        }
    }

    @Test fun manualGestureCancelsAlreadyQueuedResumeWithoutCancellingVolumeRestore() {
        for (external in listOf(false, true)) {
            val r = Rig(external)
            r.conversation(true); r.step(300)
            r.core.conversation(false) // PLAY is queued, not dispatched.
            assertTrue(r.core.gesture(AirPodsMediaGesture.PAUSE))
            r.queue.drain(); r.step(300)
            assertEquals(listOf(20, 50), r.volumes())
            assertEquals(Playback.PAUSED, r.env.main.playback)
            assertEquals(0, r.plays())
            assertNull(r.env.retained)
            assertTrue(r.queue.timers.isEmpty())
        }
    }

    @Test fun manualOverrideFoundInDispatchRefreshCancelsThatExactQueuedResume() {
        for (external in listOf(false, true)) {
            val r = Rig(external)
            r.conversation(true); r.step(300)
            r.core.conversation(false)
            r.now++ // A fresh source timestamp, not an identical PAUSED redelivery.
            r.env.beforeRead = { r.env.manualPause() }
            r.queue.drain(); r.step(300)
            assertEquals(listOf(20, 50), r.volumes())
            assertEquals("cancelEffect during refresh must fence dispatch", 0, r.plays())
            assertEquals(Playback.PAUSED, r.env.main.playback)
            assertNull(r.env.retained)
            assertTrue(r.queue.timers.isEmpty())
        }
    }

    @Test fun explicitVolumeAfterManualTransportWinsEvenAtTheDuckLevel() {
        for (external in listOf(false, true)) for (level in listOf(20, 27)) {
            val r = Rig(external)
            r.conversation(true); r.step(300)
            assertTrue(r.core.gesture(AirPodsMediaGesture.PAUSE)); r.queue.drain()
            assertTrue(r.core.manualVolume(level)); r.queue.drain()
            r.conversation(false); r.step(300)
            assertEquals("no automatic restore after a volume intent", listOf(20), r.volumes())
            assertEquals(level, r.env.main.volume!!.current)
            assertEquals(0, r.plays())
            assertNull(r.env.retained)
            assertTrue(r.queue.timers.isEmpty())
        }
    }

    @Test fun transportCannotPreserveVolumeAcrossLostTargetRouteAccountOrAuthority() {
        for (external in listOf(false, true)) for (change in 0..6) {
            val r = Rig(external)
            r.conversation(true); r.step(300)
            assertTrue(r.core.gesture(AirPodsMediaGesture.PAUSE)); r.queue.drain()
            when (change) {
                0 -> r.env.present = false
                1 -> r.env.main = r.env.main.copy(target = r.env.main.target.copy(accountEpoch = "new-account"))
                2 -> if (external) r.env.facts = r.env.facts.copy(routeKey = "other-route")
                    else r.env.main = r.env.main.copy(target = r.env.main.target.copy(routeId = "other-route"))
                3 -> if (external) r.env.facts = r.env.facts.copy(callActive = true)
                    else r.env.main = r.env.main.copy(callActive = true)
                4 -> if (external) r.env.facts = r.env.facts.copy(permitted = false)
                    else r.env.main = r.env.main.copy(permitted = false)
                5 -> if (external) r.env.facts = r.env.facts.copy(foreignDuckActive = true)
                    else r.env.main = r.env.main.copy(foreignDuckActive = true)
                6 -> r.core.stop()
            }
            r.env.signal(); r.queue.drain()
            r.conversation(false); r.step(300)
            assertEquals("external=$external change=$change", listOf(20), r.volumes())
            assertEquals(0, r.plays())
            assertNull(r.env.retained)
            assertTrue(r.queue.timers.isEmpty())
        }
    }
}
