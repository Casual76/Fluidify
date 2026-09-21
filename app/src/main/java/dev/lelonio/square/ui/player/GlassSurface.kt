package dev.lelonio.square.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.liquidGlass
import dev.antigravity.fluidengine.ui.fluid.glassSurface

/**
 * A pane of glass over [backdrop].
 *
 * One call for every glass surface outside the bottom bar: the sheets, the
 * menus, the cards a link opens, the player's own panels. What it is made of is
 * not decided here any more — it defers to the bar's [liquidGlass], so there is
 * a single answer in the app to what glass looks like, and changing it changes
 * everything at once.
 *
 * Everything degrades on its own below Android 13: the library checks for
 * RuntimeShader support and skips the refraction, leaving the blur, and below
 * Android 12 the blur goes too. What is left is a plain translucent surface, so
 * the screen stays usable rather than breaking on older phones.
 */
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

@Composable
fun GlassSurface(
    backdrop: Backdrop,
    shape: Shape,
    modifier: Modifier = Modifier,
    /**
     * How thick this pane is, as a multiple of the app's own frost.
     *
     * The one thing a caller still decides, and it is a ratio rather than a
     * number of dp on purpose: a panel covering half the screen has to hide what
     * is under it in a way a pill the size of a word does not, but both have to
     * answer the setting. Fixed dp here is what made the player's surfaces stop
     * listening to it — a hardcoded 8 next to a bar on 2 is a different
     * material, whatever the slider says.
     */
    blurScale: Float = 1f,
    /**
     * The film, for the surfaces that do not get real glass.
     *
     * Where they do, the film comes from the shared recipe with everything else
     * about the material. This is what an unsupported device, a shape the
     * refraction cannot follow, or a moving player falls back to.
     */
    surfaceColor: Color = Color.Unspecified,
    /**
     * Whether this pane is standing on a picture rather than on a page.
     *
     * A lambda, and read *here* rather than at the call site, and that is the
     * whole of why it exists. A pane deep inside the player sits in a scope that
     * has already settled by the time a Canvas renders its first frame, so the
     * same answer handed down as a value never arrived — it was correct where it
     * was computed and stale where it was needed. Reading the state inside this
     * composable subscribes *this* pane to it, which is a subscription nobody
     * above has to remember to make.
     *
     * See [dev.lelonio.square.ui.theme.LocalOnPicture], which is the engine's
     * half of the same sentence for its own controls.
     */
    onPicture: (() -> Boolean)? = null,
    content: @Composable () -> Unit,
) {
    // The same material the bottom bar is made of, so one app has one glass.
    //
    // The bar came from elsewhere and brought its own recipe with it, and next
    // to it every other pane in this app read as a different substance: a
    // different saturation, a different bend at the edge, and a thin white film
    // where the bar has a lit grey one. Asking both recipes to agree by hand was
    // the previous attempt and it changed nothing anybody could see, so there is
    // only one recipe now — [Modifier.liquidGlass], the bar's own — and the
    // parameters below are what a pane is still allowed to choose.
    //
    // Only how thick it is, in other words. How far it blurs is a real
    // difference between a small pill and a panel covering half the screen; what
    // the material *is* is not.
    val config = dev.lelonio.square.ui.glass.LocalGlassEffectConfig.current
    // The dark-page film, named out loud, for a pane on a clip. Not a darker
    // one: what was wanted here is the material the app already wears in the
    // dark — the mini player's glass — and not a slab painted over it.
    val film = if (onPicture?.invoke() == true) {
        dev.lelonio.square.ui.glass.DefaultSurfaceTint
    } else {
        Color.Unspecified
    }
    // The refraction is part of that recipe now, and it is the reason this takes
    // a shape it can bend light along. Anything else keeps the film below.
    val blurDp = (config.blurRadius * blurScale).coerceAtLeast(0f)
    val cornerShape = shape as? CornerBasedShape
    if (cornerShape == null) {
        Box(
            modifier.background(
                color = if (surfaceColor.isSpecified) surfaceColor else FallbackFilm,
                shape = shape,
            ),
        ) {
            content()
        }
        return
    }

    // Held rather than dropped while the player travels; see LocalGlassEnabled.
    val moving = !LocalGlassEnabled.current

    Box(
        modifier.liquidGlass(
            config = config,
            shape = cornerShape,
            blurRadiusDp = blurDp,
            frozen = { moving },
            // The bar's rim, not the library's default: the app's chrome is one
            // material and the edge light is most of what says so.
            highlightAlpha = BarHighlightAlpha,
            // Panes sit over their own backdrop: the player's is the artwork
            // behind it rather than the page under that.
            ownBackdrop = backdrop,
            filmOverride = film,
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

/**
 * The rim every surface in this app is lit with.
 *
 * The bottom bar picked this when it arrived and everything else kept the
 * library's brighter default, which is why the player read as a different
 * material even once the rest of the recipe was shared.
 */
internal const val BarHighlightAlpha = 0.3f

/** What a pane looks like with the refraction switched off. */
private val FallbackFilm: androidx.compose.ui.graphics.Color
    @androidx.compose.runtime.Composable get() =
        dev.lelonio.square.ui.theme.glassFilm(0.12f)
