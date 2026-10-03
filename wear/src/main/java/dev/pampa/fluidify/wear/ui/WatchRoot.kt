package dev.pampa.fluidify.wear.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.protocol.LibrarySection
import dev.pampa.fluidify.wear.ui.browse.ContextScreen
import dev.pampa.fluidify.wear.ui.browse.HomeScreen
import dev.pampa.fluidify.wear.ui.browse.LibraryScreen
import dev.pampa.fluidify.wear.ui.browse.SearchScreen
import dev.pampa.fluidify.wear.ui.browse.SectionScreen
import dev.pampa.fluidify.wear.ui.debug.GlassMeter
import dev.pampa.fluidify.wear.ui.more.MoreScreen
import dev.pampa.fluidify.wear.ui.player.ImmersiveScreen
import dev.pampa.fluidify.wear.ui.player.PlayerScreen
import dev.pampa.fluidify.wear.ui.sheets.EssentialsScreen
import dev.pampa.fluidify.wear.ui.sheets.OutputScreen
import dev.pampa.fluidify.wear.ui.sheets.QueueScreen
import dev.pampa.fluidify.wear.ui.sheets.SleepScreen
import dev.pampa.fluidify.wear.ui.sheets.VolumeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * How the watch app is put together, the way Spotify's is:
 *
 * ```
 *              [ cover only ]         swipe down from the player
 *   [ player ] [ more ]               swipe right-to-left for "more"
 *              [ home ]               swipe up from the player
 * ```
 *
 * Everything else (queue, output, timer, volume, library, a playlist, search)
 * opens on top and is swiped away to the right, Wear's back. Pages slide;
 * nothing fades between them.
 */
@Composable
fun WatchRoot(app: WearApp, modifier: Modifier = Modifier, showPlayer: Flow<Unit> = emptyFlow()) {
    val nav = rememberSwipeDismissableNavController()
    val scope = rememberCoroutineScope()
    val vertical = rememberPagerState(initialPage = PAGE_MAIN) { 3 }
    val horizontal = rememberPagerState(initialPage = 0) { 2 }
    val phoneVersion = remember {
        app.link.phone.map { it?.versionName }.stateIn(app.scope, SharingStarted.Eagerly, app.link.phone.value?.versionName)
    }
    val toPlayer: () -> Unit = { backToPlayer(nav, scope, vertical, horizontal) }
    val open: (String, String) -> Unit = { uri, title -> nav.navigate(contextRoute(uri, title)) }
    // The watch face icon, the tile and the complication all land on the player.
    LaunchedEffect(showPlayer) { showPlayer.collect { toPlayer() } }

    AppScaffold(modifier = modifier) {
        SwipeDismissableNavHost(navController = nav, startDestination = HOME) {
            composable(HOME) {
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
                                    volume = app.volume,
                                    onBrowse = { scope.launch { vertical.animateScrollToPage(PAGE_HOME) } },
                                    onOutput = { nav.navigate(OUTPUT) },
                                    onEssentials = { nav.navigate(ESSENTIALS) },
                                )
                                else -> MoreScreen(
                                    controls = app.controls,
                                    onOutput = { nav.navigate(OUTPUT) },
                                    onVolume = { nav.navigate(VOLUME) },
                                    onSleep = { nav.navigate(SLEEP) },
                                    updater = app.updater,
                                    phoneVersion = phoneVersion,
                                    surfaces = app.surfacePrefs,
                                    onSurfacesChanged = app.surfaces::onPrefsChanged,
                                    glassMeter = app.glassMeter,
                                )
                            }
                        }
                        else -> HomeScreen(
                            app = app,
                            onSearch = { nav.navigate(SEARCH) },
                            onLibrary = { nav.navigate(LIBRARY) },
                            onOpen = open,
                        )
                    }
                }
            }
            composable(ESSENTIALS) {
                EssentialsScreen(
                    controls = app.controls,
                    onQueue = { nav.navigate(QUEUE) },
                    onSleep = { nav.navigate(SLEEP) },
                    onOpenContext = { uri -> open(uri, "") },
                    onRadio = toPlayer,
                )
            }
            composable(QUEUE) { QueueScreen(app.controls, app.art, load = app.library::queue, onPlayed = toPlayer) }
            composable(OUTPUT) { OutputScreen(app.controls, load = app.library::devices, onChosen = { nav.popBackStack() }) }
            composable(SLEEP) { SleepScreen(app.controls, onSet = { nav.popBackStack() }) }
            composable(VOLUME) { VolumeScreen(app.controls, app.volume) }
            composable(LIBRARY) {
                LibraryScreen(onSection = { section, title -> nav.navigate(sectionRoute(section, title)) })
            }
            composable("$SECTION/{section}?title={title}") { entry ->
                val section = entry.arguments?.getString("section")?.let { runCatching { LibrarySection.valueOf(it) }.getOrNull() }
                    ?: LibrarySection.PLAYLISTS
                SectionScreen(
                    app = app,
                    section = section,
                    title = entry.arguments?.getString("title").orEmpty(),
                    onOpen = open,
                    onPlayTrack = { uri ->
                        app.controls.playContext(uri)
                        toPlayer()
                    },
                )
            }
            composable("$CONTEXT?uri={uri}&title={title}") { entry ->
                ContextScreen(
                    app = app,
                    uri = entry.arguments?.getString("uri").orEmpty(),
                    title = entry.arguments?.getString("title").orEmpty(),
                    onPlaying = toPlayer,
                )
            }
            composable(SEARCH) { SearchScreen(app, onOpen = open, onPlaying = toPlayer) }
        }
        val meter by app.glassMeter.enabled.collectAsStateWithLifecycle()
        if (meter) GlassMeter()
    }
}

/** Back to the player, wherever the person was: the stack emptied and the pagers reset. */
private fun backToPlayer(nav: NavHostController, scope: CoroutineScope, vertical: PagerState, horizontal: PagerState) {
    nav.popBackStack(HOME, inclusive = false)
    scope.launch {
        vertical.scrollToPage(PAGE_MAIN)
        horizontal.scrollToPage(0)
    }
}

private fun contextRoute(uri: String, title: String) = "$CONTEXT?uri=${Uri.encode(uri)}&title=${Uri.encode(title)}"
private fun sectionRoute(section: LibrarySection, title: String) = "$SECTION/${section.name}?title=${Uri.encode(title)}"

private const val HOME = "home"
private const val ESSENTIALS = "essentials"
private const val QUEUE = "queue"
private const val OUTPUT = "output"
private const val SLEEP = "sleep"
private const val VOLUME = "volume"
private const val LIBRARY = "library"
private const val SECTION = "section"
private const val CONTEXT = "context"
private const val SEARCH = "search"

private const val PAGE_IMMERSIVE = 0
private const val PAGE_MAIN = 1
private const val PAGE_HOME = 2
