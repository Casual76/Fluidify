package dev.lelonio.square.airpods.runtime

import android.app.Activity
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Prepared only. Exercises synchronous lifecycle loss without any StateFlow collector. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class I5ForegroundFenceTest {
    @Test fun hideShowInvalidatesEvenWithoutCollectorOrBooleanChangeAtReadTime() {
        val app = RuntimeEnvironment.getApplication()
        val source = AirPodsAppVisibility(); source.attach(app)
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val state = source.active as AirPodsForegroundState
        val captured = state.captureForeground()!!
        activity.pause(); activity.resume()
        assertTrue(state.value); assertFalse(state.isCurrentForeground(captured))
        assertTrue(state.captureForeground()!! > captured)
        activity.pause().stop().destroy(); source.detach()
    }
    @Test fun secondResumedWindowAndDuplicateCallbacksDoNotInventForegroundLoss() {
        val source = AirPodsAppVisibility(); source.attach(RuntimeEnvironment.getApplication())
        val first = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val second = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val state = source.active as AirPodsForegroundState
        val captured = state.captureForeground()!!
        source.onActivityResumed(first.get()); first.pause().stop().destroy()
        assertTrue(state.isCurrentForeground(captured))
        second.pause(); val afterLoss = state.captureForeground()
        assertNull(afterLoss); source.onActivityPaused(second.get()); second.resume()
        assertEquals(captured + 1, state.captureForeground()!!)
        second.pause().stop().destroy(); source.detach()
    }
    @Test fun detachReattachDoesNotRecycleOldForegroundToken() {
        val app = RuntimeEnvironment.getApplication()
        val source = AirPodsAppVisibility(); source.attach(app)
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val state = source.active as AirPodsForegroundState
        val captured = state.captureForeground()!!
        source.detach(); assertNull(state.captureForeground())
        source.attach(app); source.onActivityResumed(activity.get())
        assertFalse(state.isCurrentForeground(captured))
        activity.pause().stop().destroy(); source.detach()
    }
    @Test fun harnessSourceSetterRejectsOldTokenAcrossStopStartWithoutCollect() {
        val source = AirPodsForegroundState()
        assertNull(source.captureForeground())
        source.value = true; val captured = source.captureForeground()!!
        source.value = false; source.value = false; source.value = true
        assertTrue(source.value); assertFalse(source.isCurrentForeground(captured))
        assertEquals(captured + 1, source.captureForeground())
    }
}
