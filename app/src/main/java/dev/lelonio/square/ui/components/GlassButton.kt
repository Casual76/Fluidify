package dev.lelonio.square.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
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
    val optics = remember(flat) {
        val base = GlassDefaults.optics(GlassRole.Interactive)
        // Flat is not "no material": the film and the rim stay, so a chip in a
        // list still reads as the same glass as the chrome above it. What goes
        // is the one expensive part, which is the photograph.
        if (flat) base.copy(blurScale = 0f, backdropResolution = 0.4f) else base.copy(backdropResolution = 0.4f)
    }

    Row(
        modifier = modifier
            .height(contentHeight)
            .defaultMinSize(minWidth = contentHeight)
            .glassControlSurface(
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
