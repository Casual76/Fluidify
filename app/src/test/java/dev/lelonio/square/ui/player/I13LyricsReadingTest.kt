package dev.lelonio.square.ui.player

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w400dp-h700dp-mdpi")
class I13LyricsReadingTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val source = Lyrics(List(40) { LyricLine(it * 1_000L, "Verse $it") }, true)
    private fun list() = compose.onNodeWithTag("wf_i13_lyrics_list")
    private fun offset() = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun follow() = compose.onNodeWithText(context.getString(R.string.wf_i13_follow_song))

    @Composable private fun Theme(content: @Composable () -> Unit) {
        SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
            val backdrop = rememberGlassBackdrop()
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                Box(Modifier.requiredSize(400.dp, 700.dp)) { content() }
            }
        }
    }

    @Test fun semanticManualReadingSurvivesLineChangesAndTranslationThenCanResume() {
        val position = mutableStateOf(8_000L)
        val document = mutableStateOf(source)
        compose.setContent { Theme {
            LyricsView(document.value, position, false, showTranslation = true,
                scrollIdentity = source, onSeek = {})
        } }
        list().performScrollToIndex(25)
        follow().assertExists()
        compose.onNodeWithText("Verse 25").assertIsDisplayed()
        val before = offset()
        for (at in listOf(15_000L, 20_000L, 30_000L)) {
            compose.runOnIdle { position.value = at }
            compose.mainClock.advanceTimeBy(1_000)
            assertEquals(before, offset(), .001f)
        }
        compose.runOnIdle {
            document.value = source.copy(lines = source.lines.map { it.copy(translation = "Translated ${it.text}") })
        }
        follow().assertExists()
        assertEquals(before, offset(), .001f)
        follow().performClick()
        compose.mainClock.advanceTimeBy(2_000)
        follow().assertDoesNotExist()
        compose.onNodeWithText("Verse 30").assertIsDisplayed()
        assertTrue(offset() > before)
    }

    @Test fun touchScrollIsManualAndLineTapSeeksAndResumes() {
        val position = mutableStateOf(8_000L)
        val seeks = mutableListOf<Long>()
        compose.setContent { Theme {
            LyricsView(source, position, false, onSeek = { seeks += it; position.value = it })
        } }
        list().performTouchInput { swipeUp() }
        follow().assertExists()
        val before = offset()
        compose.runOnIdle { position.value = 25_000L }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(before, offset(), .001f)
        // Pick a visible row, independently of the fling's final distance.
        val row = compose.onAllNodes(hasClickAction() and hasText("Verse", substring = true)).onFirst()
        row.performClick()
        compose.runOnIdle { assertEquals(1, seeks.size) }
        follow().assertDoesNotExist()
    }

    @Test fun unsyncedReadingDoesNotOfferFollowAndNewTrackStartsFresh() {
        val position = mutableStateOf(0L)
        val document = mutableStateOf(source.copy(synced = false))
        compose.setContent { Theme { LyricsView(document.value, position, false, onSeek = {}) } }
        list().performScrollToIndex(25)
        val before = offset()
        compose.runOnIdle { position.value = 30_000L }
        assertEquals(before, offset(), .001f)
        follow().assertDoesNotExist()
        compose.runOnIdle {
            position.value = 0L
            document.value = Lyrics(List(40) { LyricLine(it * 1_000L, "New verse $it") }, true)
        }
        compose.onNodeWithText("New verse 0").assertIsDisplayed()
        follow().assertDoesNotExist()
    }

    @Test fun advancingPlaybackClockCannotReclaimManualReading() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Theme {
            LyricsView(source, remember { mutableStateOf(0L) }, true, onSeek = {})
        } }
        compose.mainClock.advanceTimeBy(2_000)
        list().performScrollToIndex(25)
        compose.mainClock.advanceTimeByFrame()
        val before = offset()
        compose.mainClock.advanceTimeBy(6_000)
        assertEquals(before, offset(), .001f)
        follow().assertExists().performClick()
        compose.mainClock.advanceTimeBy(2_000)
        follow().assertDoesNotExist()
        assertTrue(offset() < before)
    }
}
