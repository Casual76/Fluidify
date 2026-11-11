package dev.lelonio.square.ui.airpods

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.AirPodsOnboardingAccess
import dev.lelonio.square.ui.home.HomeScreen
import dev.lelonio.square.ui.onboarding.OnboardingScreen
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared, not executed. All state and artwork are synthetic/offline. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[36], application=Application::class, qualifiers="w360dp-h640dp-mdpi")
class AirPodsHomeEntryTest {
    @get:Rule val compose = createComposeRule()

    private fun home(w: Int = 360, h: Int = 640, font: Float = 1f,
        friends: Boolean = false, inset: Int = 0, state: AirPodsUiState = uiFixture()) {
        compose.setContent {
            val ground = rememberGlassBackdrop()
            val density = LocalDensity.current
            SquareTheme(darkTheme=true, amoled=false, systemBars=false) {
                CompositionLocalProvider(LocalGlassBackdrop provides ground,
                    LocalDensity provides Density(density.density, font),
                    dev.lelonio.square.ui.theme.LocalPageEndInset provides inset.dp,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.requiredSize(w.dp,h.dp).testTag("home-fixture")) {
                        HomeScreen(state=MainViewModel.UiState.Ready("Fixture",emptyList()),
                            contentPadding=PaddingValues(0.dp),ground=ground,
                            onLogIn={},onRetry={},onLogOut={},onOpenPlaylist={},playlistOrder=emptyList(),
                            recent=emptyList(),onPlayRecent={_,_->},onPlayFeed={_,_,_->},
                            feed=MainViewModel.FeedState(newReleases=List(8) {
                                dev.lelonio.square.data.SearchItem("fixture:album:$it","Album $it","Fixture",null)
                            }),onOpenItem={},onOpenSettings={},
                            friends=if(friends) listOf(dev.lelonio.square.data.FriendListen(
                                "fixture","Friend",null,"track","Song","Artist",null,"Album",0)) else emptyList(),
                            airPods=AirPodsUiBinding(state,{},{}))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertCenteredAndSafe() {
        val capsule = compose.onNodeWithTag("airpods-home-capsule",useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val mark = compose.onNodeWithTag("home-brand",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val trailing = compose.onNodeWithTag("home-airpods-trailing-boundary",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue("brand must not overlap", capsule.left >= mark.right - 1f)
        assertTrue("friends/avatar boundary must not overlap", capsule.right <= trailing.left + 1f)
        assertEquals((mark.right + trailing.left)/2f, capsule.center.x, 1.5f)
        // Check actual glyph layout, not just the outer capsule bounds: the charge under each
        // picture is whole and inside the one target.
        val body = compose.onNodeWithTag("airpods-capsule-body",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        for (tag in listOf("airpods-capsule-earbuds-text", "airpods-capsule-case-text")) {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag(tag, useUnmergedTree=true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(1, results.size)
            assertFalse(results.single().hasVisualOverflow)
            assertFalse(results.single().isLineEllipsized(0))
            val bounds = compose.onNodeWithTag(tag, useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= body.left && bounds.right <= body.right)
            assertTrue(bounds.top >= body.top && bounds.bottom <= body.bottom)
        }
        compose.onNodeWithTag("airpods-capsule-earbuds-text", useUnmergedTree=true).assertTextEquals("L 80%")
        compose.onNodeWithTag("airpods-capsule-body",useUnmergedTree=true)
            .assertContentDescriptionContains("80%", substring=true)
        // One target; the mode action the capsule used to carry is gone.
        assertTrue("capsule body width", body.width >= 48f)
        assertTrue("capsule body height", body.height >= 48f)
        compose.onNodeWithTag("airpods-capsule-quick",useUnmergedTree=true).assertDoesNotExist()
    }
    private fun capture(name: String) {
        java.io.File("build/airpods-full-integration/implementation/home-entry/screenshots").mkdirs()
        compose.onRoot().captureRoboImage("build/airpods-full-integration/implementation/home-entry/screenshots/$name.png")
    }
    private fun collapsed(name: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(5)
        compose.waitForIdle()
        compose.onNodeWithTag("airpods-home-capsule",useUnmergedTree=true).assertDoesNotExist()
        capture(name)
    }
    @Test fun primary360Font1VisibleCenteredCompactAndCollapsed() {
        home(); assertCenteredAndSafe(); capture("home-360x640-f1"); collapsed("home-360x640-f1-collapsed")
    }
    @Config(qualifiers="w720dp-h360dp-mdpi")
    @Test fun residualCenter720() { home(720,360); assertCenteredAndSafe(); capture("home-720x360-f1") }
    @Config(qualifiers="w839dp-h640dp-mdpi")
    @Test fun friendsAndEndInset839() { home(839,640,1.5f,true,240); assertCenteredAndSafe(); capture("home-839-f1_5-friends-inset240") }
    @Config(qualifiers="w840dp-h640dp-mdpi")
    @Test fun friendsAndEndInset840() { home(840,640,2f,true,240); assertCenteredAndSafe(); capture("home-840-f2-friends-inset240") }
    @Test fun primaryFont15RemainsCentered() { home(font=1.5f); assertCenteredAndSafe(); capture("home-360-f1_5") }
    @Test fun primaryFont2RemainsCenteredWhenMeasuredRoomFits() { home(font=2f); assertCenteredAndSafe(); capture("home-360-f2") }
    @Test fun noSetupHidesCapsule() {
        home(state=uiFixture().copy(pair=dev.lelonio.square.airpods.runtime.storage.PairSettings()))
        compose.onNodeWithTag("airpods-home-capsule",useUnmergedTree=true).assertDoesNotExist()
    }
    @Test fun productionGateOffHidesCapsule() {
        home(state=uiFixture().copy(productGatePassed=false))
        compose.onNodeWithTag("airpods-home-capsule",useUnmergedTree=true).assertDoesNotExist()
    }
    @Test fun invalidAndInsufficientGeometryHides() {
        assertFalse(airPodsHeaderFits(Float.NaN,10f,0f,96f,0f))
        assertFalse(airPodsHeaderFits(100f,24f,0f,96f,0f,0f))
        assertFalse(airPodsHeaderFits(500f,24f,0f,96f,1f,0f))
    }
    @Test fun signedOutOfflineEntryClosesAndReopensWithoutFinishingOrAuthenticating() {
        var logins=0; var finishes=0
        var back: androidx.activity.OnBackPressedDispatcherOwner? = null
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
            SquareTheme(darkTheme=false,amoled=false,systemBars=false) {
                CompositionLocalProvider(LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    AirPodsOnboardingAccess(settings = { close ->
                        Column {
                            Text("Offline setup",Modifier.testTag("offline-setup"))
                            FluidButton("Close",close,Modifier.testTag("close-offline"))
                        }
                    }) { open ->
                        OnboardingScreen(MainViewModel.UiState.LoggedOut, MainViewModel.WebApiState(),
                            {logins++},{},{},"",{}, {finishes++},onOpenAirPods=open)
                    }
                }
            }
        }
        repeat(2) {
            compose.onNodeWithTag("onboarding-airpods-entry").assertIsDisplayed().performClick()
            compose.onNodeWithTag("offline-setup").assertIsDisplayed()
            if (it == 0) compose.runOnIdle { requireNotNull(back).onBackPressedDispatcher.onBackPressed() }
            else compose.onNodeWithTag("close-offline").performClick()
            compose.onNodeWithTag("onboarding-airpods-entry").assertIsDisplayed()
        }
        assertEquals(0,logins); assertEquals(0,finishes)
    }
    @Test fun loggedOutHomeRetainsSettingsEntryWithProductGateOff() {
        var opened=0
        compose.setContent {
            val ground=rememberGlassBackdrop()
            SquareTheme(darkTheme=false,amoled=false,systemBars=false) {
                HomeScreen(state=MainViewModel.UiState.LoggedOut,
                    contentPadding=PaddingValues(0.dp),ground=ground,
                    onLogIn={},onRetry={},onLogOut={},onOpenPlaylist={},playlistOrder=emptyList(),
                    recent=emptyList(),onPlayRecent={_,_->},onPlayFeed={_,_,_->},
                    feed=MainViewModel.FeedState(),onOpenItem={},onOpenSettings={opened++},
                    airPods=AirPodsUiBinding(uiFixture().copy(productGatePassed=false),{},{}))
            }
        }
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(dev.lelonio.square.R.string.settings)).assertIsDisplayed().performClick()
        assertEquals(1,opened)
    }

}
