package dev.lelonio.square.ui.player

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w1400dp-h1000dp-mdpi")
class AdaptivePlayerLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resizingMovesWholeControlsAndRetainsOwnersAndScroll() {
        val width = mutableStateOf(839)
        val scale = mutableStateOf(1f)
        val height = mutableStateOf(600)
        var starts = 0
        var stops = 0
        var owner: Any? = null
        var original: Any? = null
        val scroll = androidx.compose.foundation.ScrollState(0)
        compose.setContent {
            AdaptivePlayerLayout(
                twoPane = playerUsesTwoPanes(width.value.dp, height.value.dp, scale.value), panelOpen = true,
                modifier = Modifier.requiredSize(width.value.dp, height.value.dp),
                media = { Box(Modifier.fillMaxSize().testTag("media")) },
                detail = {
                    val remembered = remember { Any() }
                    SideEffect { owner = remembered }
                    DisposableEffect(Unit) { starts++; onDispose { stops++ } }
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("detail")) {
                        repeat(40) { Text("Queue $it", Modifier.height(48.dp)) }
                    }
                },
                controls = { Box(Modifier.fillMaxWidth().height(240.dp).testTag("controls")) },
            )
        }
        compose.runOnIdle { original = owner }
        compose.onNodeWithText("Queue 12").performScrollTo()
        val before = scroll.value
        for (size in listOf(840, 899, 900, 1280, 839, 900)) {
            compose.runOnIdle { width.value = size }
            val media = compose.onNodeWithTag("media").fetchSemanticsNode().boundsInRoot
            val controls = compose.onNodeWithTag("controls").fetchSemanticsNode().boundsInRoot
            val detail = compose.onNodeWithTag("detail").fetchSemanticsNode().boundsInRoot
            if (size >= 840) {
                assertEquals(24f, controls.left - media.right, 1f)
                assertEquals(.45f, media.width / (media.width + controls.width), .002f)
                assertTrue(controls.width <= 560f)
                assertTrue(controls.right - media.left <= 1200f)
                assertEquals(controls.left, detail.left, 1f)
                assertTrue(detail.top >= controls.bottom)
            } else {
                assertEquals(media.left, controls.left, 1f)
                assertTrue(controls.top >= detail.bottom)
            }
            compose.runOnIdle {
                assertSame(original, owner)
                assertEquals(1, starts)
                assertEquals(0, stops)
                assertEquals(before, scroll.value)
            }
        }
        compose.runOnIdle { scale.value = 1.5f }
        val media = compose.onNodeWithTag("media").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("controls").fetchSemanticsNode().boundsInRoot
        assertEquals(media.left, controls.left, 1f)
        assertTrue(controls.width <= 560f)
        compose.runOnIdle { scale.value = 2f }
        assertEquals(
            compose.onNodeWithTag("media").fetchSemanticsNode().boundsInRoot.left,
            compose.onNodeWithTag("controls").fetchSemanticsNode().boundsInRoot.left, 1f,
        )
        compose.runOnIdle { scale.value = 1f; height.value = 479 }
        assertEquals(
            compose.onNodeWithTag("media").fetchSemanticsNode().boundsInRoot.left,
            compose.onNodeWithTag("controls").fetchSemanticsNode().boundsInRoot.left, 1f,
        )
    }

    @Test fun shortWindowKeepsFinalActionReachableByBoundedScroll() {
        compose.setContent {
            AdaptivePlayerLayout(
                twoPane = playerUsesTwoPanes(720.dp, 360.dp, 2f), panelOpen = true,
                modifier = Modifier.requiredSize(720.dp, 360.dp),
                media = { Box(Modifier.fillMaxSize()) },
                detail = { Box(Modifier.fillMaxSize()) },
                controls = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        repeat(10) { Text("Control $it", Modifier.height(48.dp)) }
                        Text("Final action", Modifier.height(48.dp))
                    }
                },
            )
        }
        compose.onNodeWithText("Final action").performScrollTo().assertIsDisplayed()
    }

    @Test fun tabletPanelRetainsReadingSpaceWhenControlsHaveLongContent() {
        compose.setContent {
            AdaptivePlayerLayout(
                twoPane = true, panelOpen = true,
                modifier = Modifier.requiredSize(840.dp, 480.dp),
                media = { Box(Modifier.fillMaxSize()) },
                detail = { Box(Modifier.fillMaxSize().testTag("panel")) },
                controls = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("controls")) {
                        repeat(12) { Text("Long title and control $it", Modifier.height(48.dp)) }
                        Text("Last tablet action", Modifier.height(48.dp))
                    }
                },
            )
        }
        val panel = compose.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("controls").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.height >= 192f)
        assertTrue(panel.height >= 480f * .42f - 1f)
        assertEquals(controls.bottom, panel.top, 1f)
        compose.onNodeWithText("Last tablet action").performScrollTo().assertIsDisplayed()
    }

    @Test fun tabletCurrentVerseStaysNearTransportWithContextAndSeekAcrossResize() {
        val width = mutableStateOf(1280)
        val height = mutableStateOf(800)
        val lyrics = dev.lelonio.square.data.Lyrics(
            (0..24).map { dev.lelonio.square.data.LyricLine(it * 10_000L, "Verse $it") },
            synced = true,
        )
        var seek: Long? = null
        compose.setContent {
            AdaptivePlayerLayout(
                twoPane = playerUsesTwoPanes(width.value.dp, height.value.dp, 1f), panelOpen = true,
                modifier = Modifier.requiredSize(width.value.dp, height.value.dp),
                media = { Box(Modifier.fillMaxSize()) },
                detail = {
                    LyricsView(
                        lyrics, remember { mutableLongStateOf(80_000L) }, false,
                        controlsAbove = playerUsesTwoPanes(width.value.dp, height.value.dp, 1f),
                        onSeek = { seek = it },
                    )
                },
                controls = { Box(Modifier.fillMaxWidth().height(240.dp).testTag("transport")) },
            )
        }
        for ((w, h) in listOf(1280 to 800, 840 to 600, 839 to 600, 1280 to 800)) {
            compose.runOnIdle { width.value = w; height.value = h }
            compose.mainClock.advanceTimeBy(1500)
            compose.onNodeWithText("Verse 8").assertIsDisplayed()
            compose.onNodeWithText("Verse 9").assertIsDisplayed()
            if (w >= 840) {
                val verse = compose.onNodeWithText("Verse 8").fetchSemanticsNode().boundsInRoot
                val transport = compose.onNodeWithTag("transport").fetchSemanticsNode().boundsInRoot
                assertTrue("Current words should follow the transport closely", verse.top - transport.bottom in 24f..128f)
                compose.onNodeWithText("Verse 7").assertIsDisplayed()
            }
        }
        compose.onNodeWithText("Verse 9").performClick()
        compose.runOnIdle { assertEquals(90_000L, seek ?: -1L) }
    }
}
