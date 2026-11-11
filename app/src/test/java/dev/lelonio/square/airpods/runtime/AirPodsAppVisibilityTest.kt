package dev.lelonio.square.airpods.runtime

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsAppVisibilityTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test fun backgroundProcessHasNoResumedActivity() {
        val visibility = AirPodsAppVisibility()
        visibility.attach(application)
        assertFalse(visibility.active.value)
        val activity = Robolectric.buildActivity(Activity::class.java).create().start()
        assertFalse(visibility.active.value)
        activity.resume()
        assertTrue(visibility.active.value)
        activity.pause().stop().destroy()
        assertFalse(visibility.active.value)
        visibility.detach()
    }

    @Test fun pausingOneWindowDoesNotHideAnotherResumedWindow() {
        val visibility = AirPodsAppVisibility()
        visibility.attach(application)
        val first = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val second = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        first.pause().stop().destroy()
        assertTrue(visibility.active.value)
        second.pause().stop().destroy()
        assertFalse(visibility.active.value)
        visibility.detach()
    }

    @Test fun duplicateResumeCannotKeepAppActiveAfterPause() {
        val visibility = AirPodsAppVisibility()
        visibility.attach(application)
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        visibility.onActivityResumed(activity.get())
        activity.pause()
        assertFalse(visibility.active.value)
        activity.stop().destroy()
        visibility.detach()
    }

    @Test fun detachReleasesActivityReferencesAndAllowsReattachment() {
        val visibility = AirPodsAppVisibility()
        visibility.attach(application)
        visibility.attach(application)
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        assertTrue(visibility.active.value)
        visibility.detach()
        assertFalse(visibility.active.value)
        activity.pause().stop().destroy()
        visibility.attach(application)
        assertFalse(visibility.active.value)
        visibility.detach()
    }
}
