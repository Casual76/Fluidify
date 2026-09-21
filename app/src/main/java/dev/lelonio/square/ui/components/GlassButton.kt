package dev.lelonio.square.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.GlassTint
import dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface

/**
 * A button cut from the same glass as the bar.
 *
 * It replaces `LiquidButton`, and the reason is the one this whole pass is
 * about: the bar, the pill and the window are the engine's material now, and a
 * button beside them made of the vendored renderer reads as a thinner, harder
 * glass — which is exactly the complaint the old file's own comment was
 * answering, one renderer earlier.
 *
 * The signature is deliberately the old one minus the backdrop. The engine's
 * controls read the page they stand on from `LocalGlassBackdrop`, which this app
 * provides at the root and again inside the player, so every call site that used
 * to thread a backdrop through simply stops. What is kept is everything the old
 * one had learned and upstream had not: the height and the padding are the
 * caller's, because a 62 dp round button forced to 48 with its icon squeezed is
 * what happens when they are not.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * False for the things that are made of this material but are not buttons —
     * the app's own mark, a status tile. No press, no pull toward the finger.
     */
    isInteractive: Boolean = true,
    /** The content colour. Unspecified keeps whatever the surrounding text colour is. */
    tint: Color = Color.Unspecified,
    /** Lit, as an active chip is. Carried by the accent joining the material, not by a fill. */
    selected: Boolean = false,
    contentHeight: Dp = 48.dp,
    contentPadding: Dp = 16.dp,
    /**
     * Draws the material without sampling anything behind it.
     *
     * For the buttons that live in a scrolling page rather than in the chrome. A
     * pane that moves with the content re-photographs the screen on every frame
     * of every scroll, and the five filter chips at the top of the home page
     * cost 8 ms a frame between them — for a reflection of a page that is
     * usually flat colour behind them anyway.
     */
    flat: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // Whether this control is standing on a photograph. It decides the film
    // below and how much of the photograph is worth recording, which are the
    // two halves of the same question.
    val onPicture = dev.lelonio.square.ui.theme.LocalOnPicture.current
    val optics = remember(flat, onPicture) {
        val base = GlassDefaults.optics(GlassRole.Interactive)
        // Flat is not "no material": the film and the rim stay, so a chip in a
        // list still reads as the same glass as the chrome above it. What goes
        // is the one expensive part, which is the photograph.
        //
        // And where there is a photograph, it is recorded whole.
        //
        // Two fifths was chosen for a page, and it is right there: what a
        // control stands on in a page is an ambient wash, and a wash survives a
        // downsample untouched — the engine's own argument for compressing
        // content glass. A cover or a clip is the opposite case. It is all
        // structure, and structure is the only thing a lens has to bend: at two
        // fifths the transport's discs were refracting a thumbnail of a video
        // through a band a few pixels wide, which is a bend nobody can see. What
        // was left of the material was the film, and a film on its own is a
        // painted disc. The preset leaves this at one for this exact reason.
        when {
            flat -> base.copy(blurScale = 0f, backdropResolution = 0.4f)
            onPicture -> base
            else -> base.copy(backdropResolution = 0.4f)
        }
    }

    Row(
        modifier = modifier
            .height(contentHeight)
            .defaultMinSize(minWidth = contentHeight)
            .glassControlSurface(
                // Denser on a cover than on a page. See LocalOnPicture: a control's own film is
                // almost nothing by design, which is right on a bar and not enough on a picture.
                tint = if (onPicture) controlTintOnPhoto() else GlassDefaults.controlTint(),
                // The ambient canvas first, and it is not a preference. This
                // button lives *in* the page, and `LocalGlassBackdrop` is the
                // record of that page: a pane drawn inside the layer it samples
                // recurses on the render thread until the process dies. The
                // canvas is the ground under the page, recorded before it, so it
                // can never contain a pixel of this button.
                backdrop = LocalFluidCanvasBackdrop.current ?: currentGlassBackdrop(),
                shape = FluidCapsuleShape,
                optics = optics,
                selected = selected,
                interactive = isInteractive,
            )
            .then(
                if (isInteractive) {
                    Modifier.fluidPressable(onClick = onClick, interactionSource = interaction)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val contentTint = if (tint.isSpecified) tint else MaterialTheme.colorScheme.onSurface
        CompositionLocalProvider(LocalContentColor provides contentTint, content = { content() })
    }
}
/**
 * A control's film, on a photograph.
 *
 * The gap this fills: the engine's glass has a photo twin for the bar and one
 * for the floating family, and none for controls. A control asked for its film
 * over a cover and got the *floating* one, which is the pill's scrim — black at
 * very nearly half — and that is a scrim's weight for a reason: a pill carries
 * navigation over arbitrary content and has to win outright.
 *
 * A control is the other thing. `GlassDefaults.controlTint` calls its own film
 * "almost nothing" and says why: a button is a lens sitting on a surface that is
 * already glass, and a second opaque wash is what made the old ones read as grey
 * pills stuck onto a bar. Lending it the pill's scrim did that again, one
 * surface at a time and only over pictures — which is where it shows most,
 * because a picture is the one backdrop with enough structure for a lens to
 * bend. The player's transport is the clearest case: three discs over a video,
 * each one a flat black circle with a lit ring around it.
 *
 * So: the same argument as [GlassDefaults.darkBarTint] — fixed colours, because
 * a scrim has no palette to come from, and it darkens whatever is behind it — at
 * a control's weight rather than a pill's. The rim comes up as the film comes
 * down, and that trade is the whole point: with little film left, the edge is
 * what says the disc is made of anything at all.
 *
 * It belongs in `GlassDefaults` beside its two siblings, and it is here because
 * `engine/` is a submodule: a change made there and not released is a silent
 * variant that the next engine update deletes. Move it over when the engine is
 * next cut.
 */
@Composable
internal fun controlTintOnPhoto(): GlassTint =
    if (dev.lelonio.square.ui.theme.onDarkPage) {
        GlassTint(
            overlay = Color.Black.copy(alpha = 0.30f),
            fallback = Color(0xFF141416).copy(alpha = 0.95f),
            hairline = Color.White.copy(alpha = 0.22f),
        )
    } else {
        // The light twin, at the ratio the rest of this family uses: dark ink
        // needs more of a picture taken away than light ink does, because the
        // bright parts of a photograph are brighter than any wash lets them be.
        GlassTint(
            overlay = Color.White.copy(alpha = 0.40f),
            fallback = Color(0xFFFDFDFF).copy(alpha = 0.96f),
            hairline = Color.Black.copy(alpha = 0.26f),
        )
    }

/**
 * A *pane* standing on a photograph, as [controlTintOnPhoto] is for a control.
 *
 * Between the two things the engine does have. A pane carries writing, so it
 * cannot be as thin as a button; it is also not a bar, and the bar family's
 * scrim is what the panel's title card was wearing when it came out as a black
 * slab with a name printed on it -- a clip showing through at nothing, which is
 * the one thing a pane over a video is supposed to do.
 *
 * The weight is only half of the answer and the smaller half. What makes writing
 * survive a photograph is *frost*, not film: a blurred clip under a title is a
 * gradient, and a gradient is both easier to read against and still the clip. So
 * this is paired with an optic that actually frosts -- see the use site -- and
 * the film is turned down by the same step the frost is turned up.
 */
@Composable
internal fun paneTintOnPhoto(): GlassTint =
    if (dev.lelonio.square.ui.theme.onDarkPage) {
        GlassTint(
            overlay = Color.Black.copy(alpha = 0.38f),
            fallback = Color(0xFF141416).copy(alpha = 0.95f),
            hairline = Color.White.copy(alpha = 0.20f),
        )
    } else {
        GlassTint(
            overlay = Color.White.copy(alpha = 0.50f),
            fallback = Color(0xFFFDFDFF).copy(alpha = 0.96f),
            hairline = Color.Black.copy(alpha = 0.24f),
        )
    }

/**
 * The same glass, cut round, at whatever size the caller asks for.
 *
 * Lived in PlayerScreen while the player was the only screen with a transport.
 * The now-playing panel has one too, and two hand-rolled copies of a disc are
 * how the two ends of the same app stop matching — so it is here, beside the
 * button it is made of.
 *
 * It takes no backdrop, for the reason [GlassButton] gives: the engine's
 * controls find the page they stand on themselves, and the caller's job is to
 * provide the right one around them rather than to thread it through.
 */
@Composable
internal fun RoundGlassButton(
    size: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Draws the material without photographing anything; see [GlassButton.flat]. */
    flat: Boolean = false,
    /** False for a copy that is a picture of a control rather than a control. */
    isInteractive: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    GlassButton(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .size(size)
            // Dimmed rather than removed when there is nowhere to go: a control
            // that disappears makes the whole row jump.
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        isInteractive = isInteractive && enabled,
        contentHeight = size,
        contentPadding = 0.dp,
        flat = flat,
    ) {
        content()
    }
}
