package dev.lelonio.square.ui.player

import android.app.Application
import android.content.Context
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lelonio.square.data.PreferencesStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class I13PlayerScreenOnTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun preferenceStartsOffAndSurvivesAStoreRecreation() {
        val disk = context.getSharedPreferences("square_preferences", Context.MODE_PRIVATE)
        disk.edit().remove("keep_player_screen_on").commit()
        try {
            val first = PreferencesStore(context)
            assertFalse(first.keepPlayerScreenOn.value)
            first.setKeepPlayerScreenOn(true)
            assertTrue(first.keepPlayerScreenOn.value)
            assertTrue(PreferencesStore(context).keepPlayerScreenOn.value)
            first.setKeepPlayerScreenOn(false)
            assertFalse(PreferencesStore(context).keepPlayerScreenOn.value)
        } finally {
            disk.edit().remove("keep_player_screen_on").commit()
        }
    }

    @Test fun everyMissingGateReleasesOnlyItsOwnRequest() {
        val owned = View(context)
        val other = View(context).apply { keepScreenOn = true }
        val request = I13ScreenOnRequest(owned)
        for (enabled in listOf(false, true)) for (full in listOf(false, true)) {
            for (playing in listOf(false, true)) for (resumed in listOf(false, true)) {
                request.update(true, true, true, true)
                assertTrue(owned.keepScreenOn)
                request.update(enabled, full, playing, resumed)
                assertEquals(enabled && full && playing && resumed, owned.keepScreenOn)
                assertTrue(other.keepScreenOn)
            }
        }
        request.release()
        assertFalse(owned.keepScreenOn)
        assertTrue(other.keepScreenOn)
    }

    @Test fun composedPlayerReleasesOnPauseBackgroundCloseOptOutAndDisposal() {
        val enabled = mutableStateOf(false)
        val full = mutableStateOf(true)
        val playing = mutableStateOf(true)
        val present = mutableStateOf(true)
        lateinit var owner: Owner
        lateinit var root: View
        compose.runOnIdle {
            owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        }
        compose.setContent {
            root = LocalView.current.rootView
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (present.value) I13PlayerScreenOn(enabled.value, playing.value) { full.value }
            }
        }
        lateinit var owned: View
        compose.runOnIdle {
            owned = root.findViewWithTag("wf_i13_screen_on")
            assertFalse(owned.keepScreenOn)
            enabled.value = true
        }
        compose.runOnIdle { assertTrue(owned.keepScreenOn); playing.value = false }
        compose.runOnIdle { assertFalse(owned.keepScreenOn); playing.value = true }
        compose.runOnIdle { assertTrue(owned.keepScreenOn); full.value = false }
        compose.runOnIdle { assertFalse(owned.keepScreenOn); full.value = true }
        compose.runOnIdle {
            assertTrue(owned.keepScreenOn)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertFalse(owned.keepScreenOn)
            owner.registry.currentState = Lifecycle.State.CREATED
            assertFalse(owned.keepScreenOn)
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertTrue(owned.keepScreenOn)
            enabled.value = false
        }
        compose.runOnIdle { assertFalse(owned.keepScreenOn); enabled.value = true }
        compose.runOnIdle { assertTrue(owned.keepScreenOn); present.value = false }
        compose.runOnIdle { assertFalse(owned.keepScreenOn) }
    }
}
