package dev.lelonio.square.ui.airpods

import android.app.Application
import android.content.Context
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.ui.AirPodsOnboardingAccess
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.airpods.host.*
import dev.lelonio.square.ui.onboarding.OnboardingScreen
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.pageFloor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** CORRECTIVE3 D-01. Prepared, NOT EXECUTED by the writer. No runtime owner/radio/auth.
 * Real SquareApp onboarding composite + real AirPodsHostPage/body; only state/action sink are fake.
 * Pointer clicks are intentional: semantics clicks alone can succeed behind an opaque sibling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w800dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirPodsOnboardingModalTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val actions = mutableListOf<AirPodsUiAction>()
    private var back: OnBackPressedDispatcherOwner? = null
    private var shown by mutableStateOf(true)
    private var playerBacks = 0
    private var logins = 0
    private var finishes = 0
    private var underlyingPicks = 0
    private var expectedPageFloor = Color.Unspecified
    private fun label(id: Int) = context.getString(id)

    private fun content(tutorial: Boolean = false, underlyingModal: Boolean = false,
        dark: Boolean = false, amoled: Boolean = false, opaqueProbe: Boolean = false) {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current
            val ground = rememberGlassBackdrop()
            val global = rememberFluidGlassModalHostState()
            val host = remember { AirPodsHost(context, null) }
            // Selected verified pair, but Product FALSE. No conformance/hardware claim.
            val state = uiFixture().copy(productGatePassed = false,
                candidates = listOf(PairCandidate("selected", "Fixture AirPods", "Selected fixture")),
                selectedCandidateId = "selected", availability = mapOf(
                    "setup.FORGET" to UiAvailability(true),
                    "gesture.LEFT" to UiAvailability(true)))
            val binding = AirPodsHostBinding(AirPodsUiBinding(state, { actions += it }, {}),
                host, false, null, {})
            DisposableEffect(host) { onDispose { host.close() } }
            SquareTheme(darkTheme = dark, amoled = amoled, systemBars = false) {
                val floor = pageFloor
                SideEffect { expectedPageFloor = floor }
                CompositionLocalProvider(LocalFluidMotionPolicy provides FluidMotionPolicy(0f),
                    LocalGlassBackdrop provides ground, LocalFluidCanvasBackdrop provides ground,
                    LocalFluidGlassModalHostState provides global) {
                    Box(Modifier.fillMaxSize().then(
                        if (opaqueProbe) Modifier.background(Color.Magenta) else Modifier)) {
                        Box(Modifier.fillMaxSize().glassBackdropSource(ground)) { Text("Player", Modifier.testTag("player")) }
                        BackHandler { playerBacks++ }
                        if (underlyingModal) FluidAlert(onDismissRequest = {}, title = "Underlying",
                            actions = listOf(FluidAlertAction("Underlying action", { underlyingPicks++ })))
                        FluidGlassModalHost(global, ground) // Same order as SquareApp.
                        if (shown) {
                            BackHandler(enabled = tutorial) { shown = false }
                            AirPodsOnboardingAccess(settings = { close ->
                                AirPodsHostPage(binding, PaddingValues(0.dp), ground, true, close)
                            }) { open ->
                                OnboardingScreen(
                                    if (tutorial) MainViewModel.UiState.Ready("Fixture", emptyList()) else MainViewModel.UiState.LoggedOut,
                                    MainViewModel.WebApiState(), { logins++ }, {}, {}, "", {},
                                    { finishes++; shown = false }, onOpenAirPods = open)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun touch(node: SemanticsNodeInteraction) { node.assertIsDisplayed().performTouchInput { click(center) }; compose.waitForIdle() }
    private fun open() = touch(compose.onNodeWithTag("onboarding-airpods-entry"))
    private fun pressBack() {
        compose.runOnIdle { requireNotNull(back).onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun forget() {
        val node = compose.onNodeWithText(label(R.string.airpods_feature_forget))
        node.performScrollTo(); touch(node)
        compose.onNodeWithText(label(R.string.airpods_ui_forget_confirm)).assertIsDisplayed()
    }
    private fun capture(name: String): java.io.File {
        val dir = java.io.File("build/airpods-full-integration/implementation/corrective-I3-ui/screenshots")
        dir.mkdirs()
        val file = java.io.File(dir, "$name.png")
        compose.onRoot().captureRoboImage(file.path)
        return file
    }

    private fun assertOpaquePageFloor(dark: Boolean, amoled: Boolean, captureName: String) {
        content(dark = dark, amoled = amoled, opaqueProbe = true)
        open()
        // A blank gutter inside the page must cover the hostile canvas behind it.
        // This checks rendered output, rather than the chosen modifier/parameter.
        // Read the same native Roborazzi rendering used by the interaction captures.
        // Compose's separate window forceRedraw path times out in this fixture.
        val image = requireNotNull(android.graphics.BitmapFactory.decodeFile(capture("page-floor-$captureName").path))
        val actual = Color(image.getPixel(8, image.height / 2))
        assertEquals("Page floor red", expectedPageFloor.red, actual.red, 0.02f)
        assertEquals("Page floor green", expectedPageFloor.green, actual.green, 0.02f)
        assertEquals("Page floor blue", expectedPageFloor.blue, actual.blue, 0.02f)
        assertEquals("Page floor alpha", 1f, actual.alpha, 0.01f)
        forget()
        capture(captureName)
        touch(compose.onNodeWithText(label(R.string.airpods_ui_cancel)))
        assertTrue(actions.isEmpty())
    }

    @Test fun lightPageFloorCoversUnderlyingCanvas() =
        assertOpaquePageFloor(dark = false, amoled = false, captureName = "forget-light-page-floor")

    @Test fun darkPageFloorCoversUnderlyingCanvas() =
        assertOpaquePageFloor(dark = true, amoled = false, captureName = "forget-dark-page-floor")

    @Test fun amoledPageFloorCoversUnderlyingCanvas() =
        assertOpaquePageFloor(dark = true, amoled = true, captureName = "forget-amoled-page-floor")

    @Test fun productFalseForgetCancelReceivesPointerAboveRealPageAndReopensCleanly() {
        content(); open(); forget(); capture("forget-above-real-page")
        touch(compose.onNodeWithText(label(R.string.airpods_ui_cancel)))
        compose.onNodeWithText(label(R.string.airpods_ui_forget_confirm)).assertDoesNotExist()
        assertTrue(actions.isEmpty())
        pressBack()
        compose.onNodeWithTag("onboarding-airpods-entry").assertIsDisplayed()
        open()
        compose.onNodeWithText(label(R.string.airpods_ui_forget_confirm)).assertDoesNotExist()
        assertEquals(0, logins); assertEquals(0, finishes); assertEquals(0, playerBacks)
    }

    @Test fun forgetBackDismissesBeforePageBackAndConfirmationDispatchesOnceByPointer() {
        content(); open(); forget(); pressBack()
        compose.onNodeWithText(label(R.string.airpods_ui_forget_confirm)).assertDoesNotExist()
        compose.onNodeWithTag("onboarding-airpods-entry").assertDoesNotExist()
        forget()
        // Title, page button and alert action share the label; only the action is clickable
        // in the final modal subtree. Last matches the host drawn after the page.
        touch(compose.onAllNodes(hasText(label(R.string.airpods_feature_forget)) and hasClickAction()).onLast())
        assertEquals(listOf(AirPodsUiAction.Setup(SetupAction.FORGET)), actions)
        pressBack()
        compose.onNodeWithTag("onboarding-airpods-entry").assertIsDisplayed()
    }

    @Test fun realGestureMenuPointerAndBackOrderRemainInsideOnboarding() {
        content(); open()
        val anchor = compose.onNodeWithTag("airpods-gesture-LEFT")
        anchor.performScrollTo(); touch(anchor); capture("gesture-menu-above-real-page")
        pressBack()
        compose.onNodeWithTag("onboarding-airpods-entry").assertDoesNotExist()
        touch(anchor)
        val action = GestureAction.DIGITAL_ASSISTANT
        touch(compose.onNodeWithText(label(gestureLabels.getValue(action))))
        assertEquals(listOf(AirPodsUiAction.AssignGesture(GestureSide.LEFT, action)), actions)
        pressBack()
        compose.onNodeWithTag("onboarding-airpods-entry").assertIsDisplayed()
    }

    @Test fun leavingWithOpenModalReleasesPortalsAndReturnsBackToPlayer() {
        content(tutorial = true); open(); forget()
        // External exit/unmount while presenting: no portal may survive re-entry.
        compose.runOnIdle { shown = false }; compose.waitForIdle()
        compose.onNodeWithText(label(R.string.airpods_ui_forget_confirm)).assertDoesNotExist()
        pressBack(); assertEquals(1, playerBacks)
        compose.runOnIdle { shown = true }; compose.waitForIdle(); open()
        compose.onNodeWithText(label(R.string.airpods_ui_forget_confirm)).assertDoesNotExist()
        pressBack(); pressBack()
        compose.onNodeWithTag("onboarding-airpods-entry").assertDoesNotExist()
        assertEquals(1, playerBacks)
        pressBack(); assertEquals(2, playerBacks)
    }

    @Test fun normalOnboardingStillAdvancesAndFinishesWithoutAuthentication() {
        content(); open(); pressBack()
        touch(compose.onNodeWithText(label(R.string.next_step)))
        compose.onNodeWithText(label(R.string.onboarding_login_title)).assertIsDisplayed()
        touch(compose.onNodeWithText(label(R.string.skip)))
        assertEquals(1, finishes); assertEquals(0, logins)
        pressBack(); assertEquals(1, playerBacks)
    }

    @Test fun underlyingGlobalModalCannotReceivePointersThroughOnboardingOrAirPods() {
        content(underlyingModal = true)
        // Target the global action's real coordinate despite its covered semantics.
        val point = compose.onNodeWithText("Underlying action").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { click(point) }; compose.waitForIdle()
        assertEquals(0, underlyingPicks)
        open()
        compose.onRoot().performTouchInput { click(point) }; compose.waitForIdle()
        assertEquals(0, underlyingPicks)
        forget(); touch(compose.onNodeWithText(label(R.string.airpods_ui_cancel)))
        assertEquals(0, underlyingPicks)
    }
}
