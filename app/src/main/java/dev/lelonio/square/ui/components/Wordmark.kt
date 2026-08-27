package dev.lelonio.square.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.Ink

/**
 * The name, drawn rather than typeset.
 *
 * The icon is a square wave: one stroke width, right angles only, no curve
 * anywhere. No font shipped with Android says that — a bold sans still has
 * round bowls on D and U and a diagonal on Y — and pulling in a display face
 * for eight letters costs more than the eight letters do.
 *
 * So the letterforms are the same primitive as the icon: polylines on a 6×10
 * grid, orthogonal segments, square caps and joins. The D's right corners are
 * stepped, which is the one thing that keeps it from reading as a box.
 *
 * Sized by its height: the width follows from the grid, so callers give this a
 * height and let it measure itself. Glyphs carry their own width — an I is a
 * bare stem, and giving it a full box of advance would put a hole in the word.
 */
@Composable
fun FluidifyWordmark(
    height: Dp,
    modifier: Modifier = Modifier,
    color: Color = Ink,
) {
    Canvas(
        modifier
            .height(height)
            .width(height * (GRID_WIDTH / 12f)),
    ) {
        val unit = size.height / 12f
        // The stroke stays near one unit and the gap well over it, or the
        // counters close up and the word turns into a dark block.
        val stroke = unit * STROKE
        val path = Path()
        var originX = stroke / 2f
        GLYPHS.forEach { glyph ->
            glyph.lines.forEach { points ->
                points.forEachIndexed { at, (gx, gy) ->
                    val x = originX + gx * unit
                    val y = gy * unit + stroke / 2f
                    if (at == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
            originX += (glyph.width + GAP) * unit
        }
        drawPath(
            path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Square, join = StrokeJoin.Miter),
        )
    }
}

/**
 * The launcher art beside the name.
 *
 * The real icon rather than a drawing of one: this is what the user tapped to
 * get here, and showing the same thing is what says the two are one app.
 */
@Composable
fun AppLockup(
    modifier: Modifier = Modifier,
    iconSize: Dp = 28.dp,
    nameHeight: Dp = 14.dp,
    /**
     * What to put the mark on, when it should sit on glass.
     *
     * Null leaves it bare, which is what everywhere but the home header wants:
     * a plate is a control, and a mark in the middle of a login page is not one.
     */
    plate: dev.lelonio.square.ui.glass.backdrop.Backdrop? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (plate == null) {
            AppGlyph(iconSize)
        } else {
            AppPlate(iconSize, plate)
        }
        Spacer(Modifier.width(iconSize * 0.32f))
        FluidifyWordmark(nameHeight)
    }
}

/**
 * The mark on a round pane of the app's own glass.
 *
 * The same material and the same rim as the round buttons — see CircleAction —
 * so it belongs to the same row of controls even though there is nothing to
 * press. What it gives the mark is a place to stand.
 *
 * With something behind it to bend. Glass over the page's own flat colour has
 * nothing to refract and comes out as a grey disc, so the pane is given a wash
 * of the accent the app takes from the artwork that is playing: off-centre, so
 * the film and the rim have a gradient to sit on, and alive, because that
 * colour slides to a new one every time the music changes.
 *
 * Flat, deliberately. This sits in a header that moves with the scroll, and a
 * pane that samples the screen re-photographs it every frame — for a
 * reflection of a colour this already knows.
 */
@Composable
fun AppPlate(
    size: Dp,
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop,
    modifier: Modifier = Modifier,
) {
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    dev.lelonio.square.ui.glass.LiquidButton(
        onClick = {},
        backdrop = backdrop,
        isInteractive = false,
        flat = true,
        modifier = modifier
            .size(size)
            // Brightest at the top left and gone by the bottom right, which is
            // the one thing an even ring cannot do: a flat rim reads as a
            // sticker, a graded one as an edge catching light.
            .border(
                0.8.dp,
                androidx.compose.ui.graphics.Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.62f),
                    0.5f to Color.White.copy(alpha = 0.20f),
                    1f to Color.White.copy(alpha = 0.06f),
                ),
                androidx.compose.foundation.shape.CircleShape,
            ),
        contentHeight = size,
        contentPadding = 0.dp,
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            // Over the glass rather than under it.
            //
            // The material draws a film across whatever it is given, and a wash
            // painted underneath came out through it as flat grey — the thing
            // this exists to avoid. Above the film it is what the pane is
            // holding: the accent the app takes from the artwork playing, which
            // slides to a new colour every time the music changes.
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                drawRect(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        0f to accent.copy(alpha = 0.62f),
                        0.5f to accent.copy(alpha = 0.30f),
                        1f to accent.copy(alpha = 0.08f),
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(size.toPx(), size.toPx()),
                    ),
                )
                // The light on it, which is what makes it read as curved rather
                // than as a coloured circle.
                drawCircle(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.34f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(
                            this.size.width * 0.30f,
                            this.size.height * 0.20f,
                        ),
                        radius = this.size.width * 0.62f,
                    ),
                )
            }
            AppGlyph(size * 0.58f)
        }
    }
}

/**
 * The app's mark with no plate under it.
 *
 * The launcher icon is a tile: artwork on a coloured square, because that is
 * what a launcher draws. On a page of the app itself there is nothing for a
 * tile to sit on — it reads as a sticker of the icon rather than as the app's
 * own mark — so what is used is the wave alone, in the page's ink.
 *
 * Drawn from the same file the launcher art came from — see
 * `app/icon-src/wave-square.svg` — rather than from the stroked approximation
 * the themed launcher icon carries, which was a redrawing of it by hand and
 * showed at this size.
 */
@Composable
fun AppGlyph(size: Dp, modifier: Modifier = Modifier, tint: Color = Ink) {
    Icon(
        painter = painterResource(R.drawable.ic_app_mark),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

@Composable
fun AppIcon(size: Dp, modifier: Modifier = Modifier) {
    Image(
        // The adaptive icon's foreground, which is a plain PNG per density —
        // `ic_launcher` itself is the adaptive XML, which painterResource
        // cannot load.
        painter = painterResource(R.mipmap.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f)),
    )
}

/** One letter: its grid width plus the polylines that draw it. */
private class Glyph(val width: Float, val lines: List<List<Pair<Float, Float>>>)

/** Stroke width and inter-glyph gap, in grid units. */
private const val STROKE = 1.15f
private const val GAP = 3.5f

/** F L U I D I F Y, as polylines on the grid described in [FluidifyWordmark]. */
private val GLYPHS: List<Glyph> = run {
    // F: an E without its floor.
    val f = Glyph(
        6f,
        listOf(
            listOf(6f to 0f, 0f to 0f, 0f to 10f),
            listOf(0f to 5f, 4f to 5f),
        ),
    )
    // I: a bare stem; its advance is its stroke, not a full box.
    val i = Glyph(0f, listOf(listOf(0f to 0f, 0f to 10f)))
    listOf(
        f,
        // L
        Glyph(6f, listOf(listOf(0f to 0f, 0f to 10f, 6f to 10f))),
        // U
        Glyph(6f, listOf(listOf(0f to 0f, 0f to 10f, 6f to 10f, 6f to 0f))),
        i,
        // D: a box whose right corners step in, which is what suggests the
        // bowl without a single curve or diagonal.
        Glyph(
            6f,
            listOf(
                listOf(
                    0f to 0f, 4f to 0f, 4f to 2f, 6f to 2f,
                    6f to 8f, 4f to 8f, 4f to 10f, 0f to 10f, 0f to 0f,
                ),
            ),
        ),
        i,
        f,
        // Y: two arms meeting a bar, then the stem — orthogonal, like the rest.
        Glyph(
            6f,
            listOf(
                listOf(0f to 0f, 0f to 5f, 6f to 5f, 6f to 0f),
                listOf(3f to 5f, 3f to 10f),
            ),
        ),
    )
}

/** The word's total width in grid units, stroke included. */
private val GRID_WIDTH: Float =
    GLYPHS.map { it.width }.sum() + (GLYPHS.size - 1) * GAP + STROKE
