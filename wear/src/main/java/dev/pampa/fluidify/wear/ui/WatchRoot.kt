package dev.pampa.fluidify.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.ui.browse.BrowseScreen
import dev.pampa.fluidify.wear.ui.more.MoreScreen
import dev.pampa.fluidify.wear.ui.player.ImmersiveScreen
import dev.pampa.fluidify.wear.ui.player.PlayerScreen
import kotlinx.coroutines.launch

/**
 * How the watch app is put together, the way Spotify's is:
 *
 * ```
 *              [ cover only ]         swipe down from the player
 *   [ player ] [ more ]               swipe right-to-left for "more"
 *              [ browse ]             swipe up from the player
 * ```
 *
 * Pages slide; nothing fades between them. The player is page one of the
 * vertical pager and page zero of the horizontal one, so the app opens on it and
 * a left-to-right swipe there is still the system's back.
 */
@Composable
fun WatchRoot(app: WearApp, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val vertical = rememberPagerState(initialPage = PAGE_MAIN) { 3 }
    val horizontal = rememberPagerState(initialPage = 0) { 2 }

    AppScaffold(modifier = modifier) {
        VerticalPager(
            state = vertical,
            // The bezel belongs to the volume on the player, not to paging.
            rotaryScrollableBehavior = null,
        ) { page ->
            when (page) {
                PAGE_IMMERSIVE -> ImmersiveScreen(app.controls, app.art)
                PAGE_MAIN -> HorizontalPager(state = horizontal) { inner ->
                    when (inner) {
                        0 -> PlayerScreen(
                            controls = app.controls,
                            art = app.art,
                            onBrowse = { scope.launch { vertical.animateScrollToPage(PAGE_BROWSE) } },
                            onOutput = { scope.launch { horizontal.animateScrollToPage(1) } },
                            onEssentials = { scope.launch { horizontal.animateScrollToPage(1) } },
                        )
                        else -> MoreScreen(
                            controls = app.controls,
                            onOutput = { },
                        )
                    }
                }
                else -> BrowseScreen()
            }
        }
    }
}

private const val PAGE_IMMERSIVE = 0
private const val PAGE_MAIN = 1
private const val PAGE_BROWSE = 2
