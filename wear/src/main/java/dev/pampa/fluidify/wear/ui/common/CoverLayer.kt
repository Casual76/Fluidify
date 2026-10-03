package dev.pampa.fluidify.wear.ui.common

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.MaterialTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.pampa.fluidify.wear.link.ArtStore
import java.io.File

/**
 * The cover, full bleed: the picture every pane of glass on the player bends.
 *
 * Sharp, not blurred — the glass does the softening where it sits, and
 * everywhere else the cover is the cover. The scrims that keep the controls
 * legible live *inside* this layer, so the glass refracts the darkened picture
 * the eye sees rather than a brighter one behind it.
 *
 * A new cover crossfades in; that crossfade is the one moment the glass over it
 * is live, which is exactly the "frozen unless something moves" rule.
 *
 * @param dimmed ambient mode: the cover at a fraction of its brightness, no scrims.
 */
@Composable
fun CoverLayer(
    artKey: String?,
    art: ArtStore,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val revision by art.revision.collectAsState()
    val file: File? = remember(artKey, revision) { art.fileFor(artKey) }
    val floor = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary

    Box(modifier = modifier.background(floor)) {
        Crossfade(
            targetState = file,
            animationSpec = FluidMotion.crossFade(),
            label = "cover",
        ) { cover ->
            if (cover != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(cover)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (dimmed) AmbientCoverFilter else null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No cover yet (or nothing playing): the brand's light on black, so the glass
                // has something to bend and the screen is not a void.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = if (dimmed) 0.12f else 0.38f), floor),
                            ),
                        ),
                )
            }
        }
        if (!dimmed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val vertical = Brush.verticalGradient(
                            0f to floor.copy(alpha = 0.50f),
                            0.32f to floor.copy(alpha = 0.10f),
                            0.62f to floor.copy(alpha = 0.18f),
                            1f to floor.copy(alpha = 0.70f),
                        )
                        val rim = Brush.radialGradient(
                            0.62f to Color.Transparent,
                            1f to floor.copy(alpha = 0.55f),
                            center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension / 2f,
                        )
                        onDrawBehind {
                            drawRect(vertical)
                            drawRect(rim)
                        }
                    },
            )
        }
    }
}

/**
 * The cover in ambient mode: a third of its brightness and a little desaturated.
 * The panel is nominally off; what stays on should read as a memory of the cover,
 * not light it up.
 */
private val AmbientCoverFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(0.6f)
        val dim = ColorMatrix(
            floatArrayOf(
                0.36f, 0f, 0f, 0f, 0f,
                0f, 0.36f, 0f, 0f, 0f,
                0f, 0f, 0.36f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        timesAssign(dim)
    },
)
