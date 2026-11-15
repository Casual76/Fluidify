package dev.lelonio.square.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production deadline with a virtual owner lifecycle. Android stopSelf/native JNI
 * are deliberately not invoked here; their production link is PlaybackService.onDestroy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackIdleShutdownTest {
    @Test fun existingMaintenanceTickEventuallyRequestsOneSavedTeardown() = runTest {
        val events = mutableListOf<String>()
        var stopped = false
        val idle = PlaybackIdleShutdown({ testScheduler.currentTime }, { PlaybackIdleFacts() }, {
            events.add("save")
            stopped = true
            events.add("stop")
            launch { delay(1); events.add("native-teardown") }
        })
        val maintenance = launch {
            while (!stopped) { delay(15_000); idle.sample() }
        }
        advanceTimeBy(315_002); runCurrent()
        assertEquals(listOf("save", "stop", "native-teardown"), events)
        idle.sample(); advanceTimeBy(600_000); runCurrent()
        assertEquals(3, events.size)
        maintenance.cancel()
    }

    @Test fun everyLiveOwnerPreventsShutdownAndStartsFreshFiveMinutesAfterRelease() = runTest {
        val owners = listOf(
            PlaybackIdleFacts(visible = true), PlaybackIdleFacts(controllerConnected = true),
            PlaybackIdleFacts(playing = true), PlaybackIdleFacts(playWhenReady = true),
            PlaybackIdleFacts(buffering = true, playWhenReady = true), PlaybackIdleFacts(remoteActive = true),
            PlaybackIdleFacts(downloading = true), PlaybackIdleFacts(nativeWork = true),
            PlaybackIdleFacts(watchWork = true), PlaybackIdleFacts(airPodsAutomation = true),
        )
        for (owner in owners) {
            var facts = PlaybackIdleFacts()
            var stops = 0
            val idle = PlaybackIdleShutdown({ testScheduler.currentTime }, { facts }, { stops++ })
            idle.sample(); advanceTimeBy(299_999)
            facts = owner; idle.sample()
            advanceTimeBy(11 * 60_000L); idle.sample()
            assertEquals("owner=$owner", 0, stops)
            facts = PlaybackIdleFacts(); idle.sample(); advanceTimeBy(299_999); idle.sample()
            assertEquals("release requires a new idle interval", 0, stops)
            advanceTimeBy(1); idle.sample()
            assertEquals("owner=$owner", 1, stops)
        }
    }

    @Test fun playOrBriefControllerBindingBetweenTicksInvalidatesOldDeadline() = runTest {
        var stops = 0
        val idle = PlaybackIdleShutdown({ testScheduler.currentTime }, { PlaybackIdleFacts() }, { stops++ })
        idle.sample(); advanceTimeBy(299_999)
        idle.activity() // production Player.Listener/onGetSession, even if paused again before tick
        advanceTimeBy(1); idle.sample()
        assertEquals(0, stops)
        advanceTimeBy(299_999); idle.sample(); assertEquals(0, stops)
        advanceTimeBy(1); idle.sample(); assertEquals(1, stops)
    }

    @Test fun pausedPreparationDoesNotKeepAnUnusedEngineAliveAfterItsNativeWorkEnds() = runTest {
        var facts = PlaybackIdleFacts(buffering = true, nativeWork = true)
        var stops = 0
        val idle = PlaybackIdleShutdown({ testScheduler.currentTime }, { facts }, { stops++ })
        idle.sample(); advanceTimeBy(600_000); idle.sample()
        assertEquals(0, stops)
        // prepare() can leave a paused queue BUFFERING without a pending load or PLAY.
        facts = facts.copy(nativeWork = false)
        idle.sample(); advanceTimeBy(299_999); idle.sample()
        assertEquals(0, stops)
        advanceTimeBy(1); idle.sample()
        assertEquals(1, stops)
    }

    @Test fun finalFreshReadRejectsShutdownIfPlayOrWatchWorkArrivesAtDeadline() = runTest {
        for (lateOwner in listOf(PlaybackIdleFacts(playWhenReady = true), PlaybackIdleFacts(watchWork = true))) {
            var stops = 0
            var reads = 0
            var finalRead = false
            val idle = PlaybackIdleShutdown({ testScheduler.currentTime }, {
                reads++
                if (finalRead && reads % 2 == 1) lateOwner else PlaybackIdleFacts()
            }, { stops++ })
            idle.sample() // read 1; starts the deadline
            advanceTimeBy(300_000); finalRead = true
            idle.sample() // read 2 idle, read 3 observes the new owner
            assertEquals(0, stops)
            finalRead = false; idle.sample()
            advanceTimeBy(299_999); idle.sample(); assertEquals(0, stops)
            advanceTimeBy(1); idle.sample(); assertEquals(1, stops)
        }
    }

    @Test fun newClientAfterStopRequestCanOwnServiceAndLaterBecomeIdleAgain() = runTest {
        var facts = PlaybackIdleFacts()
        var stops = 0
        val idle = PlaybackIdleShutdown({ testScheduler.currentTime }, { facts }, { stops++ })
        idle.sample(); advanceTimeBy(300_000); idle.sample()
        assertEquals(1, stops)
        // A new bind/start can keep Android's service instance alive while stopSelf is pending.
        facts = PlaybackIdleFacts(controllerConnected = true); idle.activity(); idle.sample()
        advanceTimeBy(600_000); idle.sample(); assertEquals(1, stops)
        facts = PlaybackIdleFacts(); idle.sample(); advanceTimeBy(300_000); idle.sample()
        assertEquals(2, stops)
    }
}
