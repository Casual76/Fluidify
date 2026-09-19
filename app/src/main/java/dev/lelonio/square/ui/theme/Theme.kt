package dev.lelonio.square.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode as EngineThemeMode
import dev.antigravity.fluidengine.ui.fluid.LocalFluidSurfaceSide
import dev.antigravity.fluidengine.ui.theme.AccentPoles
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.lelonio.square.SquareApplication
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.data.AppThemeMode

/**
 * Fluidify's own colour: amethyst, in a light and a dark cut.
 *
 * With poles, and they are not optional here: violet sits next to the palette's
 * indigo pole, and without them the derived secondary family collapses into the
 * primary — the exact case the engine's colour tests exercise under this name.
 */
/**
 * The app's own colour.
 *
 * Internal rather than private since the widget needs it too: the home screen and the app being the
 * same colour is not a coincidence to be arranged twice.
 */
internal val Amethyst = AccentPreset(
    name = "amethyst",
    label = "Ametista",
    light = Color(0xFF9966CC),
    dark = Color(0xFFB88CE8),
    poles = AccentPoles(
        secondaryLight = Color(0xFF007AFF),
        secondaryDark = Color(0xFF0A84FF),
        tertiaryLight = Color(0xFFFF2D55),
        tertiaryDark = Color(0xFFFF375F),
        secondaryBlend = 0.45f,
        tertiaryBlend = 0.40f,
    ),
)

/**
 * Which side the app is on, said plainly.
 *
 * Not read off the scheme, and that is the trap this exists to avoid: every
 * surface in this design is a translucent *film*, so `surface.luminance()` is
 * 1.0 in both settings — alpha is not part of luminance. Anything keyed on that
 * test called the dark theme light, which is the bug upstream found the same
 * way and the reason the engine grew `LocalFluidSurfaceSide` to be told instead.
 */
val LocalLightTheme = staticCompositionLocalOf { false }

/**
 * True when the dark side is standing on true black rather than on near-black.
 *
 * Only [pageFloor] and the backdrop's own veil ask: everything else is glass, and glass does not
 * care what is under it — which is exactly why this is one local and four colours rather than a
 * second theme. See [dev.lelonio.square.data.AppThemeMode.Amoled].
 */
val LocalAmoledTheme = staticCompositionLocalOf { false }

/**
 * A colour the page imposes on everything written on it.
 *
 * A record's page does not follow the phone. The artwork files a colour, the
 * page is drawn in it, and the ink is decided by *that* colour rather than by
 * the system setting — dark brown with white letters whichever way the phone is
 * set. Null means "follow the theme", which is what every ordinary page does.
 */
val LocalInkOverride = compositionLocalOf<Color?> { null }

/**
 * Glass over the album art.
 *
 * The page *is* the artwork, and what the system's setting decides is which way
 * it is taken: dark puts it down towards the floor with light ink and a sparse
 * white film on it, light takes it up towards paper with dark ink and a film
 * dense enough to read on. One design, read in two directions, rather than two
 * designs.
 *
 * The engine's [FluidTheme] provides the ladder — accent scale, continuous
 * shapes, the motion scheme, the typography — and what this adds on top is the
 * one thing this app's design insists on: the structural roles stay the
 * translucent films every screen here is drawn with, and `background` stays
 * transparent for the backdrop to show through.
 *
 * The accent is the app's own. It used to follow the playing artwork
 * everywhere; now amethyst is the identity — chrome, buttons, the light — and
 * the artwork's colour lives where the artwork is: pass [seed] and the content
 * is wrapped in [ArtworkAccentTheme], which the player and the detail pages do.
 */
@Composable
fun SquareTheme(
    /** Dominant colour of the content on screen, or null to stay amethyst. */
    seed: Color? = null,
    /**
     * Forces a side. Left alone it is the setting, and the setting is read
     * straight from the preferences rather than collected from a flow: the very
     * first composition has to be on the right side, or a cold start shows a
     * frame of the other one.
     */
    darkTheme: Boolean = rememberThemeIsDark(),
    /** True for the dark side standing on true black. See [LocalAmoledTheme]. */
    amoled: Boolean = rememberThemeIsAmoled(),
    /**
     * Whether this theme also claims the status and navigation bars.
     *
     * True for a theme that *is* the page. False for one applied to a patch of it — a header
     * standing on a cover, say, which is a dark page for the purpose of the controls on it and has
     * no business deciding what the clock looks like. Left true the innermost theme on screen wins
     * the window's glyphs, which for a patch is whichever patch happened to recompose last.
     */
    systemBars: Boolean = true,
    content: @Composable () -> Unit,
) {
    FluidTheme(
        settings = EngineSettings(
            themeMode = when {
                !darkTheme -> EngineThemeMode.LIGHT
                amoled -> EngineThemeMode.AMOLED
                else -> EngineThemeMode.DARK
            },
            accentMode = AccentMode.BRAND,
            dynamicColorEnabled = false,
        ),
        brand = Amethyst,
    ) {
        val engine = MaterialTheme.colorScheme
        // The ink and the film are the two things that change side, and both are
        // read back out of the scheme everywhere else in the app — `Ink` is the
        // scheme's own onSurface. So this is the only place that knows which way
        // round the app is.
        val ink = if (darkTheme) DarkInk else LightInk
        val film = if (darkTheme) DarkFilm else LightFilm
        val filmStrong = if (darkTheme) DarkFilmStrong else LightFilmStrong
        // Keyed on all of it: rebuilding a ColorScheme allocates dozens of
        // colours and invalidates every composable that reads MaterialTheme.
        val scheme = remember(engine, ink, film, filmStrong) {
            engine.copy(
                onPrimary = if (darkTheme) Color(0xFF0B0D10) else Color.White,
                primaryContainer = film,
                onPrimaryContainer = ink,
                secondary = ink.copy(alpha = 0.66f),
                // The backdrop shows through this; an opaque page colour would
                // cover it.
                background = Color.Transparent,
                onBackground = ink,
                // Glass, not a card: a translucent film is what every surface in
                // this design is made of, and the refraction on top comes from
                // the backdrop rather than from the colour.
                surface = film,
                onSurface = ink,
                surfaceVariant = filmStrong,
                onSurfaceVariant = ink.copy(alpha = 0.66f),
                outlineVariant = if (darkTheme) {
                    Color.White.copy(alpha = 0.16f)
                } else {
                    Color.Black.copy(alpha = 0.14f)
                },
            )
        }

        // System bar icons have to flip with the theme; left alone they are
        // drawn for the system's own theme and disappear against ours.
        val view = LocalView.current
        if (systemBars && !view.isInEditMode) {
            SideEffect {
                val window = (view.context as android.app.Activity).window
                // The bars sit over the artwork taken in whichever direction was
                // asked for, not over the system's idea of a background.
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }

        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(
                LocalLightTheme provides !darkTheme,
                LocalAmoledTheme provides (darkTheme && amoled),
                // Said out loud rather than guessed: the engine asks
                // `colorScheme.surface.luminance()`, and the surface this app
                // files there is a translucent film. Luminance ignores alpha, so
                // the answer came back "light" for the blackest app in the
                // house, and every pane of engine glass took the bright branch at
                // once.
                LocalFluidSurfaceSide provides darkTheme,
            ) {
                if (seed == null) content() else ArtworkAccentTheme(seed, content)
            }
        }
    }
}

/**
 * The setting, resolved to a side.
 *
 * Read once through [dev.lelonio.square.data.PreferencesStore.readThemeMode] and
 * then followed as a flow: the straight read is what makes the first frame right,
 * the flow is what makes the setting take effect without leaving the screen.
 */
@Composable
fun rememberThemeIsAmoled(): Boolean {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as SquareApplication).preferences
    }
    val mode by store.themeMode.collectAsStateWithLifecycle(
        initialValue = remember(store) { store.readThemeMode() },
    )
    return mode == AppThemeMode.Amoled
}

@Composable
fun rememberThemeIsDark(): Boolean {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as SquareApplication).preferences
    }
    val mode by store.themeMode.collectAsStateWithLifecycle(
        initialValue = remember(store) { store.readThemeMode() },
    )
    val systemDark = isSystemInDarkTheme()
    return when (mode) {
        AppThemeMode.Light -> false
        AppThemeMode.Dark, AppThemeMode.Amoled -> true
        AppThemeMode.System -> systemDark
    }
}

/**
 * The playing artwork's colour, where the artwork itself is on screen.
 *
 * A wrapper rather than a different theme: everything but `primary` stays what
 * [SquareTheme] built, so the player and the detail pages keep the app's glass
 * and type while their accent follows the cover — animated, so a track change
 * slides the colour across instead of snapping, which otherwise reads as a
 * glitch when artwork loads a beat late.
 */
@Composable
fun ArtworkAccentTheme(
    /** Null before the artwork has said anything; amethyst holds the fort. */
    seed: Color?,
    content: @Composable () -> Unit,
) {
    // Pushed toward legibility rather than used raw: a cover's dominant colour
    // is as likely to be near-black as near-white, and either one vanishes
    // against the wrong background — which way it has to be pushed depends on
    // which background it is about to vanish against.
    val light = LocalLightTheme.current
    val accent = when {
        seed == null -> MaterialTheme.colorScheme.primary
        light -> seed.deepenFor(LightBase)
        else -> seed.liftFor(DarkBase)
    }
    val animatedAccent by animateColorAsState(accent, tween(600), label = "accent")

    val base = MaterialTheme.colorScheme
    val scheme = remember(base, animatedAccent) {
        base.copy(primary = animatedAccent)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val DarkBase = Color(0xFF0D0E11)
private val LightBase = Color(0xFFF1F2F6)

/**
 * The film every glass surface is tinted with.
 *
 * White both times, and what changes is how much of the page is left showing
 * through: a tenth over a dark page, better than half over a light one. The
 * material is the same material — a bright, reflective film — and a light page
 * simply needs more of it before text stops competing with the artwork behind.
 */
private val DarkFilm = Color.White.copy(alpha = 0.10f)
private val DarkFilmStrong = Color.White.copy(alpha = 0.16f)
private val LightFilm = Color.White.copy(alpha = 0.55f)
private val LightFilmStrong = Color.White.copy(alpha = 0.72f)

/**
 * A film of this app's material, at the weight the dark side asks for.
 *
 * There are a dozen films scattered through the screens — a chip, a sheet, a
 * menu, the selected row of a list — each written out as a white at some small
 * alpha. They are all the same material at different weights, and every one of
 * them disappears on a light page, because a white at eight percent over paper
 * is paper.
 *
 * So the weight is kept and a floor is added under it. That is not a fudge: on a
 * dark page the film's whole job is to *lift* a surface off the floor, and a
 * little goes a long way; on a light page it has to hold ink against a
 * photograph, and it cannot start from nothing. The order survives — a heavier
 * film is still heavier than a lighter one — which is the part the screens
 * actually depend on.
 */
@Composable
fun glassFilm(darkAlpha: Float): Color = if (onDarkPage) {
    Color.White.copy(alpha = darkAlpha)
} else {
    Color.White.copy(alpha = (LightFilmFloor + darkAlpha).coerceAtMost(0.92f))
}

/**
 * The hairline that ends a pane.
 *
 * Not the same trick as [glassFilm], and it cannot be: an edge works by being
 * *unlike* the page. A white rim catches the light on a dark page and vanishes
 * on a light one, so on the light side it turns over and becomes a dark line —
 * quieter than its white twin, because a dark line on paper is read at a lower
 * contrast than a light line in the dark.
 *
 * Asks the ink and not the theme, for [InkInverse]'s reason: a record's page
 * paints itself and imposes its own ink, so a phone set to light can be showing
 * a pane that stands on a dark cover. The rim has to turn over with the page it
 * is drawn on, not with the setting.
 */
@Composable
fun glassEdge(darkAlpha: Float): Color = if (onDarkPage) {
    Color.White.copy(alpha = darkAlpha)
} else {
    Color.Black.copy(alpha = darkAlpha * 0.55f)
}

/**
 * The top bar of a page that is a picture, on whichever side that page is.
 *
 * Both arms are scrims now, and the second one is new. The first version of this
 * picked the engine's dark scrim on a dark page and the *family* tint on a light
 * one, on the argument that the family's film already lightens and so is already
 * pointing the right way on paper. That argument is true and insufficient: the
 * family film is built for a flat page, and a bar over a cover has two failures
 * it does not cover.
 *
 * Over a bright cover it lands within a percent of the page and the bar is gone.
 * Over a dark one it lands at a middling grey and the dark letters on it are the
 * hardest thing in the app to read — which is most of what made the light theme
 * impractical, and it is visible in one frame on the bench under `Mosaico`,
 * beside a dark column whose scrim holds its text across a white tile and a
 * black one.
 *
 * So the light arm is a scrim too, only a white one: see `lightBarTint`. Not a
 * darkening of paper — that really would be a grey slab — but more white than
 * the family lays down, which is what dark ink needs before a photograph stops
 * swallowing it.
 *
 * Asks the ink rather than the theme, for [glassEdge]'s reason, and that is also
 * why it picks the arms by hand instead of calling the engine's own
 * `barTintOnPhoto()`: that one asks the theme, which is the right question for
 * an app without a page that paints itself, and the wrong one here.
 *
 * Sibling of `rememberPillMorphTint`, which does the same for the floating
 * family. The two move together or the morph builds its own seam.
 */
@Composable
fun pageBarTint(): dev.antigravity.fluidengine.ui.fluid.GlassTint =
    if (onDarkPage) {
        dev.antigravity.fluidengine.ui.fluid.GlassDefaults.darkBarTint()
    } else {
        dev.antigravity.fluidengine.ui.fluid.GlassDefaults.lightBarTint()
    }

/**
 * A wash laid over a picture, at the weight the side it is on asks for.
 *
 * [darkAlpha] is how much black the dark side wants. The light side gets white
 * instead, and more of it, for the reason the app's own backdrop veil gives:
 * the bright parts of a photograph are far brighter than a dark wash ever lets
 * them be, so dark ink needs more of the cover taken away than light ink does.
 *
 * Not the same thing as the scrim behind a dialog, which darkens on both sides
 * because its job is to push a page away rather than to carry ink.
 */
@Composable
fun pageWash(darkAlpha: Float): Color = if (onDarkPage) {
    Color.Black.copy(alpha = darkAlpha)
} else {
    Color.White.copy(alpha = (darkAlpha * 1.35f).coerceAtMost(0.92f))
}

/**
 * Which side the page under your finger is on.
 *
 * Derived from the ink rather than from the theme setting, and that is the whole
 * point of it — the same argument [InkInverse] makes. A record's page paints
 * itself and imposes its own ink, so a phone set to light can be showing a page
 * that is, for every purpose a surface has, a dark one. Ask the setting and half
 * the answers are wrong exactly where it matters most.
 *
 * It is the predicate behind [glassEdge], [pageWash] and [pageBarTint], and it
 * is spelled out once so that those three cannot drift apart.
 */
val onDarkPage: Boolean
    @Composable get() = Ink.luminance() > 0.5f

/** How much white a film starts from before its own weight is added. See [glassFilm]. */
private const val LightFilmFloor = 0.45f

/** Text and icons, on whichever side of the page they have to be read from. */
private val DarkInk = Color(0xFFF7F8FA)
private val LightInk = Color(0xFF15161A)

/**
 * The colour text and icons are drawn in.
 *
 * A composable getter rather than a constant, so the hundred-odd places that
 * name it did not have to learn about the theme: it is the scheme's own
 * `onSurface`, unless the page has imposed a colour of its own — see
 * [LocalInkOverride], which is how a record's page keeps its letters white on a
 * phone set to light.
 */
val Ink: Color
    @Composable get() = LocalInkOverride.current ?: MaterialTheme.colorScheme.onSurface

/**
 * How much room at the end of a page is already spoken for.
 *
 * The now-playing panel, on a window wide enough to have one. It is handed to
 * the *rows* — the carousels, the grids, the lists — rather than to the page,
 * and that distinction is the whole reason it exists as a local instead of as a
 * margin on the page's container.
 *
 * A margin on the container is what the first tablet build used, and it grew a
 * stripe of unveiled backdrop down the side with a visible step where the
 * header's veil ran out: that veil is a rectangle drawn *inside* the page, so a
 * page narrower than the window leaves the window's edges bare. The page has to
 * go on being the width of the window and carry on under the panel, the way it
 * already carries on under the bar. Only what you are meant to reach stops
 * short.
 */
val LocalPageEndInset = staticCompositionLocalOf { 0.dp }

/**
 * The colour a page ends on, where nothing else has been painted.
 *
 * Near-black one way and paper the other. It is what shows through wherever the
 * artwork does not reach, so it has to be the page's own floor and not a
 * neutral: a black border round a light page is the one thing that would give
 * the whole arrangement away as a dark design wearing light colours.
 *
 * One definition, because it was three — in the app's backdrop, in a record's
 * page, and (as a single dark constant) in the first-run tutorial, which is how
 * the tutorial ended up being the one page that ignored the theme setting it was
 * about to offer.
 */
val pageFloor: Color
    @Composable get() = when {
        LocalLightTheme.current -> PageFloorLight
        LocalAmoledTheme.current -> Color.Black
        else -> PageFloorDark
    }

private val PageFloorDark = Color(0xFF0A0A0C)
private val PageFloorLight = Color(0xFFF1F2F6)

/**
 * The ink a given colour can be read against.
 *
 * For the pages that carry a colour of their own — a record, an artist — where
 * the question is not what the phone was set to but what this page happens to be
 * painted. Feed it to [LocalInkOverride] and everything written on the page
 * follows, including the hundred places that only ever say [Ink].
 *
 * The threshold is deliberately below the middle: a mid-tone reads better under
 * white letters than under black ones, because white on colour keeps its weight
 * and black on colour loses it.
 */
fun inkOn(background: Color): Color =
    if (background.luminance() > 0.42f) LightInk else DarkInk

/**
 * A page that paints its own ground, and therefore has to say so.
 *
 * A record's header is the case: a cover is full-bleed and unveiled behind it, so the side that
 * page is on is decided by the cover and not by the phone. [inkOn] has always answered that for the
 * *letters*, through [LocalInkOverride] — and that was only ever half an answer, because this app
 * draws its glass with two renderers and only one of them was being told.
 *
 * The app's own helpers all descend from [onDarkPage], which reads the ink and therefore followed.
 * Everything the engine draws — a `GlassButton`, a `FluidSwitch`, the rim and the tint inside
 * `glassSurface` — asks `LocalFluidSurfaceSide`, which SquareTheme sets from the *setting*. So with
 * a phone on the light side and a dark cover on screen, two controls a finger apart were on
 * opposite sides: one with a white rim over the cover, the other with a black one, in the same
 * header.
 *
 * Hence one door for both. Taking the ground rather than the ink is deliberate — a caller says what
 * it painted, which it knows, instead of working out a consequence it can get backwards — and the
 * side is then derived from the ink, so the two are the same answer by construction.
 */
@Composable
fun SelfPaintedPage(ground: Color, content: @Composable () -> Unit) {
    val ink = inkOn(ground)
    // Read off the ink rather than off `ground`, so this cannot disagree with `onDarkPage`, which
    // is the predicate every app-side helper below is about to ask.
    val dark = ink.luminance() > 0.5f
    // The whole side, and the first version of this gave two thirds of it.
    //
    // Providing the ink and `LocalFluidSurfaceSide` looks like enough and is not. That local flips
    // which *branch* the engine's tints take; the colours those branches mix from come out of the
    // ColorScheme, which was still the phone's. So `controlTint()` took its dark branch and stirred
    // it into a light palette, and the back arrow on a dark cover came out a pale disc with a white
    // glyph on it — the one arrangement worse than the bug being fixed.
    //
    // Hence the theme, which is the one thing in this app that knows how to build a side. Seeded
    // with the ground because an accent is derived for the side it will be read on, and this page's
    // ground *is* its accent. Never AMOLED: true black is a page with the light off, and this one
    // has a photograph on it. And it does not touch the window's bars — see `systemBars`.
    SquareTheme(seed = ground, darkTheme = dark, amoled = false, systemBars = false) {
        CompositionLocalProvider(
            LocalInkOverride provides ink,
            LocalOnPicture provides true,
            content = content,
        )
    }
}

/**
 * True for the controls standing directly on a picture.
 *
 * The side is not the whole question. A control's own film is *almost nothing* by design — a
 * button is a lens sitting on a surface that is already glass, and a second opaque wash there is
 * what made the old ones read as grey pills stuck onto a bar. That reasoning holds on a bar and
 * collapses on a cover: there the ground is the loudest thing on screen and a third of a film does
 * not stand between it and a label. A back arrow on a full-bleed sleeve came out a pale disc with
 * a white glyph on it, at a contrast of two to one, *after* the side had been correctly turned
 * over — which is how it became clear that the side was not the only thing being asked wrongly.
 *
 * So the ground says what it is, once, and the controls on it take the tint meant for a
 * photograph. Set by [SelfPaintedPage] and by nothing else: a page that has not said it is a
 * picture is a page with a quiet ground, which is what the default is for.
 */
val LocalOnPicture = staticCompositionLocalOf { false }

/**
 * Ink's opposite, for the one control that inverts.
 *
 * A "following" chip, a filled play button: the background becomes the ink and
 * the letters have to become the page. Written as a pair of constants at each
 * site it was a guess about which side the app was on, and half of those
 * guesses are wrong the moment there are two sides.
 *
 * Derived from [Ink] rather than from the theme, and that is the whole point of
 * it: inside a page that has imposed its own ink — a record's header, where the
 * letters are white because the cover is dark — the opposite has to be the
 * opposite of *that*. Asking the theme instead produced a white button with
 * white letters on it, which is exactly the shape of the bug this is here to
 * prevent.
 */
val InkInverse: Color
    @Composable get() = if (onDarkPage) LightInk else DarkInk

/** The same, for what is said quietly. */
val InkDim: Color
    @Composable get() = Ink.copy(alpha = 0.66f)

/** Brighten until the colour reads against a near-black background. */
private fun Color.liftFor(background: Color): Color {
    val floor = background.luminance() + 0.16f
    if (luminance() >= floor) return this
    val amount = ((floor - luminance()) * 2f).coerceIn(0f, 1f)
    return Color(
        red = red + (1f - red) * amount,
        green = green + (1f - green) * amount,
        blue = blue + (1f - blue) * amount,
    )
}

/**
 * Darken until the colour reads against a near-white background.
 *
 * Taken a long way further than [liftFor] goes the other direction, and that is
 * not symmetry for its own sake: the accent is spent on exactly the things that
 * have to be found at a glance — the lit tab, the play button, the row that is
 * playing — so "technically darker than the page" is not enough for it. At a
 * gentler ceiling a mid-tan came out of here barely touched.
 */
private fun Color.deepenFor(background: Color): Color {
    val ceiling = background.luminance() - 0.62f
    if (luminance() <= ceiling) return this
    val amount = ((luminance() - ceiling) * 1.6f).coerceIn(0f, 0.86f)
    return Color(
        red = red * (1f - amount),
        green = green * (1f - amount),
        blue = blue * (1f - amount),
    )
}

/**
 * The wide, soft, low-opacity drop shadow this design leans on.
 *
 * Elevation is the only depth cue left once colour is gone, so it does the work
 * that an accent used to: cards, covers and the mini player read as separate
 * layers rather than as regions of one flat sheet. Kept very diffuse — a tight
 * shadow at this size looks like a border and flattens the effect.
 *
 * Lighter on a light page. The same black at the same alpha that reads as a soft
 * lift over a near-black floor reads as dirt under a card on paper, because
 * there the shadow is the darkest thing on the screen instead of one of the
 * lightest.
 */
@Composable
fun Modifier.softShadow(
    shape: Shape,
    elevation: Dp = 18.dp,
    ambient: Float = 0.10f,
    spot: Float = 0.13f,
): Modifier {
    val light = LocalLightTheme.current
    val scale = if (light) 0.55f else 1f
    return shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = ambient * scale),
        spotColor = Color.Black.copy(alpha = spot * scale),
    )
}
