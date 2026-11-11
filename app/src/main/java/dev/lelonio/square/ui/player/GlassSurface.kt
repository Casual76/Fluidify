package dev.lelonio.square.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource

/** Record the stage's visible effects, excluding its sibling chrome. Order matters:
 * the engine source records modifiers after it, so the effect must be inside. */
internal fun Modifier.playerStageSource(
    backdrop: GlassBackdropState,
    frozen: () -> Boolean = { false },
    effects: GraphicsLayerScope.() -> Unit,
): Modifier = glassBackdropSource(backdrop, frozen).graphicsLayer(effects)

/**
 * Whether panes refresh what they are reflecting, or hold the last look at it.
 *
 * False while the player is in motion. A pane's blur and lens are RuntimeShaders
 * sampling a recorded layer, and during the expansion that layer changes every
 * frame — several full-screen shader passes per frame, which is most of what
 * made the animation stutter.
 *
 * It used to mean "no glass at all", and the pane fell back to a plain film for
 * the length of the travel. That saved the same work and cost a visible one:
 * this is the only surface in the player that answers the flag, so the title
 * capsule alone arrived as a flat grey patch among panes of glass and turned
 * into glass a frame after the player stopped. Holding the recording gets the
 * saving without the change of material — what is behind the player barely
 * moves anyway, being the artwork it is opening over.
 */
val LocalGlassEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }

/**
 * A pane of glass over [backdrop]: the notice that drops from the top when the
 * Web API session has expired.
 *
 * It was the one call for every pane outside the bottom bar -- sheets, menus,
 * cards -- in the app's own glass. Those are the engine's modals now, and this
 * is the engine's panel material for the one pane the app still places itself.
 */
@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    /** What the pane refracts; the page under it unless told otherwise. */
    backdrop: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState =
        dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop(),
    content: @Composable () -> Unit,
) {
    // The engine's floating panel, and nothing of its own: the material every
    // pop-up, sheet, notice and menu in the family is made of (panelOptics /
    // panelTint), with the frost for a pane that stands over a page nothing has
    // blurred. Until 3.0 this deferred to the app's own renderer, the bar's
    // vendored recipe; that renderer is gone and the app has one glass.
    Box(
        modifier.glassSurface(
            state = backdrop,
            tint = dev.antigravity.fluidengine.ui.fluid.GlassDefaults.panelTint(),
            shape = shape,
            role = dev.antigravity.fluidengine.ui.fluid.GlassRole.Floating,
            optics = dev.antigravity.fluidengine.ui.fluid.GlassDefaults.panelOptics(overSharpPage = true),
        ),
    ) {
        content()
    }
}

/**
 * A pane of the player's chrome, cut from the engine's glass.
 *
 * Why this exists rather than another set of numbers on [GlassSurface]: the
 * player had two materials in it and they were a centimetre apart. The discs,
 * the small round actions and the button that offers the video are the engine's
 * -- they came over when the bar did -- and the capsule carrying the title, the
 * one carrying the line being sung and the badge under the words were still the
 * vendored renderer's. Side by side in the same row the difference is not
 * subtle: the engine's controls have an edge that bends what is behind them and
 * a rim that catches the light, and the vendored panes beside them were a milky
 * film with a faint line along the top.
 *
 * No numbers of its own, deliberately. It asks the engine for the floating
 * family -- the one the bar, the pill and the window already wear -- and the one
 * thing it decides is the film, because a pane standing on a clip is on a dark
 * floor whatever side the phone is on. Everything else about the material comes
 * from one place, which is the whole point of having moved.
 *
 * It samples what the controls sample. That is not a detail either: a pane and a
 * button on it that refract two different pictures are two materials again, in a
 * subtler way.
 */
@Composable
fun PlayerPane(
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    /**
     * Whether this pane is standing on a picture rather than on a page.
     *
     * A lambda, and read *here* rather than at the call site, for the reason
     * [GlassSurface] gives: a pane deep inside the player sits in a scope that
     * has already settled by the time a Canvas renders its first frame.
     */
    onPicture: (() -> Boolean)? = null,
    content: @Composable () -> Unit,
) {
    val canvas = dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop.current
        ?: dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop()
    val tint = if (onPicture?.invoke() == true) {
        dev.lelonio.square.ui.components.paneTintOnPhoto()
    } else {
        dev.antigravity.fluidengine.ui.fluid.GlassDefaults.floatingTint()
    }
    Box(
        modifier.then(
            Modifier.glassSurface(
                state = canvas,
                tint = tint,
                shape = shape,
                role = dev.antigravity.fluidengine.ui.fluid.GlassRole.Floating,
            ),
        ),
    ) {
        content()
    }
}

/**
 * A control of the player's, cut from the same glass as its buttons.
 *
 * The sibling of [PlayerPane], for the two round controls that stand beside the
 * words -- the karaoke dial and the translation toggle. They were the vendored
 * renderer's while the discs an inch away were the engine's, which is the same
 * mismatch and the same fix.
 *
 * The control family rather than the floating one: a button is a lens and its
 * film is almost nothing by design, which is what lets a label sit on it without
 * the control turning into a pill stuck onto the page.
 */
@Composable
fun Modifier.playerControlGlass(
    shape: androidx.compose.ui.graphics.Shape,
): Modifier {
    val canvas = dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop.current
        ?: dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop()
    val defaults = dev.antigravity.fluidengine.ui.fluid.GlassDefaults
    val onPicture = dev.lelonio.square.ui.theme.LocalOnPicture.current
    return this.then(
        Modifier.glassSurface(
            state = canvas,
            tint = if (onPicture) {
                dev.lelonio.square.ui.components.controlTintOnPhoto()
            } else {
                defaults.controlTint()
            },
            shape = shape,
            role = dev.antigravity.fluidengine.ui.fluid.GlassRole.Interactive,
        ),
    )
}
