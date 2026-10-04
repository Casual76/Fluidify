package dev.pampa.fluidify.wear.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.VerticalPagerScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.protocol.LibrarySection
import dev.pampa.fluidify.wear.system.PlayerIntents
import dev.pampa.fluidify.wear.ui.browse.AddToPlaylistScreen
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
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
 * opens on top and is swiped away to the right, Wear's back. Pages slide with
 * Wear's own page transition ([AnimatedPage]: the page leaving shrinks a little
 * under a scrim while the next one slides over it), so a swipe reads as moving
 * between layers rather than as a strip of screens being dragged.
 */
@Composable
fun WatchRoot(
    app: WearApp,
    modifier: Modifier = Modifier,
    requests: StateFlow<PlayerIntents.Request?> = MutableStateFlow(null),
    onRequestHandled: () -> Unit = {},
) {
    val nav = rememberSwipeDismissableNavController()
    val scope = rememberCoroutineScope()
    val vertical = rememberPagerState(initialPage = PAGE_MAIN) { 3 }
    val horizontal = rememberPagerState(initialPage = 0) { 2 }
    val phoneVersion = remember {
        app.link.phone.map { it?.versionName }.stateIn(scope, SharingStarted.Eagerly, app.link.phone.value?.versionName)
    }
    val toPlayer: () -> Unit = { backToPlayer(nav, scope, vertical, horizontal) }
    val navigator = remember(nav) { Navigator(nav) }
    val go: (String) -> Unit = navigator::go
    val open: (String, String) -> Unit = { uri, title -> go(contextRoute(uri, title)) }
    // Taking a like back asks first, from the player, the cover page and the tile alike.
    var confirmUnlike by remember { mutableStateOf(false) }
    var unlikeUri by remember { mutableStateOf<String?>(null) }
    val askUnlike: () -> Unit = {
        unlikeUri = app.controls.nowPlaying.value.snapshot?.track?.uri
        confirmUnlike = unlikeUri != null
    }
    val addToPlaylist: () -> Unit = {
        app.controls.nowPlaying.value.snapshot?.track?.let { track -> go(addToPlaylistRoute(track.uri, track.title)) }
    }
    // The watch face icon, the tile and the complication all land on the player; the tile's
    // filled heart lands on it with the question.
    val request by requests.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        val asked = request ?: return@LaunchedEffect
        toPlayer()
        if (asked == PlayerIntents.Request.UPDATES) horizontal.scrollToPage(1)
        if (asked == PlayerIntents.Request.CONFIRM_UNLIKE && app.controls.nowPlaying.value.snapshot?.liked == true) askUnlike()
        onRequestHandled()
    }
    val playerActive by remember {
        derivedStateOf {
            (vertical.currentPage == PAGE_MAIN || vertical.isScrollInProgress) &&
                (horizontal.currentPage == 0 || horizontal.isScrollInProgress)
        }
    }
    val immersiveActive by remember {
        derivedStateOf { vertical.currentPage == PAGE_IMMERSIVE || vertical.isScrollInProgress }
    }
    // What the frame log (dev builds) says was on screen when a frame ran late.
    val backStack by nav.currentBackStackEntryAsState()
    val scene by remember {
        derivedStateOf {
            val page = when (vertical.targetPage) {
                PAGE_IMMERSIVE -> "cover"
                PAGE_MAIN -> if (horizontal.targetPage == 0) "player" else "more"
                else -> "home"
            }
            val moving = if (vertical.isScrollInProgress || horizontal.isScrollInProgress) "swipe " else ""
            moving + page
        }
    }
    LaunchedEffect(scene, backStack) {
        val route = backStack?.destination?.route?.substringBefore('?')
        dev.pampa.fluidify.wear.ui.debug.FrameLog.scene = if (route == null || route == HOME) scene else route
    }

    val shown by app.controls.nowPlaying.collectAsStateWithLifecycle()
    dev.pampa.fluidify.wear.ui.player.WatchAmbientSurface(shown, modifier) {
    AppScaffold {
        Box(Modifier.fillMaxSize()) {
        SwipeDismissableNavHost(navController = nav, startDestination = HOME) {
            composable(HOME) {
                // No page dots: the player is a full-bleed cover with things on every edge, and
                // Spotify's watch app (whose shape this is) goes without them too. The pages are
                // told whether they are on screen, because the pager keeps the neighbours composed
                // so the Home is ready before the swipe reaches it — and a ring ticking on a page
                // nobody can see is battery spent on nothing.
                VerticalPagerScaffold(pagerState = vertical, pageIndicator = null) {
                    VerticalPager(
                        state = vertical,
                        beyondViewportPageCount = 1,
                        // The bezel belongs to the volume on the player, not to paging.
                        rotaryScrollableBehavior = null,
                    ) { page ->
                        AnimatedPage(pageIndex = page, pagerState = vertical) {
                            when (page) {
                                PAGE_IMMERSIVE -> ScreenScaffold(timeText = {}) { _ ->
                                    ImmersiveScreen(app.controls, app.art, active = immersiveActive, onUnlike = askUnlike, volume = app.volume)
                                }
                                PAGE_MAIN -> HorizontalPagerScaffold(pagerState = horizontal, pageIndicator = null) {
                                    HorizontalPager(state = horizontal) { inner ->
                                        AnimatedPage(pageIndex = inner, pagerState = horizontal) {
                                            when (inner) {
                                                0 -> ScreenScaffold(timeText = {}) { _ ->
                                                    PlayerScreen(
                                                        controls = app.controls,
                                                        art = app.art,
                                                        volume = app.volume,
                                                        status = standaloneStatus(app),
                                                        active = playerActive,
                                                        onQueue = { go(QUEUE) },
                                                        onOutput = { go(OUTPUT) },
                                                        onEssentials = { go(ESSENTIALS) },
                                                        onUnlike = askUnlike,
                                                        onAddToPlaylist = addToPlaylist,
                                                    )
                                                }
                                                else -> MoreScreen(
                                                    controls = app.controls,
                                                    onOutput = { go(OUTPUT) },
                                                    onVolume = { go(VOLUME) },
                                                    onSleep = { go(SLEEP) },
                                                    updater = app.updater,
                                                    phoneVersion = phoneVersion,
                                                    surfaces = app.surfacePrefs,
                                                    onSurfacesChanged = app.surfaces::onPrefsChanged,
                                                    onMirrorChanged = {
                                                        app.surfaces.onPrefsChanged()
                                                        app.scope.launch { app.surfaces.publishWatchSurfaces() }
                                                    },
                                                    glassMeter = app.glassMeter,
                                                    standalone = app.standalone,
                                                    onDownloads = { go(WATCH_DOWNLOADS) },
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> HomeScreen(
                                    app = app,
                                    onSearch = { go(SEARCH) },
                                    onLibrary = { go(LIBRARY) },
                                    onOpen = open,
                                )
                            }
                        }
                    }
                }
            }
            composable(ESSENTIALS) {
                EssentialsScreen(
                    controls = app.controls,
                    onQueue = { go(QUEUE) },
                    onSleep = { go(SLEEP) },
                    onOpenContext = open,
                    onRadio = toPlayer,
                    onAddToPlaylist = addToPlaylist,
                )
            }
            composable("$ADD_TO_PLAYLIST?track={track}&title={title}") { entry ->
                AddToPlaylistScreen(
                    app = app,
                    trackUri = entry.arguments?.getString("track").orEmpty(),
                    trackTitle = entry.arguments?.getString("title").orEmpty(),
                    onDone = toPlayer,
                )
            }
            composable(QUEUE) { QueueScreen(app.controls, app.art, load = app::queue, onPlayed = toPlayer) }
            composable(OUTPUT) {
                val outputs by app.standalone.router.outputs.collectAsStateWithLifecycle()
                val mode by app.playback.mode.collectAsStateWithLifecycle()
                OutputScreen(
                    app.controls,
                    load = app.library::devices,
                    onChosen = { nav.popBackStack() },
                    // Headphones first, then the speaker if the listener allows it.
                    watchOutputs = outputs.sortedBy { it.kind != dev.pampa.fluidify.wear.standalone.LocalOutput.Kind.HEADPHONES }
                        .filter { it.kind == dev.pampa.fluidify.wear.standalone.LocalOutput.Kind.HEADPHONES || app.standalone.prefs.speakerAllowed },
                    watchActive = mode == dev.pampa.fluidify.wear.playback.PlaybackMode.WATCH,
                    watchConnectId = app.standalone.prefs.deviceId,
                    onWatchOutput = { output ->
                        app.playback.moveToWatch(output)
                        // Asked once, on a watch that has a radio at all; see CellularScreen.
                        val prefs = app.standalone.prefs
                        if (app.standalone.network.hasCellular && !prefs.cellularOffered) go(CELLULAR)
                    },
                    onConnectHeadphones = { app.standalone.router.openHeadphonePicker() },
                )
            }
            composable(SLEEP) { SleepScreen(app.controls, onSet = { nav.popBackStack() }) }
            composable(VOLUME) { VolumeScreen(app.controls, app.volume) }
            composable(LIBRARY) {
                LibraryScreen(
                    onSection = { section, title -> go(sectionRoute(section, title)) },
                    onWatchDownloads = { go(WATCH_DOWNLOADS) },
                )
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
            composable(WATCH_DOWNLOADS) {
                dev.pampa.fluidify.wear.ui.browse.WatchDownloadsScreen(app.downloads, onOpen = open)
            }
            composable(CELLULAR) {
                dev.pampa.fluidify.wear.ui.more.CellularScreen { yes ->
                    app.standalone.prefs.allowCellular = yes
                    app.standalone.prefs.cellularOffered = true
                    nav.popBackStack()
                }
            }
        }
        // Every command that fails says so, wherever the person is.
        var notice by remember { mutableStateOf<String?>(null) }
        val context = androidx.compose.ui.platform.LocalContext.current
        val haptics = dev.antigravity.fluidengine.ui.haptics.LocalFluidHaptics.current
        LaunchedEffect(app) {
            var hide: kotlinx.coroutines.Job? = null
            val window = dev.pampa.fluidify.wear.ui.common.NoticeWindow(NOTICE_MS)
            app.controls.errors.collect { code ->
                val message = context.getString(dev.pampa.fluidify.wear.ui.common.ErrorMessages.textFor(code))
                if (!window.accept(message, android.os.SystemClock.uptimeMillis())) return@collect
                hide?.cancel()
                notice = message
                haptics.play(dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent.Reject)
                hide = launch { kotlinx.coroutines.delay(NOTICE_MS); notice = null }
            }
        }
        dev.antigravity.fluidengine.wear.components.FluidWearToast(message = notice)
        LaunchedEffect(shown.snapshot?.track?.uri) {
            if (shown.snapshot?.track?.uri != unlikeUri) confirmUnlike = false
        }
        dev.pampa.fluidify.wear.ui.player.UnlikeDialog(
            visible = confirmUnlike,
            title = shown.snapshot?.track?.title,
            onConfirm = {
                confirmUnlike = false
                haptics.play(dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent.ToggleOff)
                if (app.controls.nowPlaying.value.snapshot?.track?.uri == unlikeUri) app.controls.setLiked(false)
            },
            onDismiss = { confirmUnlike = false },
        )
        val meter by app.glassMeter.visible.collectAsStateWithLifecycle()
        if (meter) GlassMeter()
        }
    }
    }
}

/** What the watch's own engine is doing, while the watch is the one playing and it is not simply playing. */
@Composable
private fun standaloneStatus(app: WearApp): String? {
    val mode by app.playback.mode.collectAsStateWithLifecycle()
    if (mode != dev.pampa.fluidify.wear.playback.PlaybackMode.WATCH) return null
    val moving by app.playback.moving.collectAsStateWithLifecycle()
    if (moving) return androidx.compose.ui.res.stringResource(dev.pampa.fluidify.wear.R.string.moving_to_watch)
    val status by app.standalone.engine.status.collectAsStateWithLifecycle()
    val id = when (status) {
        dev.pampa.fluidify.wear.standalone.EngineStatus.STARTING -> dev.pampa.fluidify.wear.R.string.engine_starting
        dev.pampa.fluidify.wear.standalone.EngineStatus.NEEDS_PHONE -> dev.pampa.fluidify.wear.R.string.engine_needs_phone
        dev.pampa.fluidify.wear.standalone.EngineStatus.SIGNED_OUT -> dev.pampa.fluidify.wear.R.string.engine_signed_out
        dev.pampa.fluidify.wear.standalone.EngineStatus.PREMIUM_REQUIRED -> dev.pampa.fluidify.wear.R.string.engine_premium
        dev.pampa.fluidify.wear.standalone.EngineStatus.FAILED -> dev.pampa.fluidify.wear.R.string.engine_failed
        else -> return null
    }
    return androidx.compose.ui.res.stringResource(id)
}

/**
 * Opens screens, once per tap: a quick second tap on a row (the transition still running) opened
 * the same screen twice, and it then had to be swiped away twice, each fetching its data again.
 * The same route asked for again within [DOUBLE_TAP_MS] is the same tap. Screens without
 * arguments are also single-top; a playlist opened from a playlist is a new screen.
 */
private class Navigator(private val nav: NavHostController) {
    private var last: String? = null
    private var lastAt = 0L

    fun go(route: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (route == last && now - lastAt < DOUBLE_TAP_MS) return
        last = route
        lastAt = now
        nav.navigate(route) { launchSingleTop = true }
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
private fun addToPlaylistRoute(track: String, title: String) = "$ADD_TO_PLAYLIST?track=${Uri.encode(track)}&title=${Uri.encode(title)}"

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
private const val CELLULAR = "cellular"
private const val WATCH_DOWNLOADS = "watch-downloads"
private const val ADD_TO_PLAYLIST = "add-to-playlist"

/** Two taps on the same thing closer than this are one. */
private const val DOUBLE_TAP_MS = 700L

/** How long a notice stays up. */
private const val NOTICE_MS = 2_600L

private const val PAGE_IMMERSIVE = 0
private const val PAGE_MAIN = 1
private const val PAGE_HOME = 2
