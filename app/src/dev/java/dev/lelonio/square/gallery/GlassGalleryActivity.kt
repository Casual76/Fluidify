package dev.lelonio.square.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSlider
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.InkInverse
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.glassEdge
import dev.lelonio.square.ui.theme.glassFilm
import dev.lelonio.square.ui.theme.pageFloor
import dev.lelonio.square.ui.theme.pageWash

/**
 * Every surface the app is made of, on both sides at once.
 *
 * Built for a night of work on the light theme, and it exists because the app
 * cannot be looked at: it is a Spotify client, and an emulator has no account,
 * so the only screens reachable without signing in are the tutorial and the
 * login. Everything that was wrong on the light side — the rim, the film, the
 * wash over a cover, the wash under a puck — lives on surfaces none of those two
 * screens show.
 *
 * Two columns, the same content, one side each, so a difference is a difference
 * between two things on one screen rather than between two screenshots taken a
 * minute apart. The cover behind them is drawn rather than downloaded, and it is
 * deliberately violent — a near-white patch beside a near-black one — because a
 * rim that survives a mid grey can still vanish on either end of a photograph.
 *
 * Only in the `dev` build type. It is not in the release source set, so it does
 * not ship, and it does not need to: nothing here is a feature.
 */
class GlassGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Row(Modifier.fillMaxSize()) {
                GallerySide(dark = false, amoled = false, modifier = Modifier.weight(1f))
                GallerySide(dark = true, amoled = false, modifier = Modifier.weight(1f))
                GallerySide(dark = true, amoled = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GallerySide(dark: Boolean, amoled: Boolean, modifier: Modifier = Modifier) {
    SquareTheme(darkTheme = dark, amoled = amoled) {
        val cover = rememberLayerBackdrop()
        Box(modifier.fillMaxSize().background(pageFloor)) {
            // What the glass has to see through. Recorded into its own layer for
            // the reason the app's own note gives: a pane drawn inside the layer
            // it samples recurses on the render thread until the process dies.
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(cover)
                    .background(GalleryCover),
            )
            // The page veil, taken in the same two directions the app takes it.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(pageWash(0.20f), pageWash(0.30f), pageWash(0.42f)),
                        ),
                    ),
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 44.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    if (!dark) "Light" else if (amoled) "Black" else "Dark",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )

                // A sheet. The thing whose rim was unconditionally white.
                GlassPane(cover, RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("A song", color = Ink, style = MaterialTheme.typography.titleSmall)
                        Text("An artist", color = InkDim, style = MaterialTheme.typography.bodySmall)
                        Text("Add to queue", color = Ink, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Remove",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // A capsule and a round button: the bar's two shapes.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPane(cover, RoundedCornerShape(percent = 50), Modifier.weight(1f)) {
                        Text(
                            "Follow",
                            color = Ink,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    GlassPane(cover, CircleShape, Modifier.size(46.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("+", color = Ink, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }

                // Inverted ink: a filled control, where the page becomes the letters.
                Box(
                    Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Ink)
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                ) {
                    Text("Following", color = InkInverse, style = MaterialTheme.typography.labelLarge)
                }

                // The engine's controls, which Fase C is about to replace.
                var checked by remember { mutableStateOf(true) }
                var value by remember { mutableFloatStateOf(0.4f) }
                var picked by remember { mutableStateOf("One") }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FluidSwitch(checked = checked, onCheckedChange = { checked = it })
                    Text("Switch", color = InkDim, style = MaterialTheme.typography.labelMedium)
                }
                FluidSlider(value = value, onValueChange = { value = it })
                FluidSegmentedControl(
                    options = listOf("One", "Two"),
                    selected = picked,
                    onSelect = { picked = it },
                    label = { it },
                )

                // The tokens themselves, as paint on the page, so a wrong one is
                // visible even where nothing happens to be using it.
                Swatch("floor", pageFloor)
                Swatch("film .10", glassFilm(0.10f))
                Swatch("edge .55", glassEdge(0.55f))
                Swatch("wash .28", pageWash(0.28f))
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun GlassPane(
    cover: dev.lelonio.square.ui.glass.backdrop.Backdrop,
    shape: androidx.compose.foundation.shape.CornerBasedShape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.liquidGlass(
            config = LocalGlassEffectConfig.current,
            shape = shape,
            ownBackdrop = cover,
        ),
    ) {
        content()
    }
}

@Composable
private fun Swatch(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 54.dp, height = 22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = InkDim, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * A stand-in for album art, and a hostile one on purpose.
 *
 * Near-white at one end and near-black at the other with saturated colour in
 * between: a rim, a film or a wash that only works over a mid tone gives itself
 * away here in one screenshot instead of after twenty covers.
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
