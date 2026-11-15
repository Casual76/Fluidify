package dev.lelonio.square.ui.library

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.DownloadState
import dev.lelonio.square.data.OwnerState
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared, NOT EXECUTED. No provider or network calls; real controls and synthetic state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w400dp-h900dp-mdpi")
class I09DownloadStatusUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val tracks = listOf(CatalogTrack("a", "Downloaded song", "Artist"), CatalogTrack("b", "Missing song", "Artist"))
    private val detail = mutableStateOf(MainViewModel.PlaylistState(uri = "spotify:playlist:a", name = "A", tracks = tracks))
    private val owner = mutableStateOf<OwnerState>(OwnerState.Partial(1, 2))
    private val incomplete = mutableStateOf(false)
    private val failures = mutableStateOf(1)
    private val offline = mutableStateOf(false)
    private var retry = 0
    private var sourceRetry = 0
    private var remove = 0
    private fun mount() {
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                val backdrop = rememberGlassBackdrop()
                val modal = rememberFluidGlassModalHostState()
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                    LocalFluidGlassModalHostState provides modal, LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.fillMaxSize()) {
                        PlaylistScreen(detail.value, PaddingValues(0.dp), null, onBack = {}, onPlay = { _, _, _ -> },
                            onEnqueue = {}, shuffleOn = false, onToggleShuffle = {}, onAddToPlaylist = {}, onTrackMenu = {},
                            downloadState = owner.value, downloadSourceIncomplete = incomplete.value,
                            downloadFailures = failures.value, onRetryDownload = { retry++ }, onToggleDownload = { remove++ },
                            onRetryDownloadSource = { sourceRetry++ },
                            canDownload = true, offline = offline.value,
                            trackDownload = { if (it.uri == "a") DownloadState.Done else DownloadState.Failed("synthetic") })
                        FluidGlassModalHost(modal, backdrop)
                    }
                }
            }
        }
    }
    @Test fun partialCountsAndFailuresHaveExplicitRetryWhileRemovalStillWorks() {
        mount()
        compose.onNodeWithTag("wf_i09_download_status").assertTextContains(context.getString(R.string.wf_i09_download_count, 1, 2), substring = true)
        compose.onNodeWithTag("wf_i09_retry_download").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retry); assertEquals(0, remove) }
        compose.onNodeWithContentDescription(context.getString(R.string.remove_download)).performClick()
        compose.runOnIdle { assertEquals(1, remove) }
        compose.onNodeWithText("Missing song").performScrollTo()
        compose.onNodeWithContentDescription(context.getString(R.string.wf_i09_file_failed), useUnmergedTree = true).assertExists()
    }
    @Test fun equalKnownCountsRemainReadingThenIncompleteWithoutInventingFileFailures() {
        owner.value = OwnerState.Partial(2, 2)
        incomplete.value = true
        failures.value = 0
        detail.value = detail.value.copy(loadingMore = true)
        mount()
        compose.onNodeWithTag("wf_i09_download_status").assertTextContains(context.getString(R.string.wf_i08_download_reading), substring = true)
        compose.onNodeWithTag("wf_i09_retry_download").assertDoesNotExist()
        compose.runOnIdle { detail.value = detail.value.copy(loadingMore = false, error = "synthetic interruption") }
        compose.onNodeWithTag("wf_i09_download_status").assertTextContains(context.getString(R.string.wf_i08_download_incomplete), substring = true)
        compose.onNodeWithTag("wf_i09_retry_source").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, sourceRetry); assertEquals(0, retry) }
        compose.onNodeWithTag("wf_i09_retry_download").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.remove_download)).performClick()
        compose.runOnIdle { assertEquals(1, remove) }
    }
    @Test fun completedOwnerHasNoFailureRecoveryAction() {
        owner.value = OwnerState.Complete
        failures.value = 0
        mount()
        compose.onNodeWithTag("wf_i09_download_status").assertTextEquals(context.getString(R.string.downloaded))
        compose.onNodeWithTag("wf_i09_retry_download").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.remove_download)).performClick()
        compose.runOnIdle { assertEquals(1, remove) }
    }
    @Test fun offlineMissingFilesRemainVisibleAndRemovableWithoutNetworkRetry() {
        offline.value = true
        mount()
        compose.onNodeWithTag("wf_i09_download_status").assertTextContains(context.getString(R.string.wf_i09_download_failed, 1), substring = true)
        compose.onNodeWithTag("wf_i09_retry_download").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.remove_download)).performClick()
        compose.runOnIdle { assertEquals(0, retry); assertEquals(1, remove) }
    }
}
