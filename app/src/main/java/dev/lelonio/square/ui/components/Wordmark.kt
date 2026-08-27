package dev.lelonio.square.ui.components

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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import dev.antigravity.fluidengine.ui.fluid.FluidDisplayFontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.Ink

/**
 * The name, set rather than drawn.
 *
 * It used to be a drawing: polylines on a 6x10 grid, orthogonal only, square
 * caps, on the argument that the icon is a square wave and no font says that.
 * The argument was wrong twice. The icon's own caps and joins are *round*, so
 * the drawn name was the harder shape of the two and a third heavier than the
 * symbol beside it; and the grid could not make three of the eight letters —
 * the D had no bowl and read as a rectangle, the Y had no diagonals and read as
 * a U with a tail, the U was a bracket. At the size the collapsed bar asks for,
 * a 1.3dp stroke, those three simply failed.
 *
 * Inter Display is already in the binary, shipped with the engine as the face
 * its own titles are set in, so this costs no kilobytes. It is an optical cut
 * for display sizes: tighter fitting and more closed apertures than the text
 * cut, which is exactly what a logotype wants. The tracking is pulled in
 * further still, past anything the type scale does, so the word reads as one
 * object rather than as a heading that happens to say the app's name.
 *
 * Sized by its **cap height**, not by its point size. Callers place this beside
 * a 44dp icon and mean "as tall as that": the ratio below converts, so a caller
 * asking for 22dp gets a capital 22dp tall. And in dp rather than sp, on
 * purpose — a logotype that grows with the system's font scale is a logotype
 * that breaks its own lockup.
 */
@Composable
fun FluidifyWordmark(
    height: Dp,
    modifier: Modifier = Modifier,
    color: Color = Ink,
) {
    val density = LocalDensity.current
    Text(
        text = "Fluidify",
        modifier = modifier,
        color = color,
        fontFamily = FluidDisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = with(density) { (height / CapHeightRatio).toSp() },
        // Explicit, and equal to the size: the default leading would add space
        // above the letters, and this sits in a Row that centres on the box it
        // is given rather than on the letters inside it.
        lineHeight = with(density) { (height / CapHeightRatio).toSp() },
        letterSpacing = (-0.03).em,
        maxLines = 1,
        style = LocalTextStyle.current.merge(
            TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        ),
    )
}

/**
 * Cap height over em, for Inter.
 *
 * The number a caller cares about is how tall the capital F is; the number the
 * text system takes is the em. Inter's capitals are 0.727 of the em, so a
 * caller asking for a 22dp letter asks the system for 30.3dp of type.
 */
private const val CapHeightRatio = 0.727f

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
    // The light on the plate, tinted by the accent rather than plain white:
    // what catches the edge is the app's own glow coming from behind.
    val glint = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.55f)
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
                    0f to glint.copy(alpha = 0.62f),
                    0.5f to glint.copy(alpha = 0.20f),
                    1f to glint.copy(alpha = 0.06f),
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
                        listOf(glint.copy(alpha = 0.34f), Color.Transparent),
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
