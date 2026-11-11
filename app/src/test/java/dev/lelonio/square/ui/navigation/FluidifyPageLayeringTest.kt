package dev.lelonio.square.ui.navigation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuButton
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuHost
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuState
import dev.antigravity.fluidengine.ui.fluidphysics.LocalFluidMorphMenuState
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidMorphMenuState
import dev.antigravity.fluidengine.ui.navigation.FluidNavHost
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.ui.page.*
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.pageFloor
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PREPARED ONLY. Uses the actual Engine host/destination/gesture and the production app helpers.
 * The legacy fixtures use Engine's transparent pageColor with NO app surface. The lower marker
 * must be visible INSIDE the moving upper page there, while fixed fixtures must show the ambient.
 * Native Roborazzi pixels are required; no captureToImage, white-image acceptance, Engine-internal
 * setters, copied motion formula or production bypass. This does not validate a hardware GPU.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w800dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FluidifyPageLayeringTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController
    private lateinit var pages: FluidPageHostState
    private lateinit var nestedPages: FluidPageHostState
    private lateinit var ambient: FluidifyPageAmbient
    private lateinit var menuState: FluidMorphMenuState
    private var dispatcher: OnBackPressedDispatcherOwner? = null
    private var nestedOpen by mutableStateOf(false)
    private var sourceVisible by mutableStateOf(true)
    private var liveColor by mutableStateOf(Wash)
    private var outerVisible by mutableStateOf(true)
    private var sourceMounts = 0
    private var sourceDisposals = 0
    private var sourceDraws = 0
    private var upperMounts = 0
    private var upperDisposals = 0
    private var parentBacks = 0
    private var upperPicks = 0
    private var lowerPicks = 0
    private var menuPicks = 0
    private var expectedFloor = Color.Unspecified
    private val scopes = mutableMapOf<String, FluidPageScope>()
    private val lifecycles = mutableMapOf<String, Lifecycle>()

    private fun content(
        fixed: Boolean,
        motion: FluidPageTransition,
        nested: Boolean = false,
        dark: Boolean = false,
        amoled: Boolean = false,
        font: Float = 1f,
        source: Boolean = true,
    ) {
        sourceVisible = source
        compose.setContent {
            SquareTheme(darkTheme = dark, amoled = amoled, systemBars = false) {
                val density = LocalDensity.current
                val ground = rememberGlassBackdrop()
                val glass = rememberGlassBackdrop()
                val menus = rememberFluidMorphMenuState()
                menuState = menus
                val modals = rememberFluidGlassModalHostState()
                ambient = rememberFluidifyPageAmbient()
                pages = rememberFluidPageHostState()
                nestedPages = rememberFluidPageHostState()
                nav = rememberNavController()
                dispatcher = LocalOnBackPressedDispatcherOwner.current
                expectedFloor = pageFloor
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, font),
                    LocalFluidMotionPolicy provides FluidMotionPolicy(1f),
                    LocalFluidifyPageAmbient provides ambient,
                    LocalGlassBackdrop provides glass,
                    LocalFluidCanvasBackdrop provides ground,
                    LocalFluidMorphMenuState provides menus,
                    LocalFluidGlassModalHostState provides modals,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        BackHandler { parentBacks++ }
                        Box(Modifier.fillMaxSize().glassBackdropSource(glass)) {
                            if (sourceVisible) {
                                FluidifyAmbientSource(ambient,
                                    Modifier.fillMaxSize().glassBackdropSource(ground)) {
                                    DisposableEffect(Unit) {
                                        sourceMounts++
                                        onDispose { sourceDisposals++ }
                                    }
                                    Pattern()
                                }
                            }
                            if (outerVisible) {
                                FluidNavHost(nav, "under", state = pages,
                                    pageColor = if (fixed) pageFloor else Color.Transparent) {
                                    if (fixed) {
                                        fluidifyComposable("under") { Lower(this) }
                                        fluidifyComposable("upper", transition = motion) {
                                            Upper(this, fixed = true, nested = nested, ground = ground)
                                        }
                                    } else {
                                        composable("under") { Lower(this) }
                                        composable("upper", transition = motion) {
                                            Upper(this, fixed = false, nested = nested, ground = ground)
                                        }
                                    }
                                }
                            }
                        }
                        // Exactly the production ordering: portals AFTER the page recording.
                        FluidGlassModalHost(modals, glass)
                        FluidMorphMenuHost(menus, glass)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Composable
    private fun Pattern() {
        Canvas(Modifier.fillMaxSize()) {
            sourceDraws++
            drawRect(Navy)
            drawRect(liveColor, topLeft = Offset(0f, size.height * 0.3f),
                size = Size(size.width, size.height * 0.5f))
            drawRect(Gold, topLeft = Offset(size.width * 0.5f, 0f),
                size = Size(size.width * 0.25f, size.height))
        }
    }

    @Composable
    private fun Lower(scope: FluidPageScope) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        SideEffect { scopes["under"] = scope; lifecycles["under"] = lifecycle }
        Box(Modifier.fillMaxSize().background(Marker).testTag("lower-body")) {
            Text("Lower target", Modifier.offset(200.dp, 80.dp).size(150.dp, 60.dp)
                .clickable { lowerPicks++ }.testTag("lower-target"))
        }
    }

    @Composable
    private fun Upper(scope: FluidPageScope, fixed: Boolean, nested: Boolean, ground: GlassBackdropState) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        SideEffect { scopes["upper"] = scope; lifecycles["upper"] = lifecycle }
        if (!nested) UpperBody(ground)
        else {
            val stack = if (nestedOpen) listOf("settings", "detail") else listOf("settings")
            val body: @Composable FluidPageScope.(String) -> Unit = { key ->
                if (key == "settings") {
                    Box(Modifier.fillMaxSize().background(Marker)) {
                        Text("Open nested", Modifier.padding(100.dp).clickable { nestedOpen = true })
                    }
                } else UpperBody(ground)
            }
            // This is the exact helper called by SettingsScreen, inside a real FluidNavHost page.
            if (fixed) FluidifyPageHost(stack, { nestedOpen = false },
                state = nestedPages, content = body)
            else FluidPageHost(stack, { nestedOpen = false },
                state = nestedPages, pageColor = Color.Transparent, content = body)
        }
    }

    @Composable
    private fun UpperBody(ground: GlassBackdropState) {
        var edits by rememberSaveable { mutableStateOf(0) }
        DisposableEffect(Unit) {
            upperMounts++
            onDispose { upperDisposals++ }
        }
        BoxWithConstraints(Modifier.fillMaxSize().testTag("upper-body")) {
            // Blank, deliberately INSIDE the page. Its transformed bounds, not an Engine motion
            // equation, locate the pixel. Well away from the rounded edge, chrome and menu.
            Box(Modifier.offset(64.dp, maxHeight * 0.65f).size(24.dp).testTag("probe"))
            Box(Modifier.offset(maxWidth * 0.65f, maxHeight * 0.65f).size(24.dp).testTag("gold-probe"))
            Text("Upper target $edits", Modifier.offset(200.dp, 80.dp).size(150.dp, 60.dp)
                .clickable { upperPicks++; edits++ }.testTag("upper-target"))
            FluidMorphMenuButton(
                actions = { listOf(FluidContextAction("Pick fixture action") { menuPicks++ }) },
                onClick = {}, text = "Fixture menu", opensOnTap = true, backdrop = ground,
                modifier = Modifier.offset(180.dp, 200.dp).testTag("fixture-menu"),
            )
        }
    }

    /** Bounded public-clock stepping: failure to reach the requested frame is a test failure. */
    private fun frameIn(state: FluidPageHostState, min: Float, max: Float) {
        repeat(90) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val reached = compose.runOnIdle { state.isMoving && state.progress in min..max }
            if (reached) return
        }
        fail("Engine never reached a moving frame in $min..$max; progress=${state.progress}")
    }

    private fun settle(state: FluidPageHostState) {
        // A NavController update first reaches the host through composition/SideEffect. Do not
        // mistake the previous route's idle state for completion of the new transition.
        repeat(3) { flushFrame() }
        repeat(180) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (compose.runOnIdle { !state.isMoving }) {
                // Completion changes lifecycle, semantics and pointer gates in the next composition.
                repeat(3) { flushFrame() }
                if (compose.runOnIdle { !state.isMoving }) return
            }
        }
        fail("Engine did not settle")
    }

    private fun openUpper() { compose.runOnIdle { nav.navigate("upper") } }

    private fun flushFrame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun capture(name: String): Bitmap {
        val file = File("build/fluidify-visual-layering/implementation/screenshots/$name.png")
        file.parentFile!!.mkdirs()
        compose.onRoot().captureRoboImage(file.path)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        assertTrue("Native capture must have real dimensions", bitmap.width > 100 && bitmap.height > 100)
        return bitmap
    }

    private fun pixel(name: String, tag: String = "probe"): Color {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val probe = node.boundsInRoot
        // Reject a clipped-away/outside-card probe, including early Slide frames.
        assertTrue("Probe must be fully visible inside the host", probe.width >= 20f && probe.height >= 20f)
        assertTrue(root.contains(probe.center))
        val bitmap = capture(name)
        try {
            val x = ((probe.center.x - root.left) * bitmap.width / root.width).toInt()
            val y = ((probe.center.y - root.top) * bitmap.height / root.height).toInt()
            return Color(bitmap.getPixel(x, y))
        } finally { bitmap.recycle() }
    }

    private fun assertColor(expected: Color, actual: Color) {
        if (expected == Marker) {
            // The real elevated card casts a black shadow over the lower page. Its diagnostic
            // magenta hue must still be present; exact unshadowed RGB is not expected here.
            assertTrue("Exposed lower marker: $actual", actual.red > 0.60f && actual.blue > 0.60f)
            assertTrue("Marker cannot be the green/blue ambient or a blank capture: $actual", actual.green < 0.05f)
            assertEquals("Marker hue survives the neutral shadow", actual.red, actual.blue, 0.025f)
            assertEquals("alpha", 1f, actual.alpha, 0.01f)
            return
        }
        assertEquals("red: expected $expected, actual $actual", expected.red, actual.red, 0.025f)
        assertEquals("green", expected.green, actual.green, 0.025f)
        assertEquals("blue", expected.blue, actual.blue, 0.025f)
        assertEquals("alpha", 1f, actual.alpha, 0.01f)
    }

    private fun motionCase(fixed: Boolean, motion: FluidPageTransition, name: String) {
        content(fixed, motion)
        openUpper()
        frameIn(pages, 0.32f, 0.55f)
        assertColor(if (fixed) Wash else Marker, pixel("$name-push-first"))
        frameIn(pages, 0.68f, 0.9f)
        assertColor(if (fixed) Wash else Marker, pixel("$name-push-later"))
        settle(pages)
        assertColor(Wash, pixel("$name-rest"))
        assertColor(Gold, pixel("$name-rest-pattern", "gold-probe"))
        if (fixed) {
            assertFalse(scopes.getValue("under").isFront)
            assertTrue(scopes.getValue("under").isCovered)
            assertEquals(Lifecycle.State.CREATED, lifecycles.getValue("under").currentState)
            assertEquals(Lifecycle.State.RESUMED, lifecycles.getValue("upper").currentState)
            assertTrue(scopes.getValue("upper").isFront)
        }
        compose.runOnIdle { nav.popBackStack() }
        frameIn(pages, 0.18f, 0.45f)
        assertColor(if (fixed) Wash else Marker, pixel("$name-pop"))
        settle(pages)
        assertEquals("under", nav.currentBackStackEntry?.destination?.route)
    }

    @Test fun legacyTransparentSlideExposesLowerMarkerInsideUpperPage() =
        motionCase(false, FluidPageTransition.Slide, "legacy-slide")

    @Test fun opaqueSlideReplaysAmbientInsteadOfLowerMarker() =
        motionCase(true, FluidPageTransition.Slide, "fixed-slide")

    @Test fun legacyTransparentExpandExposesLowerMarkerInsideUpperPage() =
        motionCase(false, FluidPageTransition.Expand, "legacy-expand")

    @Test fun opaqueExpandReplaysAmbientInsteadOfLowerMarker() =
        motionCase(true, FluidPageTransition.Expand, "fixed-expand")

    private fun backCancelCase(fixed: Boolean, motion: FluidPageTransition, name: String) {
        content(fixed, motion)
        openUpper(); settle(pages)
        compose.onNodeWithTag("upper-target").performTouchInput { click(center) }
        flushFrame()
        flushFrame()
        assertEquals("Real pointer click must reach the settled upper page", 1, upperPicks)
        compose.onNodeWithText("Upper target 1").assertExists()
        // Start at the Engine's default edge, with no test-only access to its drag state.
        compose.onRoot().performTouchInput {
            down(Offset(2f, height * 0.65f))
            moveTo(Offset(width * 0.16f, height * 0.65f), delayMillis = 200)
        }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(pages.isInteractive)
            assertTrue(pages.progress in 0.08f..0.33f)
        }
        assertColor(if (fixed) Wash else Marker, pixel("$name-held"))
        // Stationary hold removes fling; less than the commit distance must cancel.
        compose.onRoot().performTouchInput {
            moveTo(Offset(width * 0.16f, height * 0.65f), delayMillis = 240)
            up()
        }
        frameIn(pages, 0.025f, 0.3f)
        assertColor(if (fixed) Wash else Marker, pixel("$name-return"))
        settle(pages)
        assertEquals("upper", nav.currentBackStackEntry?.destination?.route)
        assertColor(Wash, pixel("$name-restored"))
        compose.onNodeWithText("Upper target 1").assertExists() // saved page state survived cancellation
        assertEquals(1, upperPicks); assertEquals(0, lowerPicks); assertEquals(0, parentBacks)
    }

    @Test fun legacySlideBackCancelExposesMarker() = backCancelCase(false, FluidPageTransition.Slide, "legacy-slide-cancel")
    @Test fun fixedSlideBackCancelKeepsAmbient() = backCancelCase(true, FluidPageTransition.Slide, "fixed-slide-cancel")
    @Test fun legacyExpandBackCancelExposesMarker() = backCancelCase(false, FluidPageTransition.Expand, "legacy-expand-cancel")
    @Test fun fixedExpandBackCancelKeepsAmbient() = backCancelCase(true, FluidPageTransition.Expand, "fixed-expand-cancel")

    private fun nestedCase(fixed: Boolean, name: String) {
        content(fixed, FluidPageTransition.Expand, nested = true)
        openUpper(); settle(pages)
        compose.onNodeWithText("Open nested").performTouchInput { click(center) }
        frameIn(nestedPages, 0.32f, 0.55f)
        assertColor(if (fixed) Wash else Marker, pixel("$name-push"))
        settle(nestedPages)
        assertColor(Wash, pixel("$name-rest"))
        compose.onNodeWithTag("fixture-menu").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        compose.onNodeWithText("Pick fixture action").assertIsDisplayed()
        assertColor(Wash, pixel("$name-menu"))
        pressBack()
        assertFalse("System back must dismiss the menu before navigating pages: nested=$nestedOpen, " +
            "route=${nav.currentBackStackEntry?.destination?.route}, parent=$parentBacks, " +
            "owner=${dispatcher?.lifecycle?.currentState}, callbacks=${dispatcher?.onBackPressedDispatcher?.hasEnabledCallbacks()}",
            menuState.isOpen)
        // The frozen clock above samples PAGE transitions. The menu also waits for its
        // draw-driven liquid tail; let Compose drive its complete idle cycle as in the
        // Engine menu tests before asserting that the closing surface has been removed.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Pick fixture action").assertDoesNotExist()
        assertTrue(nestedOpen)
        assertEquals("upper", nav.currentBackStackEntry?.destination?.route)
        compose.onNodeWithTag("fixture-menu").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        compose.onNodeWithText("Pick fixture action").performTouchInput { click(center) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        assertEquals(1, menuPicks)
        assertFalse("Picking the action dismisses the menu", menuState.isOpen)
        assertFalse("The menu has finished closing before the next Back", menuState.isOnScreen)
        pressBack(); settle(nestedPages)
        assertFalse(nestedOpen)
        assertEquals("upper", nav.currentBackStackEntry?.destination?.route)
        pressBack(); settle(pages)
        assertEquals("under", nav.currentBackStackEntry?.destination?.route)
        assertEquals(0, parentBacks)
        compose.runOnIdle { outerVisible = false }
        flushFrame(); pressBack()
        assertEquals(1, parentBacks)
    }

    @Test fun legacyNestedSettingsHasDiscriminatingMarkerAndKeepsMenuBackOrder() = nestedCase(false, "legacy-nested")
    @Test fun fixedNestedSettingsCoversMarkerAndKeepsMenuBackOrder() = nestedCase(true, "fixed-nested")

    private fun pressBack() {
        compose.runOnIdle { requireNotNull(dispatcher).onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
    }

    @Test fun sameAmbientUpdatesDuringMotionAndDisposalFallsBackWithoutRemountingConsumers() {
        content(true, FluidPageTransition.Slide)
        openUpper(); frameIn(pages, 0.32f, 0.55f)
        assertColor(Wash, pixel("live-before"))
        val first: GraphicsLayer = compose.runOnIdle { requireNotNull(ambient.sample) }
        compose.runOnIdle { liveColor = ChangedWash }
        flushFrame()
        assertColor(ChangedWash, pixel("live-updated"))
        assertSame(first, ambient.sample)
        assertEquals(1, sourceMounts); assertEquals(0, sourceDisposals)
        compose.runOnIdle { sourceVisible = false }
        flushFrame()
        assertNull(ambient.sample)
        assertColor(expectedFloor, pixel("source-disposed-floor"))
        assertEquals(1, sourceDisposals)
        compose.runOnIdle { sourceVisible = true }
        flushFrame()
        // Readiness is published by the source's first actual draw. Until the next composition
        // observes that publication the opaque floor is the intentional safe first-frame result.
        assertColor(expectedFloor, pixel("source-remount-first-draw-floor"))
        flushFrame()
        assertColor(ChangedWash, pixel("source-remounted"))
        assertNotSame(first, ambient.sample)
        assertEquals(2, sourceMounts)
        assertEquals(1, upperMounts); assertEquals(0, upperDisposals)
        settle(pages)
        val draws = sourceDraws
        repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertEquals("No idle invalidation loop after readiness publication", draws, sourceDraws)
    }

    private fun fallbackCase(dark: Boolean, amoled: Boolean, font: Float, name: String) {
        content(true, FluidPageTransition.Expand, dark = dark, amoled = amoled, font = font, source = false)
        openUpper(); frameIn(pages, 0.32f, 0.55f)
        assertColor(expectedFloor, pixel("$name-moving"))
        settle(pages)
        assertColor(expectedFloor, pixel("$name-rest"))
        assertNull(ambient.sample)
        assertEquals(0, sourceMounts)
        compose.onNodeWithTag("upper-target").performTouchInput { click(center) }
        assertEquals(1, upperPicks); assertEquals(0, lowerPicks)
    }

    @Test fun missingAmbientLightUsesOpaquePageFloor() = fallbackCase(false, false, 1f, "fallback-light")
    @Test fun missingAmbientDarkLargeFontUsesOpaquePageFloor() = fallbackCase(true, false, 1.5f, "fallback-dark")
    @Test fun missingAmbientAmoledUsesOpaquePageFloor() = fallbackCase(true, true, 1f, "fallback-amoled")

    @Test fun smallerPageCropsAmbientWithoutStretchingAndDoesNotPaintOutsideItsRectangle() {
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                val sample = rememberFluidifyPageAmbient()
                CompositionLocalProvider(LocalFluidifyPageAmbient provides sample) {
                    Box(Modifier.fillMaxSize()) {
                        FluidifyAmbientSource(sample, Modifier.fillMaxSize()) { Pattern() }
                        Box(Modifier.fillMaxSize().background(Marker))
                        FluidifyPageSurface(Modifier.size(200.dp, 300.dp)) {
                            Box(Modifier.offset(64.dp, 180.dp).size(24.dp).testTag("probe"))
                        }
                        Box(Modifier.offset(230.dp, 180.dp).size(24.dp).testTag("outside"))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertColor(Navy, pixel("small-page-cropped")) // stretching would produce Wash here
        assertColor(Marker, pixel("small-page-outside", "outside"))
    }

    private companion object {
        val Marker = Color(0xFFFF00FF)
        val Navy = Color(0xFF143368)
        val Wash = Color(0xFF169B87)
        val ChangedWash = Color(0xFF235FC1)
        val Gold = Color(0xFFCA951F)
    }
}
