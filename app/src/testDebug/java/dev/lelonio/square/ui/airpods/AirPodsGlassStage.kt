// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.LocalGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.lelonio.square.R
import dev.lelonio.square.airpods.AacpStage
import dev.lelonio.square.airpods.AirPodsIdentity
import dev.lelonio.square.airpods.AirPodsState
import dev.lelonio.square.airpods.BatteryValue
import dev.lelonio.square.airpods.CommandOutcome
import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.ListeningMode
import dev.lelonio.square.airpods.PendingCommand
import dev.lelonio.square.airpods.Received
import dev.lelonio.square.airpods.protocol.PropertyValue
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import dev.lelonio.square.airpods.runtime.storage.PairSettings
import dev.lelonio.square.airpods.runtime.storage.PairVerification
import dev.lelonio.square.airpods.runtime.storage.QuickMode
import dev.lelonio.square.ui.navigation.FluidifyAmbientSource
import dev.lelonio.square.ui.navigation.FluidifyPageHost
import dev.lelonio.square.ui.navigation.LocalFluidifyPageAmbient
import dev.lelonio.square.ui.navigation.rememberFluidifyPageAmbient
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.pageFloor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Invented AirPods state for looking at the three surfaces on a device. Debug builds only.
 *
 * Nothing here comes from a radio, a store or the runtime owner, and nothing is sent anywhere.
 * The product gate is ON in most of these states because the point is to see the surfaces an
 * enabled product would show; that is a property of the fixture, said on screen by the stage's
 * DEMO notice, and not a statement about the product. [gateOff] is the state the shipped product
 * is actually in.
 */
internal object AirPodsDemo {
    const val NOW = 600_000L
    private const val GENERATION = 7L

    private fun <T> rx(value: T, ageMs: Long = 1_000) = Received(value, NOW - ageMs, GENERATION)
    private fun number(value: Int) = rx<PropertyValue>(PropertyValue.Number(value))

    // A3063 is AirPods Pro 3 in the protocol table: the stage shows that model's pictures.
    private val identity = AirPodsIdentity("A3063", "Apple Inc.", "DEMO", name = "DEMO AirPods")

    /** Every key the surfaces ask about, so no control falls back to "unavailable" by omission. */
    fun availability(enabled: Boolean, reason: String? = null,
        vararg overrides: Pair<String, UiAvailability>): Map<String, UiAvailability> {
        val keys = SetupAction.entries.map { "setup.$it" } +
            LocalToggle.entries.flatMap { listOf("local.$it", "status.$it") } +
            CommandProperty.entries.map { "property.$it" } +
            GestureSide.entries.map { "gesture.$it" } +
            listOf("local.preferredMode", "host.volume", "host.ca_volume", "tracking.start", "tracking.stop")
        return keys.associateWith { UiAvailability(enabled, if (enabled) null else reason) } + overrides
    }

    private fun done(vararg actions: SetupAction) =
        actions.map { "setup.$it" to UiAvailability(false, "DEMO · già fatto in questo stato") }.toTypedArray()

    /** Set up, connected, subscribed: one answer outstanding and one request that timed out. */
    val connected = AirPodsUiState(
        domain = AirPodsState(
            generation = GENERATION, stage = AacpStage.SUBSCRIBED,
            identityReceived = rx(identity), mode = rx(ListeningMode.ANC),
            properties = mapOf(
                CommandProperty.EQ to rx<PropertyValue>(PropertyValue.Eq(true, 40, 50, 60)),
                CommandProperty.MIC_MODE to number(0),
                CommandProperty.CONVERSATION to number(1),
                CommandProperty.ADAPTIVE_VOLUME_CONFIG to number(2),
                CommandProperty.AUTO_ANC_STRENGTH to number(35),
                CommandProperty.CHIME_VOLUME to number(60),
                CommandProperty.LISTENING_MODE_CONFIGS to number(14),
                CommandProperty.STEM_CONFIG to number(8),
                CommandProperty.DOUBLE_CLICK_INTERVAL to number(0),
                CommandProperty.EAR_DETECTION_CONFIG to number(1),
                CommandProperty.AUTOMATIC_CONNECTION_CONFIG to number(1),
                CommandProperty.SLEEP_DETECTION_CONFIG to number(2),
                CommandProperty.DYNAMIC_END_OF_CHARGE to number(1),
                // Off is offered (1): four modes on the selector. PPE is left unreported on
                // purpose, so one row of the main page shows what "not received" looks like.
                CommandProperty.ALLOW_OFF_OPTION to number(1),
                CommandProperty.CALL_MANAGEMENT_CONFIG to number(3),
                CommandProperty.ONE_BUD_ANC_MODE to number(2),
                CommandProperty.VOLUME_SWIPE_MODE to number(1),
                CommandProperty.VOLUME_SWIPE_INTERVAL to number(1),
            ),
            batteries = mapOf(
                4 to rx(BatteryValue(82, 2)), 2 to rx(BatteryValue(64, 2)), 8 to rx(BatteryValue(37, 1)),
            ),
            pending = mapOf(CommandProperty.DOUBLE_CLICK_INTERVAL to PendingCommand(1, NOW - 400, 3, generation = GENERATION)),
            outcomes = mapOf(CommandProperty.CLICK_HOLD_INTERVAL to CommandOutcome.NOT_CONFIRMED),
        ),
        pair = PairSettings(
            verification = PairVerification("A3063", "demo", 0),
            host = HostSettings(automationsEnabled = true, earPause = true, earResume = true, headGestures = true),
        ),
        enabled = true, profileConnected = true, bluetoothGranted = true,
        productGatePassed = true, conformanceMatches = true, nowMs = NOW,
        candidates = listOf(
            PairCandidate("demo-1", "DEMO AirPods", "DEMO · coppia scelta"),
            PairCandidate("demo-2", "DEMO seconda coppia", "DEMO"),
        ),
        selectedCandidateId = "demo-1",
        availability = availability(true, null, *done(SetupAction.BLUETOOTH_PERMISSION,
            SetupAction.VERIFY_SELECTED, SetupAction.ENABLE, SetupAction.RESUME, SetupAction.CONNECT,
            SetupAction.STOP_DIAGNOSTICS, SetupAction.COMPANION_ASSOCIATION)),
        localVolumePercent = 45,
        gestureAssignments = mapOf(
            GestureSide.LEFT to GestureAction.NOISE_CONTROL,
            GestureSide.RIGHT to GestureAction.DIGITAL_ASSISTANT,
            GestureSide.NOD to GestureAction.PLAY_PAUSE,
        ),
        diagnostics = listOf("DEMO · riga di diagnostica inventata", "DEMO · nessun dispositivo coinvolto"),
    )

    /** Both earbuds alike and charging in the optimised way: one ring for the pair, stopped at 80 %. */
    val paired = connected.copy(domain = connected.domain.copy(batteries = mapOf(
        4 to rx(BatteryValue(96, 5)), 2 to rx(BatteryValue(95, 5)), 8 to rx(BatteryValue(12, 2)))))

    /** One earbud reporting, the case only as history: unknown stays unknown, old stays old. */
    val partial = connected.copy(domain = connected.domain.copy(batteries = mapOf(
        4 to rx(BatteryValue(82, 2)), 8 to rx(BatteryValue(37, 1), ageMs = 420_000))))

    /** A listening-mode request waiting for its answer: the received mode is still the selected one. */
    val pending = connected.copy(domain = connected.domain.copy(
        pending = mapOf(CommandProperty.MODE to PendingCommand(3, NOW - 400, 4, generation = GENERATION))))

    /** Verified pair, audio profile down: the player keeps the capsule, Home does not show one. */
    fun disconnected(reason: String) = connected.copy(profileConnected = false,
        domain = AirPodsState(),
        availability = availability(false, reason,
            "setup.CONNECT" to UiAvailability(true), "setup.BLUETOOTH_SETTINGS" to UiAvailability(true),
            "setup.DISABLE" to UiAvailability(true), "setup.STOP" to UiAvailability(true)))

    /** What the shipped product is today: gate off, nothing authorized, every control with its reason. */
    fun gateOff(reason: String) = connected.copy(productGatePassed = false, profileConnected = false,
        domain = AirPodsState(),
        availability = availability(false, reason,
            "setup.BLUETOOTH_SETTINGS" to UiAvailability(true),
            "setup.EXPORT_DIAGNOSTICS" to UiAvailability(true),
            "setup.DELETE_DIAGNOSTICS" to UiAvailability(true)))
}

/**
 * A pretend device for the stage: it answers what the surfaces ask, after a visible wait.
 *
 * Debug builds only, and only with [simulate]. Nothing is sent anywhere: a device write becomes a
 * pending request in the invented state and, 900 ms later, a received value equal to what was
 * asked; a phone-side preference changes at once. Setup and tracking actions are discarded,
 * because there is nothing here for them to act on. This is what lets the stage show the
 * difference the surfaces are built around: where the thumb is while a request is outstanding,
 * and where it goes when the answer arrives.
 */
@Composable
private fun rememberDemoSession(initial: AirPodsUiState, simulate: Boolean): Pair<AirPodsUiState, (AirPodsUiAction) -> Unit> {
    var state by remember(initial) { mutableStateOf(initial) }
    var sequence by remember(initial) { mutableStateOf(100L) }
    val scope = rememberCoroutineScope()
    fun answer(action: AirPodsUiAction) {
        if (!simulate) return
        when (action) {
            is AirPodsUiAction.Write -> if (state.writeAllowed(action.property, action.value)) {
                val number = (action.value as? PropertyValue.Number)?.value
                val name = (action.value as? PropertyValue.Name)?.value
                sequence += 1
                state = state.copy(domain = state.domain.copy(pending = state.domain.pending +
                    (action.property to PendingCommand(number ?: 0, AirPodsDemo.NOW, sequence,
                        requested = action.value, generation = state.domain.generation))))
                scope.launch {
                    delay(900)
                    val domain = state.domain
                    val answered = ListeningMode.entries.firstOrNull { it.wire == number }
                    state = state.copy(domain = domain.copy(
                        pending = domain.pending - action.property,
                        outcomes = domain.outcomes + (action.property to CommandOutcome.OBSERVED_MATCH),
                        properties = if (name == null) domain.properties +
                            (action.property to Received(action.value, AirPodsDemo.NOW, domain.generation))
                        else domain.properties,
                        mode = if (action.property == CommandProperty.MODE && answered != null)
                            Received(answered, AirPodsDemo.NOW, domain.generation) else domain.mode,
                        identityReceived = if (name == null) domain.identityReceived
                        else domain.identityReceived?.let { it.copy(value = it.value.copy(name = name)) },
                    ))
                }
            }
            is AirPodsUiAction.Toggle -> {
                val pair = state.pair
                val host = pair.host
                state = state.copy(pair = when (action.toggle) {
                    LocalToggle.AUTOMATIONS -> pair.copy(host = host.copy(automationsEnabled = action.enabled))
                    LocalToggle.OTHER_APPS -> pair.copy(host = host.copy(otherAppsEnabled = action.enabled))
                    LocalToggle.BACKGROUND -> pair.copy(host = host.copy(fluidifyBackgroundEnabled = action.enabled))
                    LocalToggle.EAR_PAUSE -> pair.copy(host = host.copy(earPause = action.enabled))
                    LocalToggle.EAR_RESUME -> pair.copy(host = host.copy(earResume = action.enabled))
                    LocalToggle.CA_DUCK -> pair.copy(host = host.copy(conversationDuck = action.enabled))
                    LocalToggle.CA_PAUSE -> pair.copy(host = host.copy(conversationPause = action.enabled))
                    LocalToggle.CA_RELATIVE -> pair.copy(host = host.copy(conversationRelative = action.enabled))
                    LocalToggle.HEAD_GESTURES -> pair.copy(host = host.copy(headGestures = action.enabled))
                    LocalToggle.ALTERNATE_PACKETS -> pair.copy(headPacketAlternate = action.enabled)
                    LocalToggle.DISCONNECT_UNWORN -> pair.copy(host = host.copy(disconnectUnworn = action.enabled))
                })
            }
            is AirPodsUiAction.ConversationVolume -> state = state.copy(pair = state.pair.copy(
                host = state.pair.host.copy(conversationVolumePercent = action.percent)))
            is AirPodsUiAction.LocalVolume -> state = state.copy(localVolumePercent = action.percent)
            is AirPodsUiAction.PreferredMode -> state = state.copy(pair = state.pair.copy(secondMode = when (action.mode) {
                ListeningMode.OFF -> QuickMode.OFF
                ListeningMode.ADAPTIVE -> QuickMode.ADAPTIVE
                ListeningMode.TRANSPARENCY -> QuickMode.TRANSPARENCY
                ListeningMode.ANC -> state.pair.secondMode
            }))
            is AirPodsUiAction.AssignGesture ->
                state = state.copy(gestureAssignments = state.gestureAssignments + (action.side to action.action))
            is AirPodsUiAction.SelectPair -> state = state.copy(selectedCandidateId = action.candidateId)
            is AirPodsUiAction.Setup -> Unit
            is AirPodsUiAction.Tracking -> Unit
        }
    }
    return state to ::answer
}

/**
 * The three AirPods surfaces on the app's real glass plumbing, with nothing of the app's data in it.
 *
 * The wiring is SquareApp's, in the same order: a page-wide recording for the chrome and the
 * modal host (`LocalGlassBackdrop`), and inside it the artwork alone, recorded before any page
 * and published as the canvas (`LocalFluidCanvasBackdrop`) and as the page paper
 * (`FluidifyAmbientSource`). The settings stand in the real page host with the host page's own
 * pages and chrome ([AirPodsSettingsPages], the one call `AirPodsHostPage` makes); the capsule
 * samples the artwork, never the recording it is drawn into; the quick pane is declared through
 * the real portal and drawn by a modal host outside the recording.
 *
 * The artwork is drawn, not fetched, and deliberately uneven, because a pane that only ever
 * stands on a mid grey proves nothing about whether it transmits. With [flatGround] the patches
 * are left out: only glass changes, which tells a pane of glass from an opaque fallback.
 *
 * @param view
 *  - `flow` (default): the way the app is walked. The capsule where Home and the player put it;
 *    touching it opens the quick pane; the pane's button opens the settings; a row opens its page.
 *  - `board`: the three surfaces side by side, each 380 dp wide, to look at them together. On a
 *    screen narrower than the three it scrolls sideways.
 *  - `page`: the settings alone, opened on [page].
 *  - `quick`: a player-like top with the capsule and the quick pane already open.
 *  - `capsules`: the capsule at the widths Home and the player give it.
 * @param page for `page` and `board`, the settings page to show first.
 * @param simulate answer what the surfaces ask (see [rememberDemoSession]); off, every action is
 *   discarded and the state never changes, which is what a capture wants.
 */
@Composable
internal fun AirPodsGlassStage(
    dark: Boolean,
    amoled: Boolean = false,
    fontScale: Float = 1f,
    view: String = "flow",
    state: AirPodsUiState = AirPodsDemo.connected,
    /** Constrain the stage to this many dp, centred, to look at a narrow phone on a wide one. */
    widthDp: Int = 0,
    flatGround: Boolean = false,
    page: AirPodsSettingsPage = AirPodsSettingsPage.Main,
    simulate: Boolean = false,
    onClose: () -> Unit = {},
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        SquareTheme(darkTheme = dark, amoled = amoled) {
            val pageGlass = rememberGlassBackdrop()
            val ground = rememberGlassBackdrop()
            val ambient = rememberFluidifyPageAmbient()
            val modal = rememberFluidGlassModalHostState()
            val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val session = rememberDemoSession(state, simulate)
            val live = session.first
            val dispatch = session.second
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                LocalGlassBackdrop provides pageGlass,
                LocalFluidCanvasBackdrop provides ground,
                LocalFluidifyPageAmbient provides ambient,
                LocalFluidGlassModalHostState provides modal,
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopCenter) {
                    Box(if (widthDp > 0) Modifier.width(widthDp.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                        // Only the artwork and the surfaces are recorded; the modal host must
                        // never sample its own pixels.
                        Box(Modifier.fillMaxSize().glassBackdropSource(pageGlass)) {
                            FluidifyAmbientSource(ambient, Modifier.fillMaxSize().glassBackdropSource(ground)) {
                                DemoArtwork(dark, flatGround)
                            }
                            val insets = PaddingValues(top = statusBar, bottom = navigationBar)
                            when (view) {
                                "quick" -> DemoPlayerTop(live, dispatch, ground, statusBar)
                                "capsules" -> DemoCapsules(live, ground, statusBar)
                                "board" -> DemoBoard(live, dispatch, ground, page, statusBar, navigationBar)
                                "page" -> FluidifyPageHost(stack = listOf("airpods"), onBack = onClose) { _ ->
                                    AirPodsSettingsPages(live, dispatch, insets, ground, onClose, start = page)
                                }
                                else -> DemoFlow(live, dispatch, ground, insets, onClose)
                            }
                        }
                        FluidGlassModalHost(modal, pageGlass)
                        DemoNotice(Modifier.align(Alignment.BottomCenter).padding(bottom = navigationBar + 8.dp))
                    }
                }
            }
        }
    }
}

/**
 * Home, the quick pane and the settings, joined the way the app joins them.
 *
 * The capsule is drawn twice because the app has two places for it; either opens the same pane.
 */
@Composable
private fun DemoFlow(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit, ground: GlassBackdropState,
    insets: PaddingValues, onClose: () -> Unit) {
    var quick by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf<Rect?>(null) }
    var settings by remember { mutableStateOf(false) }
    val binding = AirPodsUiBinding(state, dispatch, { origin = null; quick = true }, { rect -> origin = rect; quick = true })
    val stack = remember(settings) { if (settings) listOf("home", "airpods") else listOf("home") }
    FluidifyPageHost(stack = stack, onBack = { if (settings) settings = false else onClose() }) { key ->
        if (key == "airpods") AirPodsSettingsPages(state, dispatch, insets, ground, { settings = false })
        else Column(Modifier.fillMaxSize().padding(insets).padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            for ((width, place) in listOf(120 to "Home · 120 dp", 160 to "Player · 160 dp")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.testTag("airpods-demo-capsule-$width")) {
                        AirPodsCapsule(binding, Modifier.width(width.dp), backdrop = ground)
                    }
                    Text(place, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clearAndSetSemantics { })
                }
            }
            // The app's other way in: the settings entry, without passing through the pane.
            FluidButton(stringResource(R.string.airpods_ui_full), { settings = true }, style = FluidButtonStyle.Tinted,
                modifier = Modifier.heightIn(min = 48.dp).testTag("airpods-demo-open-settings"))
        }
    }
    AirPodsQuickOverlay(state, quick, { quick = false }, dispatch, { quick = false; settings = true }, origin = { origin })
}

/** The capsule, the quick pane's content and a settings page, side by side. */
@Composable
private fun DemoBoard(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit, ground: GlassBackdropState,
    start: AirPodsSettingsPage, statusBar: Dp, navigationBar: Dp) {
    var page by remember(start) { mutableStateOf(start) }
    val binding = AirPodsUiBinding(state, dispatch, {})
    Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())
        .padding(start = 16.dp, top = statusBar + 12.dp, end = 16.dp, bottom = navigationBar + 44.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BoardColumn("Capsula") {
            for ((width, place) in listOf(96 to "Home · 96 dp", 120 to "Home · 120 dp", 160 to "Player · 160 dp")) {
                Box(Modifier.testTag("airpods-demo-capsule-$width")) {
                    AirPodsCapsule(binding, Modifier.width(width.dp), backdrop = ground)
                }
                Text(place, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        BoardColumn("Pannello rapido") {
            // The pane's content on the page's own glass: the modal sheet cannot stand beside a page.
            FluidListGroup(Modifier.testTag("airpods-demo-quick"), glass = true) {
                Box(Modifier.padding(16.dp)) { AirPodsQuickContent(state, dispatch) }
            }
        }
        BoardColumn("Impostazioni") {
            if (page != AirPodsSettingsPage.Main) FluidButton("< " + page.name, { page = AirPodsSettingsPage.Main },
                style = FluidButtonStyle.Plain)
            AirPodsSettingsContent(state, dispatch, Modifier.weight(1f), page = page, onOpen = { page = it })
        }
    }
}

@Composable
private fun BoardColumn(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.width(380.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics { })
        content()
    }
}

/** The capsule where the player puts it, and the quick pane it opens, already open. */
@Composable
private fun DemoPlayerTop(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit, ground: GlassBackdropState,
    statusBar: Dp) {
    var quick by remember { mutableStateOf(true) }
    var origin by remember { mutableStateOf<Rect?>(null) }
    val binding = AirPodsUiBinding(state, dispatch, { origin = null; quick = true }, { rect -> origin = rect; quick = true })
    Box(Modifier.fillMaxWidth().padding(top = statusBar + 6.dp), contentAlignment = Alignment.Center) {
        AirPodsCapsule(binding, Modifier.widthIn(max = 160.dp), backdrop = ground)
    }
    AirPodsQuickOverlay(state, quick, { quick = false }, dispatch, { quick = false }, origin = { origin })
}

/** The capsule at the two widths Home can give it and the one the player does. */
@Composable
private fun DemoCapsules(state: AirPodsUiState, ground: GlassBackdropState, statusBar: Dp) {
    val binding = AirPodsUiBinding(state, {}, {})
    Column(Modifier.fillMaxWidth().padding(top = statusBar + 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        for ((width, place) in listOf(96 to "Home · 96 dp", 120 to "Home · 120 dp", 160 to "Player · 160 dp")) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.testTag("airpods-demo-capsule-$width")) {
                    AirPodsCapsule(binding, Modifier.width(width.dp), backdrop = ground)
                }
                Text(place, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

/** Says, on every frame anyone can photograph, that what is on screen was invented. Not a control. */
@Composable
private fun DemoNotice(modifier: Modifier = Modifier) {
    Text("DEMO · dati inventati · nessun dispositivo",
        style = MaterialTheme.typography.labelMedium, color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.62f), FluidCapsuleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("airpods-demo-notice"))
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
 * Opens the stage. Debug builds only, synthetic state only; it starts no service, opens no socket
 * and touches no stored AirPods configuration.
 *
 *     adb shell am start -n dev.pampa.fluidify/dev.lelonio.square.ui.airpods.AirPodsGlassPreviewActivity \
 *         --es view flow --es theme dark --es state connected --ef font 1.0
 *
 * Extras, all optional: `view` = flow (default) | board | page | quick | capsules; `theme` = dark
 * (default) | light | amoled; `state` = connected (default) | paired | partial | pending |
 * disconnected | gate-off; `page` = a settings page by name (Main, Rename, LongPressLeft,
 * LongPressRight, Calls, AdaptiveStrength, Equalizer, Controls, HeadGestures, Automations, Setup,
 * Diagnostics) for `page` and `board`; `sim` = false to discard every action instead of answering
 * it; `font` = 0.85..2.0; `width` = a stage width in dp (for example 360); `flat` = true for the
 * ground without artwork.
 */
class AirPodsGlassPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val theme = intent.getStringExtra("theme")
        val view = intent.getStringExtra("view") ?: "flow"
        val fontScale = intent.getFloatExtra("font", 1f).coerceIn(0.85f, 2f)
        val widthDp = intent.getIntExtra("width", 0).coerceIn(0, 1600)
        val flat = intent.getBooleanExtra("flat", false)
        val simulate = intent.getBooleanExtra("sim", true)
        val page = AirPodsSettingsPage.entries.firstOrNull { it.name == intent.getStringExtra("page") }
            ?: AirPodsSettingsPage.Main
        val state = when (intent.getStringExtra("state")) {
            "paired" -> AirPodsDemo.paired
            "partial" -> AirPodsDemo.partial
            "pending" -> AirPodsDemo.pending
            "disconnected" -> AirPodsDemo.disconnected(getString(R.string.airpods_reason_connection))
            "gate-off" -> AirPodsDemo.gateOff(getString(R.string.airpods_reason_gate))
            else -> AirPodsDemo.connected
        }
        setContent {
            AirPodsGlassStage(dark = theme != "light", amoled = theme == "amoled", fontScale = fontScale,
                view = view, state = state, widthDp = widthDp, flatGround = flat, page = page,
                simulate = simulate, onClose = { finish() })
        }
    }
}
