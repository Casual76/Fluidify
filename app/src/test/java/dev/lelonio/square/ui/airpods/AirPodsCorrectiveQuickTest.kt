package dev.lelonio.square.ui.airpods

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PREPARED NOT EXECUTED. Real Engine portal, synthetic backdrop/body; no Samsung/full-app contrast proof. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[36], application=Application::class, qualifiers="w720dp-h640dp-mdpi")
class AirPodsCorrectiveQuickTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
    private fun mount(font: Float, width: Int=360, height: Int=640, content: @Composable () -> Unit) {
        compose.setContent {
            val ground=rememberGlassBackdrop()
            val host=rememberFluidGlassModalHostState()
            val density=LocalDensity.current
            SquareTheme(darkTheme=true,amoled=false,systemBars=false) {
                CompositionLocalProvider(LocalDensity provides Density(density.density,font),
                    LocalGlassBackdrop provides ground,LocalFluidGlassModalHostState provides host,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.requiredSize(width.dp,height.dp)) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).glassBackdropSource(ground))
                        content()
                        FluidGlassModalHost(host,backdrop=ground)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun compact(font: Float) {
        var opens=0; val actions=mutableListOf<AirPodsUiAction>()
        mount(font) { AirPodsCapsule(AirPodsUiBinding(uiFixture(),actions::add,{opens++}),Modifier.width(96.dp)) }
        val texts=compose.onAllNodesWithTag("airpods-capsule-battery-text",useUnmergedTree=true).fetchSemanticsNodes()
        if(font == 1f) assertEquals("Default font must retain readable charge",1,texts.size)
        if(font == 2f) assertTrue("Large charge must be omitted as a whole",texts.isEmpty())
        if (texts.isNotEmpty()) {
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("airpods-capsule-battery-text",useUnmergedTree=true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("L 80%",layouts.single().layoutInput.text.text)
            assertFalse("Battery layout size=${layouts.single().size}, constraints=${layouts.single().layoutInput.constraints}, paragraph=${layouts.single().multiParagraph.width}x${layouts.single().multiParagraph.height}", layouts.single().hasVisualOverflow)
        }
        for(tag in listOf("airpods-capsule-body","airpods-capsule-quick")) {
            val bounds=compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width>=48f); assertTrue(bounds.height>=48f)
        }
        compose.onNodeWithTag("airpods-capsule-body",useUnmergedTree=true)
            .assertContentDescriptionContains("80%",substring=true).performClick()
        assertEquals(1,opens)
        compose.onNodeWithTag("airpods-capsule-quick",useUnmergedTree=true).performClick()
        assertEquals(1,actions.size)
    }
    @Test fun minimumCapsuleFont1() = compact(1f)
    @Test fun minimumCapsuleFont15() = compact(1.5f)
    @Test fun minimumCapsuleFont2() = compact(2f)

    @Test fun primaryModesRemainVisibleBeforeAutomationPreferencesAtNormalFont() {
        val actions = mutableListOf<AirPodsUiAction>()
        mount(1f) { AirPodsQuickOverlay(uiFixture(), true, {}, actions::add, {}) }
        val footer = compose.onNodeWithTag("airpods-all-settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        for (mode in listOf(1, 2, 3, 4)) {
            val button = compose.onNodeWithTag("airpods-quick-MODE-$mode").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Noise mode $mode must be above the fixed footer", button.bottom <= footer.top)
        }
        assertTrue(actions.isEmpty())
    }

    private fun quick(saved: Boolean, blocked: Boolean, landscape: Boolean=false) {
        val base=uiFixture()
        val toggles=listOf(LocalToggle.AUTOMATIONS,LocalToggle.BACKGROUND,LocalToggle.OTHER_APPS)
        val reason=context.getString(R.string.airpods_reason_nls)
        val state=base.copy(pair=base.pair.copy(host=base.pair.host.copy(automationsEnabled=saved,
            fluidifyBackgroundEnabled=saved,otherAppsEnabled=saved)),
            availability=toggles.associate { "status.$it" to UiAvailability(!blocked,if(blocked) reason else null) })
        val events=mutableListOf<String>()
        mount(2f,if(landscape) 720 else 360,if(landscape) 360 else 640) {
            AirPodsQuickOverlay(state,true,{events+="dismiss"},{events+="unexpected action"},{events+="settings"})
        }
        for(toggle in toggles) {
            val expected=context.getString(if(!saved) R.string.airpods_ui_saved_off else if(blocked)
                R.string.airpods_ui_saved_blocked else R.string.airpods_ui_saved_ready,reason)
            compose.onNodeWithTag("airpods-host-summary-$toggle").performScrollTo().assertIsDisplayed()
                .assertTextContains(expected,substring=true)
        }
        assertTrue(events.isEmpty())
        compose.onNodeWithTag("airpods-all-settings").assertIsDisplayed().performClick()
        assertEquals(listOf("dismiss","settings"),events)
    }
    @Test fun quickSavedOffIsObservationalAtFont2() = quick(false,false)
    @Test fun quickSavedOnShowsPrerequisitesWithoutClaimingEffects() = quick(true,false)
    @Test fun quickBlockedShowsReasonAtFont2() = quick(true,true)
    @Test fun quickLandscapeFont2KeepsFooterAndSummaryReachable() = quick(true,true,true)
}
