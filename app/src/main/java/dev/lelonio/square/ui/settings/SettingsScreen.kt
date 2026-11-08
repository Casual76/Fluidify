package dev.lelonio.square.ui.settings

import dev.antigravity.fluidengine.ui.fluid.fluidOverscrollContent
import dev.antigravity.fluidengine.ui.fluid.fluidOverscrollEdge
import dev.antigravity.fluidengine.ui.fluid.rememberFluidEdgeOverscroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidToggleRow
import dev.antigravity.fluidengine.ui.fluid.FluidChoiceRow
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.antigravity.fluidengine.ui.fluid.FluidCollapsingTitle
import dev.antigravity.fluidengine.ui.fluid.FluidCollapsingTopBar
import dev.antigravity.fluidengine.ui.fluid.fluidTitleCollapseOrigin
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidTitleCollapse
import dev.antigravity.fluidengine.ui.fluid.rememberFluidTitleSnapFling
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.LocalFluidHaptics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.fluidLicensesSection
import dev.antigravity.fluidengine.ui.fluid.fluidUpdateSection
import dev.antigravity.fluidengine.ui.fluid.release
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.update.LocalUpdateController
import dev.lelonio.square.ui.theme.rememberFluidifyUpdateStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.ArrowUpRight
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CaretUp
import dev.lelonio.square.BuildConfig
import dev.lelonio.square.R
import dev.lelonio.square.data.AppThemeMode
import dev.lelonio.square.data.AppLanguages
import dev.lelonio.square.data.CrossfadeSteps
import dev.lelonio.square.data.EffectQuality
import dev.lelonio.square.data.Quality
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.softShadow

/**
 * Everything that is configuration rather than listening.
 *
 * Reached from the avatar in the home header, not from the tab bar. The bar has
 * three places you go to play something and settings is not one of them — it is
 * somewhere you visit twice a year, and giving it a permanent quarter of the
 * navigation would say otherwise.
 *
 * Deliberately short. Most of what a music app usually puts here is decided by
 * the account or by the engine and is not ours to offer: bitrate comes from
 * Premium, the device name from the phone. What is left is the account itself,
 * the Web API application the user has to register for search, and the licences
 * this app owes attribution to.
 */
@Composable
fun SettingsScreen(
    state: MainViewModel.UiState,
    webApi: MainViewModel.WebApiState,
    contentPadding: PaddingValues,
    /**
     * The artwork wash on its own.
     *
     * Combined here with a recording of this page's own list, which is what the
     * docked title's bar refracts. It cannot be the app's page-wide recording:
     * that one contains this bar.
     */
    ground: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState,
    deviceName: String,
    onClientIdChange: (String) -> Unit,
    onConnectWebApi: () -> Unit,
    onDisconnectWebApi: () -> Unit,
    onLogOut: () -> Unit,
    onShowTutorial: () -> Unit,
    /** The chosen language tag, empty for the phone's own. */
    language: String,
    onLanguage: (String) -> Unit,
    /** Whether the phone's own music gets a shelf in the library. */
    showLocalFiles: Boolean,
    onShowLocalFiles: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenAirPods: (() -> Unit)? = null,
) {
    val ready = state as? MainViewModel.UiState.Ready
    val context = LocalContext.current
    val app = remember(context) {
        context.applicationContext as dev.lelonio.square.SquareApplication
    }
    /**
     * Which page is open, or null for the list of them.
     *
     * One screen with a page inside it rather than a second destination: every
     * page here reads the same state and the same stores, and threading all of
     * it through a navigation graph would buy nothing but the plumbing.
     * Remembered across a rotation, since coming back to the top of the
     * settings after turning the phone is not what anyone meant to do.
     */
    var open by rememberSaveable { mutableStateOf<SettingsPage?>(null) }

    // The watch-update notification asks for the Watch page (see WatchPageRequest): taken here,
    // whether the screen was already showing or has only now been reached.
    val watchPageRequested by WatchPageRequest.pending.collectAsStateWithLifecycle()
    LaunchedEffect(watchPageRequested) {
        if (watchPageRequested) {
            open = SettingsPage.Watch
            WatchPageRequest.consume()
        }
    }

    // Resolved here because fluidLicensesSection is a LazyListScope extension,
    // where composable calls like stringResource are out of reach.
    val update = LocalUpdateController.current
    val updateStrings = rememberFluidifyUpdateStrings()
    val updateChannelNote = stringResource(R.string.update_channel_note)
    val chooseUpdateChannel = rememberChooseUpdateChannel()
    val updateContext = LocalContext.current
    val updateApp = remember(updateContext) { updateContext.applicationContext as SquareApplication }
    val prefs = updateApp.preferences
    val updateChannel by prefs.updateChannel.collectAsStateWithLifecycle()
    val engineLicencesTitle = stringResource(R.string.engine_licences_title)
    val engineLicencesFootnote = stringResource(
        R.string.engine_licences_footnote,
        dev.antigravity.fluidengine.foundation.EngineBuild.VERSION,
    )

    // The back gesture closes the page first and leaves the settings second,
    // which is the order the screen is read in. The pages are the engine's
    // (3.0): a page comes in from the side over the list of them, and the back
    // holds it under the finger. The hand-made preview that scaled the whole
    // page -- glass bar included, which glass must never be -- is gone with it.
    val stack = remember(open) { listOf(RootPageKey) + listOfNotNull(open?.name) }
    val openPage: (SettingsPage) -> Unit = { open = it }
    val onClosePage: () -> Unit = { open = null }
    dev.antigravity.fluidengine.ui.page.FluidPageHost(
        stack = stack,
        onBack = { open = null },
        modifier = Modifier.fillMaxSize(),
        // The artwork wash behind the whole app shows through every page,
        // as it does through the settings themselves.
        pageColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { key ->
    @Suppress("NAME_SHADOWING")
    val open = SettingsPage.entries.firstOrNull { it.name == key }

    // The elastic edge every page in the family has; see the engine's
    // FluidOverscroll. Two modifiers on purpose: only the content travels.
    val overscroll = rememberFluidEdgeOverscroll()

    // The heading belongs to the page, not to the window.
    //
    // It used to be a row inside the list that simply scrolled away and left
    // nothing behind, so a page scrolled down had no name at all. Now it is the
    // list's first item and the bar takes it over as it goes: one piece of type
    // travelling from one home to the other, which is the engine's own handover
    // rather than an imitation of it.
    val listState = rememberLazyListState()
    val title = stringResource(open?.title ?: R.string.settings)
    val collapse = rememberFluidTitleCollapse(title, listState)
    // What this page's own body looks like, recorded on its own. Combined with
    // the ground it makes an opaque image with no chrome in it, which is the
    // one thing the bar above is allowed to blur.
    val bodyGlass = dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop()
    val barBackdrop = dev.antigravity.fluidengine.ui.fluid.rememberCombinedGlassBackdrop(
        ground,
        bodyGlass,
    )

    Box(
        Modifier
            .fillMaxSize()
            .fluidTitleCollapseOrigin(collapse),
    ) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .fluidOverscrollEdge(overscroll)
            .glassBackdropSource(bodyGlass)
            .fluidOverscrollContent(overscroll),
        state = listState,
        flingBehavior = rememberFluidTitleSnapFling(collapse),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults.topBarHeight(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        overscrollEffect = null,
    ) {
        item("top") {
            // The title spans the page; the 20 dp on the list's sides are for the groups.
            Box(Modifier.bleedHorizontal(20.dp)) { FluidCollapsingTitle(title, collapse) }
        }

        if (open == null) item("pages") {
            Section(null) {
                if (onOpenAirPods != null) {
                    PageRow(stringResource(R.string.airpods_feature_title), stringResource(R.string.airpods_feature_quick_title), onOpenAirPods)
                    FluidListDivider()
                }
                SettingsPage.entries.forEachIndexed { index, page ->
                    if (index > 0) FluidListDivider()
                    PageRow(stringResource(page.title), stringResource(page.summary)) {
                        openPage(page)
                    }
                }
            }
        }

        if (open == SettingsPage.Account) item("account") {
            Section(stringResource(R.string.account)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(
                        url = ready?.avatarUrl,
                        title = ready?.displayName.orEmpty(),
                        modifier = Modifier
                            .size(54.dp)
                            .softShadow(CircleShape, elevation = 10.dp),
                        corner = 27.dp,
                        decodeSize = 54.dp,
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 14.dp),
                    ) {
                        Text(
                            ready?.displayName ?: stringResource(R.string.not_connected),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (ready != null) {
                                pluralStringResource(R.plurals.playlist_count, ready.playlists.size, ready.playlists.size)
                            } else {
                                stringResource(R.string.log_in_to_resume)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                }

                FluidListDivider()
                InfoRow(stringResource(R.string.connect_device), deviceName)
            }
        }

        if (open == SettingsPage.Account) item("listening-history") {
            ListeningHistorySection(app)
        }

        if (open == SettingsPage.Account) item("webapi") {
            Section(stringResource(R.string.web_api)) {
                if (webApi.connected) {
                    InfoRow(stringResource(R.string.application), stringResource(R.string.connected))
                    FluidListDivider()
                    InfoRow(
                        stringResource(R.string.client_id),
                        webApi.clientId.take(8) + if (webApi.clientId.length > 8) "…" else "",
                    )
                    // Only when there is something to gain by it. Offering a
                    // sign-in to someone whose application already has every
                    // permission is an invitation to break what works.
                    if (webApi.incomplete) {
                        FluidListDivider()
                        ActionRow(
                            stringResource(R.string.web_api_regrant),
                            destructive = false,
                            onClick = onConnectWebApi,
                        )
                        Text(
                            stringResource(R.string.web_api_regrant_why),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                            modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 12.dp),
                        )
                    }
                    FluidListDivider()
                    ActionRow(stringResource(R.string.disconnect), destructive = true, onClick = onDisconnectWebApi)
                } else {
                    // The full explanation, because with no application
                    // connected search does not work at all and the reason is
                    // not something anyone would guess.
                    WebApiSetup(webApi, onClientIdChange, onConnectWebApi)
                }
            }
        }

        if (open == SettingsPage.Account) item("tutorial") {
            Section(stringResource(R.string.guide)) {
                ActionRow(stringResource(R.string.see_setup_again), destructive = false) {
                    onShowTutorial()
                }
            }
        }

        if (open == SettingsPage.Playback) item("quality") {
            QualitySection()

            DownloadsSection()
        }

        // Also librespot's: the crossfade is mixed by the engine's own player.
        if (open == SettingsPage.Playback) item("crossfade") {
            CrossfadeSection()
        }

        // Under Playback and not in the player, which is where the app this fork
        // comes from put it. That player has a panel of things that change how it
        // sounds and this fits at the end of it; ours does not, and inventing one
        // for a single row would have been a panel with one thing in it. What
        // matters is that it is set before sleep and that it survives the screen
        // going off, and both of those are true wherever the row lives.
        if (open == SettingsPage.Playback) item("sleep") {
            SleepTimerSection()
        }

        if (open == SettingsPage.Playback) item("canvas") {
            CanvasSection()
        }

        // The effects run on our own output, so this one holds for both backends.
        if (open == SettingsPage.Playback) item("effect-quality") {
            EffectQualitySection()
        }

        // First under App, and deliberately above the glass: this is the one
        // choice that changes every screen at once, and the glass numbers below
        // it are read against whichever side it has chosen.
        if (open == SettingsPage.App) item("theme") {
            ThemeSection()
        }

        // How the app looks and what that costs, so under the app rather than
        // under playback: nothing here touches a note of audio.
        if (open == SettingsPage.App) item("glass") {
            GlassSection()

        }

        // The light that follows the music, in the player and the mini player. Under the app
        // rather than under playback: it is something the screen does, and nothing in the
        // audio changes. Its section is named for where the light is (the player), because
        // the row inside is named for what it is, and one string in both places reads as a
        // heading stuck on a row.
        if (open == SettingsPage.App) item("audio-light") {
            val context = LocalContext.current
            val prefs = (context.applicationContext as dev.lelonio.square.SquareApplication).audioLightPreferences
            val enabled by prefs.enabled.collectAsStateWithLifecycle()
            Section(stringResource(R.string.audio_light_section)) {
                FluidToggleRow(title = stringResource(R.string.audio_light_title), subtitle = stringResource(R.string.audio_light_note),
                    checked = enabled, onCheckedChange = prefs::setEnabled)
            }
        }
        if (open == SettingsPage.App) item("widget") {
            WidgetSection()
        }

        // Under the app rather than under playback for the same reason: this
        // adds a shelf to the library, it does not change what anything sounds
        // like.
        if (open == SettingsPage.App) item("local-files") {
            Section(stringResource(R.string.local_files)) {
                FluidToggleRow(
                    title = stringResource(R.string.show_local_files),
                    subtitle = stringResource(R.string.show_local_files_note),
                    checked = showLocalFiles,
                    onCheckedChange = onShowLocalFiles,
                )
            }
        }

        if (open == SettingsPage.App) item("language") {
            Section(stringResource(R.string.language)) {
                AppLanguages.forEachIndexed { index, (tag, name) ->
                    if (index > 0) FluidListDivider()
                    ChoiceRow(
                        label = name.ifEmpty { stringResource(R.string.system_language) },
                        selected = tag == language,
                    ) { onLanguage(tag) }
                }
            }
        }

        if (open == SettingsPage.App && android.os.Build.VERSION.SDK_INT >= 31) {
            item("links") {
                Section(stringResource(R.string.spotify_links)) {
                    Text(
                        stringResource(R.string.spotify_links_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                    FluidListDivider()
                    ActionRow(
                        stringResource(R.string.open_by_default),
                        destructive = false,
                    ) {
                        // Android's own screen, because this is Android's own
                        // decision: an app cannot claim a domain it does not
                        // own, and the listener granting it here is the whole
                        // point of the design.
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings
                                        .ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    }
                }
            }
        }

        if (open == SettingsPage.Watch) item("watch") {
            WatchSection()
        }

        if (open == SettingsPage.About) item("permissions") {
            Section(stringResource(R.string.permissions_asked)) {
                Text(
                    stringResource(R.string.permissions_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
                SCOPES.forEach { (scope, why) ->
                    FluidListDivider()
                    InfoRow(scope, stringResource(why))
                }
            }
        }

        if (open == SettingsPage.About) item("author") {
            Section(stringResource(R.string.forked_by)) {
                CreditRow(name = GITHUB_USER, url = GITHUB_URL, avatarUrl = "$GITHUB_URL.png")
            }
        }

        // The app this one is forked from, with its author's face on it: the
        // fork changes the skin and the engine room, but the base is Square
        // and the credit for that stays with Lelonio.
        if (open == SettingsPage.About) item("upstream") {
            Section(stringResource(R.string.based_on)) {
                CreditRow(
                    name = "$UPSTREAM_NAME · $UPSTREAM_USER",
                    url = UPSTREAM_URL,
                    avatarUrl = "https://github.com/$UPSTREAM_USER.png",
                )
            }
        }

        if (open == SettingsPage.About) item("about") {
            Section(stringResource(R.string.about)) {
                InfoRow(stringResource(R.string.version), "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                FluidListDivider()
                Licences()
            }
        }

        // Internal probe entry only; there is no exported diagnostic activity or release UI.
        if (BuildConfig.DEBUG && open == SettingsPage.About) item("airpods_diagnostic") {
            Section("Debug") {
                ActionRow("Diagnostica AirPods (POC)", destructive = false) {
                    context.startActivity(
                        android.content.Intent().setClassName(
                            context,
                            "dev.lelonio.square.airpods.AirPodsDiagnosticActivity",
                        ),
                    )
                }
            }
        }

        // The engine's update section: what is installed, whether there is something newer, the
        // channel to follow (the same setting as on the Watch page) and the way to install it.
        if (open == SettingsPage.About) {
            fluidUpdateSection(
                state = update.state,
                onCheck = update::check,
                onInstall = update::install,
                lastCheckedAtMillis = update.lastCheckedAtMillis,
                channel = updateChannel,
                onChannelChange = chooseUpdateChannel,
                onSkip = { update.state.release?.version?.let { prefs.setSkippedUpdate(it) } },
                strings = updateStrings,
                footnote = updateChannelNote,
            )
        }

        // The engine's own third-party credits. The Apache-2.0 notice for the
        // glass has to travel with the APK, not sit in a Markdown in the repo.
        if (open == SettingsPage.About) {
            fluidLicensesSection(
                title = engineLicencesTitle,
                footnote = engineLicencesFootnote,
            )
        }

        if (ready != null && open == SettingsPage.Account) {
            item("logout") {
                Section(null) {
                    ActionRow(stringResource(R.string.log_out), destructive = true, onClick = onLogOut)
                }
            }
        }
    }

        // Last, so it stands over the list rather than under it. Clear until
        // the heading reaches it: a bar that is already glass at the top of a
        // page is a bar the page has no reason to have.
        FluidCollapsingTopBar(
            title = title,
            collapse = collapse,
            backdrop = barBackdrop,
            // A page standing on a blurred cover, so on the dark side the
            // family's own film — which lightens by design — would make the
            // bar the brightest thing on the screen. See pageBarTint, which
            // holds the other side of that argument.
            barTint = dev.lelonio.square.ui.theme.pageBarTint(),
            modifier = Modifier.align(Alignment.TopCenter),
            onBack = { if (open != null) onClosePage() else onBack() },
            contentTranslation = { overscroll.offsetPx },
        )
    }
    }
}

/** Lets a child use [by] more on each side than its parent gives: the list pads its groups, not its title. */
private fun Modifier.bleedHorizontal(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val width = constraints.maxWidth + 2 * extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

/** The list of pages, under the pages themselves in the settings' own stack. */
private const val RootPageKey = "root"

/**
 * The pages the settings are divided into.
 *
 * Four, and no deeper: a page that holds one row is a row that was hidden, and
 * a tree that goes further than this is somewhere to lose things in.
 *
 * The order is how often they are wanted. The account is what a first launch
 * comes here for, playback is what a settled install comes back for, and the
 * rest is read once.
 */
private enum class SettingsPage(
    @StringRes val title: Int,
    /** One line under the name, so a page can be chosen without opening it. */
    @StringRes val summary: Int,
) {
    Account(R.string.account, R.string.page_account_summary),
    Playback(R.string.page_playback, R.string.page_playback_summary),
    App(R.string.page_app, R.string.page_app_summary),
    Watch(R.string.page_watch, R.string.page_watch_summary),
    About(R.string.about, R.string.page_about_summary),
}

/** A row that opens a page: the engine's own row, with its chevron. */
@Composable
private fun PageRow(title: String, summary: String, onClick: () -> Unit) {
    FluidListRow(
        title = title,
        subtitle = summary,
        onClick = onClick,
        subtitleMaxLines = 1,
    )
}

/**
 * Which file the engine asks Spotify for.
 *
 * The three fixed steps are the ones the account is offered, and there is no
 * fourth: this client is served Ogg Vorbis at 320 kbps and below, never a
 * lossless file, so a "lossless" row would be a promise nothing can keep.
 */
/**
 * Whether the player shows a track's Canvas.
 *
 * Under playback rather than under the app's appearance: a Canvas is a piece
 * of the record, fetched over the network and decoded like audio, not a
 * decoration this app draws.
 */
@Composable
private fun CanvasSection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).preferences
    }
    val enabled by store.canvasEnabled.collectAsStateWithLifecycle()

    Section(stringResource(R.string.canvas)) {
        FluidToggleRow(
            title = stringResource(R.string.canvas_show),
            checked = enabled,
            onCheckedChange = store::setCanvasEnabled,
        )
        FluidListDivider()
        Text(
            stringResource(R.string.canvas_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun EffectQualitySection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).effectQuality
    }
    val chosen by store.quality.collectAsStateWithLifecycle()

    Section(stringResource(R.string.effect_quality)) {
        EffectQuality.entries.forEachIndexed { index, quality ->
            if (index > 0) FluidListDivider()
            ChoiceRow(
                label = stringResource(quality.label),
                selected = quality == chosen,
            ) { store.set(quality) }
        }
        FluidListDivider()
        Text(
            stringResource(chosen.note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/**
 * Light, dark, or the phone's own answer.
 *
 * Three rows rather than a switch, because "follow the system" is a real third
 * answer and not the absence of the other two — and it is the one most people
 * want, so it goes first and is the default.
 */
@Composable
private fun ThemeSection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).preferences
    }
    val chosen by store.themeMode.collectAsStateWithLifecycle()

    Section(stringResource(R.string.theme)) {
        AppThemeMode.entries.forEachIndexed { index, mode ->
            if (index > 0) FluidListDivider()
            ChoiceRow(
                label = stringResource(
                    when (mode) {
                        AppThemeMode.System -> R.string.theme_system
                        AppThemeMode.Light -> R.string.theme_light
                        AppThemeMode.Dark -> R.string.theme_dark
                        AppThemeMode.Amoled -> R.string.theme_amoled
                    },
                ),
                selected = mode == chosen,
            ) { store.setThemeMode(mode) }
        }
        FluidListDivider()
        Text(
            stringResource(R.string.theme_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/**
 * When the music is to stop on its own.
 *
 * The rows say what was asked for; the line under them says what is left, and
 * they are not the same question — a lit row is a length, and a countdown is a
 * clock. What it sets lives in the playback service, which outlives this screen:
 * see [dev.lelonio.square.playback.SleepTimer].
 */
@Composable
private fun SleepTimerSection() {
    val endsAt by dev.lelonio.square.playback.SleepTimer.endsAt.collectAsStateWithLifecycle()
    val chosen by dev.lelonio.square.playback.SleepTimer.minutes.collectAsStateWithLifecycle()
    val atTrackEnd by
        dev.lelonio.square.playback.SleepTimer.atTrackEnd.collectAsStateWithLifecycle()

    // Read once a second while something is running, and not at all otherwise: a
    // clock nobody set should cost nothing.
    var left by remember { mutableStateOf(dev.lelonio.square.playback.SleepTimer.remaining()) }
    LaunchedEffect(endsAt) {
        while (endsAt != null) {
            left = dev.lelonio.square.playback.SleepTimer.remaining()
            kotlinx.coroutines.delay(1_000)
        }
        left = null
    }

    Section(stringResource(R.string.sleep_timer)) {
        ChoiceRow(
            label = stringResource(R.string.off),
            selected = endsAt == null && !atTrackEnd,
        ) { dev.lelonio.square.playback.SleepTimer.cancel() }
        SleepLengths.forEach { minutes ->
            FluidListDivider()
            ChoiceRow(
                label = stringResource(R.string.sleep_timer_minutes, minutes),
                selected = chosen == minutes,
            ) { dev.lelonio.square.playback.SleepTimer.inMinutes(minutes) }
        }
        FluidListDivider()
        ChoiceRow(
            label = stringResource(R.string.sleep_timer_track_end),
            selected = atTrackEnd,
        ) { dev.lelonio.square.playback.SleepTimer.atEndOfTrack() }
        FluidListDivider()
        val running = left
        Text(
            when {
                atTrackEnd -> stringResource(R.string.sleep_timer_track_end_active)
                running != null -> stringResource(
                    R.string.sleep_timer_left,
                    dev.lelonio.square.ui.library.formatDuration(running),
                )
                else -> stringResource(R.string.sleep_timer_note)
            },
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/** The lengths offered, which are the ones every player offers. */
private val SleepLengths = listOf(5, 15, 30, 45, 60)

/**
 * A way to put the widget on the home screen from inside the app.
 *
 * The long press on an empty part of a home screen, then Widgets, then finding the app in an
 * alphabetical list of every app that has one — that is the path, and most people never walk it. One
 * row here and the launcher asks the question itself.
 *
 * Only where the launcher says it will honour the request. Below Android 8 there is no such request
 * at all, and above it plenty of launchers decline, so the row is only drawn when there is something
 * behind it: a row that does nothing is worse than no row.
 */
@Composable
private fun WidgetSection() {
    val context = LocalContext.current
    val manager = remember(context) { AppWidgetManager.getInstance(context) }
    val supported = remember(manager) {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            runCatching { manager.isRequestPinAppWidgetSupported }.getOrDefault(false)
    }
    if (!supported) return

    Section(stringResource(R.string.widget_label)) {
        ActionRow(stringResource(R.string.widget_add_row), destructive = false) {
            runCatching {
                manager.requestPinAppWidget(
                    ComponentName(
                        context,
                        dev.lelonio.square.widget.media.NowPlayingWidget::class.java,
                    ),
                    null,
                    null,
                )
            }
        }
        FluidListDivider()
        Text(
            stringResource(R.string.widget_add_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun CrossfadeSection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).crossfade
    }
    val chosen by store.seconds.collectAsStateWithLifecycle()

    Section(stringResource(R.string.crossfade)) {
        CrossfadeSteps.forEachIndexed { index, seconds ->
            if (index > 0) FluidListDivider()
            ChoiceRow(
                label = if (seconds == 0) {
                    stringResource(R.string.crossfade_off)
                } else {
                    pluralStringResource(R.plurals.crossfade_seconds, seconds, seconds)
                },
                selected = seconds == chosen,
            ) { store.set(seconds) }
        }
        FluidListDivider()
        Text(
            stringResource(R.string.crossfade_note) + " " +
                stringResource(R.string.quality_restarts),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun QualitySection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).quality
    }
    val chosen by store.quality.collectAsStateWithLifecycle()

    Section(stringResource(R.string.quality)) {
        Quality.entries.forEachIndexed { index, quality ->
            if (index > 0) FluidListDivider()
            ChoiceRow(
                label = stringResource(quality.label),
                selected = quality == chosen,
            ) { store.set(quality) }
        }
        // What automatic is deciding, and from what. A setting that answers
        // "it depends" should say what it depends on.
        if (chosen == Quality.Auto) {
            FluidListDivider()
            val link = store.linkKbps()
            InfoRow(
                stringResource(R.string.quality_link),
                if (link > 0) {
                    stringResource(R.string.quality_link_value, link, store.automatic())
                } else {
                    stringResource(R.string.quality_link_unknown, store.automatic())
                },
            )
        }
        FluidListDivider()
        Text(
            stringResource(R.string.quality_auto_note) + " " +
                stringResource(R.string.quality_restarts),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/**
 * Checking for, and installing, a new version.
 *
 * A button rather than something that happens on its own: the check is a
 * request to GitHub carrying the user's address, made for the app's benefit
 * rather than theirs, and nothing here needs it badly enough to make it
 * automatic.
 */
/**
 * Everything about music kept on the phone.
 *
 * Sits under the streaming quality because the two are read together and are
 * deliberately different: that one follows the connection minute by minute,
 * this one is chosen once and lives on the phone until it is deleted.
 */
@Composable
private fun DownloadsSection() {
    val context = LocalContext.current
    val app = remember(context) {
        context.applicationContext as dev.lelonio.square.SquareApplication
    }
    val store = app.downloads
    val settings = app.downloadSettings

    val files by store.files.collectAsStateWithLifecycle()
    val failures by store.failures.collectAsStateWithLifecycle()
    val quality by settings.quality.collectAsStateWithLifecycle()
    val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle()
    val likedSongs by settings.downloadLikedSongs.collectAsStateWithLifecycle()
    val offline by settings.offlineMode.collectAsStateWithLifecycle()

    var confirmingClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Section(stringResource(R.string.downloads)) {
        // The audio plus everything kept beside it. Counted rather than
        // summed from the index, because the Canvases are video and a library
        // of them is not a rounding error next to the music.
        val extrasBytes by androidx.compose.runtime.produceState(0L, files.size) {
            value = withContext(Dispatchers.IO) {
                dev.lelonio.square.download.DownloadExtras.bytes()
            }
        }
        InfoRow(
            stringResource(R.string.download_storage),
            stringResource(
                R.string.download_storage_used,
                android.text.format.Formatter.formatShortFileSize(
                    context,
                    files.values.sumOf { it.bytes } + extrasBytes,
                ),
            ),
        )
        FluidListDivider()
        InfoRow(stringResource(R.string.downloaded_tracks), files.size.toString())

        FluidListDivider()
        Text(
            stringResource(R.string.download_quality),
            style = MaterialTheme.typography.labelLarge,
            color = InkDim,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp),
        )
        dev.lelonio.square.data.DownloadQuality.entries.forEach { entry ->
            ChoiceRow(
                label = stringResource(entry.label),
                selected = entry == quality,
            ) { settings.setQuality(entry) }
        }
        Text(
            stringResource(R.string.download_quality_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )

        FluidListDivider()
        FluidToggleRow(
            title = stringResource(R.string.download_wifi_only),
            subtitle = stringResource(R.string.download_wifi_only_note),
            checked = wifiOnly,
            onCheckedChange = settings::setWifiOnly,
        )

        FluidListDivider()
        FluidToggleRow(
            title = stringResource(R.string.download_liked_songs),
            subtitle = stringResource(R.string.download_liked_songs_note),
            checked = likedSongs,
            onCheckedChange = settings::setDownloadLikedSongs,
        )

        FluidListDivider()
        FluidToggleRow(
            title = stringResource(R.string.offline_mode),
            subtitle = stringResource(R.string.offline_mode_note),
            checked = offline,
            onCheckedChange = settings::setOfflineMode,
        )

        // Only when there is something to say. A row reporting zero failures is
        // a row about nothing, on a screen that is already long.
        val givenUp = failures.count { it.value.attempts >= dev.lelonio.square.data.DownloadStore.MAX_ATTEMPTS }
        if (givenUp > 0) {
            FluidListDivider()
            InfoRow(
                stringResource(R.string.downloads),
                pluralStringResource(R.plurals.download_failed_count, givenUp, givenUp),
            )
            ActionRow(stringResource(R.string.download_retry_failed), destructive = false) {
                scope.launch {
                    store.retryFailed()
                    dev.lelonio.square.download.DownloadService.start(context)
                }
            }
        }

        if (files.isNotEmpty()) {
            FluidListDivider()
            ActionRow(stringResource(R.string.remove_all_downloads), destructive = true) {
                confirmingClear = true
            }
        }
    }

    if (confirmingClear) {
        dev.antigravity.fluidengine.ui.fluid.FluidAlert(
            onDismissRequest = { confirmingClear = false },
            title = stringResource(R.string.remove_all_downloads),
            message = stringResource(R.string.remove_all_downloads_confirm),
            actions = listOf(
                dev.antigravity.fluidengine.ui.fluid.FluidAlertAction(
                    stringResource(R.string.cancel),
                    { confirmingClear = false },
                ),
                dev.antigravity.fluidengine.ui.fluid.FluidAlertAction(
                    stringResource(R.string.remove_all_downloads),
                    {
                        confirmingClear = false
                        scope.launch { store.clearAll() }
                    },
                    dev.antigravity.fluidengine.ui.fluid.FluidAlertAction.Emphasis.Destructive,
                ),
            ),
        )
    }

}

/**
 * The third-party code this app ships, and under what.
 *
 * Not a nicety: Bungee is MPL-2.0 and librespot is MIT, and both licences
 * require the notice to travel with the binary. The glass (AndroidLiquidGlass)
 * and the buttons and pages (LibrePods, GPL-3.0) come with the engine, and the
 * engine's own section below lists them. Collapsed by
 * default because it is an obligation to the authors, not a feature.
 */
@Composable
private fun Licences() {
    var open by remember { mutableStateOf(false) }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.licences), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                if (open) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown,
                contentDescription = null,
                tint = InkDim,
                modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            LICENCES.forEach { (what, licence) ->
                Text(
                    "$what — $licence",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * A titled group of rows: the engine's section header over the engine's group.
 *
 * A plain panel rather than glass (`glass = true`): the groups were a soft grey film before the
 * engine, and on a light page glass reads fainter than that. The rows inside are the engine's
 * (`FluidToggleRow`, `FluidChoiceRow`, `FluidListRow`), so a switch can be slid by its thumb.
 */
@Composable
internal fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        if (title != null) {
            FluidSectionHeader(
                // The reader's locale, not Locale.ROOT's: in Turkish a lowercase "i" capitalises
                // to a dotted "I", and the header's own uppercase() spells every heading wrongly.
                // Capitalised here, the header's second pass has nothing left to change.
                title = title.uppercase(LocalConfiguration.current.locales[0]),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        FluidListGroup(content = content)
    }
}

/**
 * A person or project with a face, a name and a link — the shape both credit
 * blocks share, so the fork's author and the upstream app read as peers.
 */
@Composable
private fun CreditRow(name: String, url: String, avatarUrl: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { uriHandler.openUri(url) }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // GitHub serves the account picture at `<user>.png`, so the avatar
        // follows whatever it is set to rather than being a copy checked in
        // here.
        Artwork(
            url = avatarUrl,
            title = name,
            modifier = Modifier
                .size(54.dp)
                .softShadow(CircleShape, elevation = 10.dp),
            corner = 27.dp,
            decodeSize = 54.dp,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                url.removePrefix("https://"),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
            )
        }
        Icon(
            PhosphorIcons.Regular.ArrowUpRight,
            contentDescription = null,
            tint = InkDim,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A label and its value, side by side. The engine has no such row: it is the one the app keeps. */
@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = InkDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A row that does something. Destructive ones read in the error colour. */
@Composable
internal fun ActionRow(label: String, destructive: Boolean, onClick: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    FluidListRow(
        title = if (destructive) {
            AnnotatedString(label, SpanStyle(color = error))
        } else {
            AnnotatedString(label)
        },
        subtitle = "",
        onClick = onClick,
        disclosure = false,
    )
}

/** One of a list where one is picked: the engine's choice row, a tick on the one that is. */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    FluidChoiceRow(title = label, selected = selected, onSelect = onClick)
}

/** Kept next to the request that asks for them; see MainViewModel.connectWebApi. */
private val SCOPES = listOf(
    "user-top-read" to R.string.scope_top_artists,
    "user-read-recently-played" to R.string.scope_history,
    "user-read-playback-state" to R.string.scope_devices,
    "user-modify-playback-state" to R.string.scope_transfer,
    "playlist-modify-private" to R.string.scope_private_playlists,
    "playlist-modify-public" to R.string.scope_public_playlists,
)

private val LICENCES = listOf(
    "librespot" to "MIT",
    "Bungee" to "MPL-2.0",
    "Phosphor Icons" to "MIT",
    "Coil" to "Apache-2.0",
    "OkHttp / Retrofit" to "Apache-2.0",
)

private const val GITHUB_USER = "Casual76"
private const val GITHUB_URL = "https://github.com/Casual76"

/** The app this one is forked from, credited in full on the About page. */
private const val UPSTREAM_NAME = "Square"
private const val UPSTREAM_USER = "Lelonio"
private const val UPSTREAM_URL = "https://github.com/Lelonio/Square"



/** Account controls share the existing grouped settings and confirmation material. */
@Composable
private fun ListeningHistorySection(app: dev.lelonio.square.SquareApplication) {
    val history = app.listeningHistory
    val session by history.session.collectAsStateWithLifecycle()
    val access = session.access
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<dev.lelonio.square.data.HistoryAccess?>(null) }
    var importing by remember { mutableStateOf(false) }
    var status by remember(access) { mutableStateOf<String?>(null) }
    val observed = stringResource(R.string.history_import_observed)
    val empty = stringResource(R.string.history_import_empty)
    val failed = stringResource(R.string.history_import_error)
    val partial = stringResource(R.string.history_import_partial)
    Section(stringResource(R.string.history_title)) {
        FluidToggleRow(
            title = stringResource(R.string.history_record),
            subtitle = stringResource(R.string.history_retention),
            checked = session.recording && access != null,
            onCheckedChange = { if (access != null) history.setRecording(it) },
        )
        FluidListDivider()
        if (access == null) {
            InfoRow(stringResource(R.string.history_account), stringResource(R.string.not_connected))
        } else {
            InfoRow(stringResource(R.string.history_account), access.account.id)
            FluidListDivider()
            ActionRow(stringResource(if (importing) R.string.history_importing else R.string.history_import), false) {
                if (!importing) scope.launch {
                    importing = true
                    try {
                        val result = dev.lelonio.square.data.SpotifyHistoryImport.authenticated(history, app.webApi, access).refresh(access)
                        status = when (result.status) {
                            "error" -> failed
                            "empty" -> empty
                            "partial" -> partial
                            else -> if (result.truncated) partial else observed
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { status = failed }
                    finally { importing = false }
                }
            }
            status?.let { InfoRow(stringResource(R.string.history_import_result), it) }
            FluidListDivider()
            ActionRow(stringResource(R.string.history_delete), true) { confirming = access }
        }
    }
    val deleting = confirming
    if (deleting != null) dev.antigravity.fluidengine.ui.fluid.FluidAlert(
        onDismissRequest = { confirming = null },
        title = stringResource(R.string.history_delete),
        message = stringResource(R.string.history_delete_confirm, deleting.account.id),
        actions = listOf(
            dev.antigravity.fluidengine.ui.fluid.FluidAlertAction(stringResource(R.string.cancel), { confirming = null }),
            dev.antigravity.fluidengine.ui.fluid.FluidAlertAction(stringResource(R.string.history_delete), {
                confirming = null
                scope.launch { runCatching { history.deleteAccount(deleting) } }
            }, dev.antigravity.fluidengine.ui.fluid.FluidAlertAction.Emphasis.Destructive),
        ),
    )
}
