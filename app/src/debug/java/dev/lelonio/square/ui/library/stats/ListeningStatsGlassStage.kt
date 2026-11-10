// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.stats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.LocalGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.HistoryAccess
import dev.lelonio.square.data.HistoryAccount
import dev.lelonio.square.data.HistoryCoverage
import dev.lelonio.square.data.HistorySession
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.library.LibraryScreen
import dev.lelonio.square.ui.navigation.FluidifyAmbientSource
import dev.lelonio.square.ui.navigation.FluidifyPageHost
import dev.lelonio.square.ui.navigation.LocalFluidifyPageAmbient
import dev.lelonio.square.ui.navigation.rememberFluidifyPageAmbient
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.pageFloor
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Invented listening data for looking at the section on a device. Debug builds only.
 *
 * It is the unit tests' `ListeningStatsFixtures`, value for value, restated here because a debug
 * source set cannot see test sources; `ListeningStatsGlassScreenshots.demoDataIsTheFixture` fails
 * if the two drift. Nothing here is read from or written to an account repository.
 */
internal object ListeningStatsDemo {
    val period = ListeningMonth(YearMonth.of(2026, 11), ZoneId.of("Europe/Rome"))
    val request = StatsRequest(HistorySession(HistoryAccess(HistoryAccount("demo-fictional-account"), 1)), period)
    val now = Instant.parse("2026-11-08T12:00:00Z").toEpochMilli()
    val summary = ListeningStats(
        period, plays = 103, localPlays = 61, spotifyOnlyPlays = 42, measuredLocalMs = 14_520_000,
        tracks = listOf(
            StatsTrack(CatalogTrack("demo:one", "DEMO · Luci lungo la strada del ritorno", "DEMO · Aurora e le Onde"), 31, 18, 13),
            StatsTrack(CatalogTrack("demo:two", "DEMO · Orbite", "DEMO · Vetro Blu"), 25, 15, 10),
            StatsTrack(CatalogTrack("demo:three", "DEMO · Un altro novembre", "DEMO · Mira"), 21, 12, 9),
            StatsTrack(CatalogTrack("demo:four", "DEMO · Senza fretta", "DEMO · Lune"), 16, 10, 6),
            StatsTrack(CatalogTrack("demo:five", "DEMO · Fino al mare", "DEMO · Nodi"), 10, 6, 4),
        ),
        artists = listOf("DEMO · Aurora e le Onde" to 31L, "DEMO · Vetro Blu" to 25L,
            "DEMO · Mira" to 21L, "DEMO · Lune" to 16L, "DEMO · Nodi" to 10L),
        recentReceipts = listOf(
            HistoryCoverage("spotify", "demo-fictional-account", "demo-limited", null, now,
                now - 86_400_000, now - 3_600_000, now, 5, true, false, "observed", null),
            HistoryCoverage("spotify", "demo-fictional-account", "demo-error", null, now - 86_400_000,
                null, null, now - 86_400_000, 0, false, true, "error", "demo_error"),
        ),
        loadedAt = now,
    )
    val ready = ListeningStatsState(request, StatsContent.Ready(summary))
    val empty = ready.copy(content = StatsContent.Ready(summary.copy(plays = 0, localPlays = 0,
        spotifyOnlyPlays = 0, measuredLocalMs = 0, tracks = emptyList(), artists = emptyList())))
    val noAccount = ListeningStatsState(request.copy(session = HistorySession()), StatsContent.NoAccount)

    /** The Library around the entry: two invented playlists and nothing else. */
    val playlists = listOf(
        CatalogPlaylist("demo:playlist-one", "DEMO · Musica del mattino"),
        CatalogPlaylist("demo:playlist-two", "DEMO · Viaggi immaginari"),
    )
}

/**
 * The section standing on the app's real glass plumbing, with nothing of the app's data in it.
 *
 * The wiring is SquareApp's, in the same order: a page-wide recording for the chrome
 * (`LocalGlassBackdrop`), and inside it the artwork alone, recorded before any page and published
 * as the canvas (`LocalFluidCanvasBackdrop`) and as the page paper (`FluidifyAmbientSource`). The
 * pages then stand in the real page host. So a pane here refracts exactly what it would in the
 * app, and a pane that sampled the wrong recording would fail here the way it would there.
 *
 * The artwork is drawn, not fetched, and deliberately uneven: a warm bright patch, a violet one
 * and a dark corner, because a pane that only ever stands on a mid grey proves nothing about
 * whether it transmits. With [flatGround] the patches are left out, which is the fastest way to
 * tell a pane that is glass from one that fell back to an opaque fill: only glass changes.
 *
 * This file uses nothing that the section did not already expose before its glass was reworked,
 * so the same stage, built on the earlier sources, is the "before" of a like-for-like comparison.
 */
@Composable
internal fun ListeningStatsGlassStage(
    dark: Boolean,
    fontScale: Float = 1f,
    state: ListeningStatsState = ListeningStatsDemo.ready,
    /** Start in the Library, with the entry; tapping it opens the page. Otherwise the page alone. */
    library: Boolean = false,
    /** Constrain the stage to this many dp, centred, to look at a narrow phone on a wide one. */
    widthDp: Int = 0,
    flatGround: Boolean = false,
    onClose: () -> Unit = {},
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        SquareTheme(darkTheme = dark, amoled = false) {
            val pageGlass = rememberGlassBackdrop()
            val ground = rememberGlassBackdrop()
            val ambient = rememberFluidifyPageAmbient()
            var stack by remember { mutableStateOf(listOf(if (library) "library" else "stats")) }
            val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                LocalGlassBackdrop provides pageGlass,
                LocalFluidCanvasBackdrop provides ground,
                LocalFluidifyPageAmbient provides ambient,
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopCenter) {
                    Box(
                        (if (widthDp > 0) Modifier.width(widthDp.dp).fillMaxHeight() else Modifier.fillMaxSize())
                            .glassBackdropSource(pageGlass),
                    ) {
                        FluidifyAmbientSource(ambient, Modifier.fillMaxSize().glassBackdropSource(ground)) {
                            DemoArtwork(dark, flatGround)
                        }
                        FluidifyPageHost(
                            stack = stack,
                            onBack = { if (stack.size > 1) stack = stack.dropLast(1) else onClose() },
                        ) { page ->
                            if (page == "library") LibraryScreen(
                                state = MainViewModel.UiState.Ready("DEMO", ListeningStatsDemo.playlists),
                                contentPadding = PaddingValues(top = statusBar, bottom = navigationBar + 24.dp),
                                ground = ground,
                                onLogIn = {}, onRetry = {}, onLogOut = {}, onOpenPlaylist = {},
                                playlistOrder = emptyList(),
                                onOpenListeningStats = { if (stack.last() == "library") stack = stack + "stats" },
                            ) else ListeningStatsPage(state, ListeningStatsDemo.period.month, {}, {}, {}, {},
                                { if (stack.size > 1) stack = stack.dropLast(1) else onClose() }, demo = true)
                        }
                    }
                }
            }
        }
    }
}

/** A drawn stand-in for the blurred cover under every page. Invented colours, no image. */
@Composable
private fun DemoArtwork(dark: Boolean, flat: Boolean) {
    val floor = pageFloor
    Canvas(Modifier.fillMaxSize()) {
        drawRect(floor)
        if (flat) return@Canvas
        val strength = if (dark) 0.62f else 0.40f
        fun patch(colour: Color, x: Float, y: Float, reach: Float, weight: Float = 1f) = drawRect(
            Brush.radialGradient(
                listOf(colour.copy(alpha = strength * weight), Color.Transparent),
                center = Offset(size.width * x, size.height * y),
                radius = size.width * reach,
            ),
        )
        patch(Color(0xFFE2A04E), x = 0.88f, y = 0.16f, reach = 0.95f)
        patch(Color(0xFF7B5CE0), x = 0.08f, y = 0.52f, reach = 0.85f)
        patch(Color(0xFF2FA89F), x = 0.72f, y = 0.92f, reach = 0.75f, weight = 0.8f)
    }
}

/**
 * Opens the stage. Debug builds only, synthetic data only, and it touches no account.
 *
 *     adb shell am start -n dev.pampa.fluidify/dev.lelonio.square.ui.library.stats.ListeningStatsGlassPreviewActivity \
 *         --es theme dark --es view page --ef font 1.0
 *
 * Extras, all optional: `theme` = dark (default) | light; `view` = page (default) | library;
 * `state` = ready (default) | empty | no-account | error | loading; `font` = 0.85..2.0;
 * `width` = a stage width in dp (for example 320); `flat` = true for the ground without artwork.
 */
class ListeningStatsGlassPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dark = intent.getStringExtra("theme") != "light"
        val library = intent.getStringExtra("view") == "library"
        val fontScale = intent.getFloatExtra("font", 1f).coerceIn(0.85f, 2f)
        val widthDp = intent.getIntExtra("width", 0).coerceIn(0, 1600)
        val flat = intent.getBooleanExtra("flat", false)
        val state = when (intent.getStringExtra("state")) {
            "empty" -> ListeningStatsDemo.empty
            "no-account" -> ListeningStatsDemo.noAccount
            "error" -> ListeningStatsDemo.ready.copy(content = StatsContent.Error)
            "loading" -> ListeningStatsDemo.ready.copy(content = StatsContent.Loading)
            else -> ListeningStatsDemo.ready
        }
        setContent {
            ListeningStatsGlassStage(dark = dark, fontScale = fontScale, state = state, library = library,
                widthDp = widthDp, flatGround = flat, onClose = { finish() })
        }
    }
}
