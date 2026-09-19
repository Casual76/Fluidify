package dev.lelonio.square.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.GlassFalloff
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSlider
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.LocalGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.components.GlassButton
import dev.lelonio.square.ui.components.GlassChip
import dev.lelonio.square.ui.components.GlassChoiceItem
import dev.lelonio.square.ui.components.GlassMenuRule
import dev.lelonio.square.ui.components.menuSkin
import dev.lelonio.square.ui.glass.LocalAppBackdrop
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.player.GlassSurface
import dev.lelonio.square.ui.player.rememberPillMorphTint
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.InkInverse
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.glassEdge
import dev.lelonio.square.ui.theme.glassFilm
import dev.lelonio.square.ui.theme.pageBarTint
import dev.lelonio.square.ui.theme.pageFloor
import dev.lelonio.square.ui.theme.pageWash

/**
 * Every surface the app is made of, on all three sides, over anything you like behind it.
 *
 * It exists because the app cannot be looked at: it is a Spotify client, and a machine without an
 * account only ever reaches the tutorial and the login. Everything that has ever been wrong on the
 * light side lives on surfaces neither of those two screens shows.
 *
 * **What changed, and why it is the point.** The first version had one backdrop — a violent
 * gradient — and that was enough to catch a rim that never turned over. It was not enough to catch
 * the thing that actually made the light theme unusable, because that fault is not in the glass: it
 * is in *what the glass is standing on*. A pane over a flat, veiled page behaves; the same pane over
 * a photograph at full strength does not, and no amount of staring at one backdrop shows it. So the
 * stage is now a choice — see [Stage] — and every surface is drawn over whichever one is picked, on
 * three sides at once. A difference is then a difference between two things on one screen, rather
 * than between two screenshots taken a minute apart.
 *
 * Both renderers are fed. The app's vendored [liquidGlass] samples a `LayerBackdrop`; the engine's
 * [glassSurface] samples a `GlassBackdropState`; and the two are recorded over the same box, because
 * half of what goes wrong in this app goes wrong at the seam between them.
 *
 * Only in the `dev` build type, and — deliberately — no longer a second launcher icon. It is started
 * by name:
 *
 * ```
 * adb shell am start -n dev.pampa.fluidify/dev.lelonio.square.gallery.GlassGalleryActivity
 * ```
 */
class GlassGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var stage by remember { mutableStateOf(Stage.VeiledPage) }
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                StagePicker(stage) { stage = it }
                Row(Modifier.fillMaxSize()) {
                    GallerySide(false, false, stage, Modifier.weight(1f))
                    GallerySide(true, false, stage, Modifier.weight(1f))
                    GallerySide(true, true, stage, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * What is behind the glass.
 *
 * Ordered the way the census is ordered, because legibility is a function of the backdrop and of
 * nothing else. [Floor] and [VeiledPage] are the cases that already behave; the four below them are
 * where the light side falls apart, and each one is a real place in the app:
 *
 * - [DarkCover] / [BrightCover] — a record's header, where the cover is full-bleed and unveiled.
 * - [Mosaic] — a bar or the pill over the library grid, which is neither dark nor light but both,
 *   twelve times per screen.
 * - [Canvas] — the player's stage, the brightest backdrop the app can produce.
 */
private enum class Stage(val label: String) {
    Floor("Pavimento"),
    VeiledPage("Velata"),
    DarkCover("Cop. scura"),
    BrightCover("Cop. chiara"),
    Mosaic("Mosaico"),
    Canvas("Canvas"),
}

/**
 * Chrome, not a specimen: fixed dark, so it never reads as part of what is being judged.
 *
 * Inset below the status bar, and that is not cosmetics. Drawn underneath it the first two chips
 * were unreachable — the system takes the touch before the app sees it — which is the same trap the
 * collapsed bar has caught this app with before ([[fluidify-gotchas]] 1). A control you cannot press
 * on a bench is a control you will quietly stop testing.
 */
@Composable
private fun StagePicker(current: Stage, onPick: (Stage) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF111114))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stage.entries.forEach { stage ->
            val on = stage == current
            Text(
                stage.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (on) Color(0xFF111114) else Color(0xFFE8E8EC),
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (on) Color(0xFFE8E8EC) else Color(0x1FFFFFFF))
                    .clickable { onPick(stage) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun GallerySide(
    dark: Boolean,
    amoled: Boolean,
    stage: Stage,
    modifier: Modifier = Modifier,
) {
    SquareTheme(darkTheme = dark, amoled = amoled) {
        // Two recordings of one box. The app runs both renderers and the seam between them is a
        // real source of faults, so the bench has to be able to show them side by side.
        val vendored = rememberLayerBackdrop()
        val engineGlass = rememberGlassBackdrop()

        Box(modifier.fillMaxSize().background(pageFloor)) {
            // Recorded into its own layer, and everything glassy is drawn outside it: a pane drawn
            // inside the layer it samples recurses on the render thread until the process dies.
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(vendored)
                    .glassBackdropSource(engineGlass),
            ) {
                StageContent(stage)
            }

            CompositionLocalProvider(
                LocalAppBackdrop provides vendored,
                LocalGlassBackdrop provides engineGlass,
                LocalFluidCanvasBackdrop provides engineGlass,
            ) {
                Specimens(dark, amoled, engineGlass)
            }
        }
    }
}

@Composable
private fun StageContent(stage: Stage) {
    when (stage) {
        // Nothing at all. The one case where glass has nothing to refract and has to read purely
        // as film, rim and shadow — which is also every AMOLED screen with a dark cover.
        Stage.Floor -> Unit

        Stage.VeiledPage -> {
            Box(Modifier.fillMaxSize().background(GalleryCover))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(pageWash(0.20f), pageWash(0.30f), pageWash(0.42f)),
                    ),
                ),
            )
        }

        Stage.DarkCover -> Box(Modifier.fillMaxSize().background(DarkCover))
        Stage.BrightCover -> Box(Modifier.fillMaxSize().background(BrightCover))
        Stage.Canvas -> Box(Modifier.fillMaxSize().background(CanvasFrame))
        Stage.Mosaic -> Mosaic()
    }
}

/**
 * A grid of covers, alternating bright and dark.
 *
 * The case a single flat backdrop can never stand in for: a bar crossing this has a near-white tile
 * under one end and a near-black one under the other, and a film chosen for either is wrong for the
 * other. It is what the library actually looks like under the tab bar.
 */
@Composable
private fun Mosaic() {
    Column(Modifier.fillMaxSize()) {
        repeat(9) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(3) { col ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MosaicTiles[(row * 3 + col) % MosaicTiles.size]),
                    )
                }
            }
        }
    }
}

@Composable
private fun Specimens(dark: Boolean, amoled: Boolean, engineGlass: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (!dark) "Chiaro" else if (amoled) "Nero" else "Scuro",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        // Page ink, standing on the stage with nothing under it. The control for everything below:
        // if this is unreadable the backdrop is at fault, not the glass.
        Text("Testo di pagina", color = Ink, style = MaterialTheme.typography.bodyMedium)
        Text("e la sua versione smorzata", color = InkDim, style = MaterialTheme.typography.bodySmall)

        Label("Barra collassante")
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .glassSurface(
                    state = engineGlass,
                    tint = pageBarTint(),
                    shape = RoundedCornerShape(0.dp),
                    edge = GlassEdge.Bottom,
                    falloff = GlassFalloff.FadeDown,
                    role = GlassRole.Bar,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                "Libreria",
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }

        // The join the morph exists to hide: the engine's pill next to the app's own, on one
        // screen. They are meant to be one material; any step between them is the bug.
        Label("Pillola — engine | app")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(54.dp)
                    .glassSurface(
                        state = engineGlass,
                        tint = rememberPillMorphTint(),
                        shape = RoundedCornerShape(percent = 50),
                        role = GlassRole.Floating,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("engine", color = Ink, style = MaterialTheme.typography.labelMedium)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(54.dp)
                    .liquidGlass(
                        config = LocalGlassEffectConfig.current,
                        shape = RoundedCornerShape(percent = 50),
                        highlightAlpha = 0.3f,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("app", color = Ink, style = MaterialTheme.typography.labelMedium)
            }
        }

        // A record's header: the one place a pane genuinely stands on a cover at full strength,
        // and the only pane in the app that draws a second rim to survive it.
        Label("Capsula della testata")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .liquidGlass(
                        config = LocalGlassEffectConfig.current,
                        shape = RoundedCornerShape(percent = 50),
                    )
                    .headerRim(RoundedCornerShape(percent = 50)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Condividi",
                    color = Ink,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            Box(
                Modifier
                    .size(46.dp)
                    .liquidGlass(config = LocalGlassEffectConfig.current, shape = CircleShape)
                    .headerRim(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("↓", color = Ink, style = MaterialTheme.typography.titleMedium)
            }
        }

        Label("Bottoni e chip")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassButton(onClick = {}) {
                Text("Vetro", style = MaterialTheme.typography.labelLarge)
            }
            GlassButton(onClick = {}, flat = true) {
                Text("Piatto", style = MaterialTheme.typography.labelLarge)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("Acceso", selected = true, onClick = {})
            GlassChip("Spento", selected = false, onClick = {})
        }

        // A sheet and the scrim under it, which is the one wash that darkens on both sides.
        Label("Foglio sopra lo scrim")
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color.Black.copy(alpha = 0.42f)),
            contentAlignment = Alignment.Center,
        ) {
            GlassSurface(
                backdrop = LocalAppBackdrop.current,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                blurScale = 23f,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Un brano", color = Ink, style = MaterialTheme.typography.titleSmall)
                    Text("Un artista", color = InkDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Not glass, and it must not look like a failed attempt at it: a dialog is a separate
        // window that cannot sample anything. What it can be is the same design.
        Label("Dialogo e menu")
        Box(Modifier.fillMaxWidth().menuSkin(RoundedCornerShape(20.dp))) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    "Togliere dalla libreria?",
                    color = Ink,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                GlassMenuRule()
                GlassChoiceItem("Per data", selected = true, onClick = {})
                GlassChoiceItem("Per nome", selected = false, onClick = {})
            }
        }

        Label("Controlli")
        var checked by remember { mutableStateOf(true) }
        var value by remember { mutableFloatStateOf(0.4f) }
        var picked by remember { mutableStateOf("Uno") }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluidSwitch(checked = checked, onCheckedChange = { checked = it })
            Text("Interruttore", color = InkDim, style = MaterialTheme.typography.labelMedium)
        }
        FluidSlider(value = value, onValueChange = { value = it })
        FluidSegmentedControl(
            options = listOf("Uno", "Due"),
            selected = picked,
            onSelect = { picked = it },
            label = { it },
        )

        Label("Inchiostro invertito e copertina generata")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Ink)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("Segui già", color = InkInverse, style = MaterialTheme.typography.labelLarge)
            }
            Artwork(url = null, title = "Senza copertina", modifier = Modifier.size(56.dp))
            // The scrollbar's thumb, which is Ink at just over half.
            Box(
                Modifier
                    .size(width = 4.dp, height = 44.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Ink.copy(alpha = 0.55f)),
            )
        }

        // The tokens as paint, so a wrong one shows even where nothing happens to use it.
        Label("Token")
        Swatch("floor", pageFloor)
        Swatch("film .10", glassFilm(0.10f))
        Swatch("film .16", glassFilm(0.16f))
        Swatch("edge .30", glassEdge(0.30f))
        Swatch("wash .28", pageWash(0.28f))

        // The widget's playing state, which is otherwise unreachable here: a machine with no
        // account can never make anything play, so the card can only ever be caught saying it has
        // nothing. This writes a track into the widget's own state the way the service would.
        if (!dark) {
            val context = androidx.compose.ui.platform.LocalContext.current
            Label("Widget")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true, false).forEach { playing ->
                    Text(
                        if (playing) "widget ▶" else "widget ⏸",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink,
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(glassFilm(0.16f))
                            .clickable {
                                dev.lelonio.square.widget.media.NowPlayingWidgetBridge.preview(
                                    context = context,
                                    title = "Un brano di prova",
                                    artist = "Fluidify",
                                    artworkUrl = SampleCover,
                                    playing = playing,
                                )
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}

/** The second rim a header capsule draws, because refraction alone is invisible over flat colour. */
@Composable
private fun Modifier.headerRim(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.border(0.6.dp, glassEdge(0.30f), shape)

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        color = InkDim,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun Swatch(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 54.dp, height = 20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = InkDim, style = MaterialTheme.typography.labelSmall)
    }
}

/** A real https image, so the cover path is exercised and not only the placeholder. */
private const val SampleCover =
    "https://raw.githubusercontent.com/Casual76/Fluidify/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"

/**
 * A stand-in for album art, and a hostile one on purpose.
 *
 * Near-white at one end and near-black at the other with saturated colour in between: a rim, a film
 * or a wash that only works over a mid tone gives itself away here in one screenshot instead of
 * after twenty covers.
 */
private val GalleryCover = Brush.linearGradient(
    0.00f to Color(0xFFFFFFFF),
    0.22f to Color(0xFFFFC857),
    0.45f to Color(0xFFE4572E),
    0.68f to Color(0xFF2E86AB),
    0.88f to Color(0xFF1B1B3A),
    1.00f to Color(0xFF050506),
    start = Offset.Zero,
    end = Offset(900f, 2400f),
)

/** A cover with nothing bright in it — where a white film reads as a slab and kills the picture. */
private val DarkCover = Brush.linearGradient(
    0.00f to Color(0xFF14161C),
    0.35f to Color(0xFF1E2430),
    0.62f to Color(0xFF2A1F2E),
    1.00f to Color(0xFF07070A),
    start = Offset.Zero,
    end = Offset(900f, 2400f),
)

/** And one with nothing dark — where a white film disappears and the rim has to carry it alone. */
private val BrightCover = Brush.linearGradient(
    0.00f to Color(0xFFFDFBF4),
    0.38f to Color(0xFFF3E8D8),
    0.70f to Color(0xFFE8EEF4),
    1.00f to Color(0xFFFAFAFC),
    start = Offset.Zero,
    end = Offset(900f, 2400f),
)

/** The player's stage: a sunlit clip, the brightest thing the app can put behind a control. */
private val CanvasFrame = Brush.linearGradient(
    0.00f to Color(0xFFBFD8E8),
    0.30f to Color(0xFFE9DCA8),
    0.55f to Color(0xFFC9B87A),
    0.78f to Color(0xFF8E7A4E),
    1.00f to Color(0xFF3A3324),
    start = Offset.Zero,
    end = Offset(500f, 2200f),
)

private val MosaicTiles = listOf(
    Color(0xFFF7F4EE),
    Color(0xFF15161A),
    Color(0xFFD94F3D),
    Color(0xFFFBFBFD),
    Color(0xFF223A5E),
    Color(0xFFE8C547),
    Color(0xFF0B0B0D),
    Color(0xFF7A5CA8),
    Color(0xFFEFEFF3),
)
