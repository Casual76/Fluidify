package dev.lelonio.square.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidSliderRow
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import kotlin.math.roundToInt

/**
 * The glass, and the part of it that is still the listener's to set.
 *
 * Until 1.7.5 this page tuned the app's own glass renderer: three profiles and
 * thirteen knobs (colour, frost, edge depth, bend, edge light, film, colour
 * splitting, depth, and where the glass was allowed to be). With engine 3.0 the
 * app has one glass, the engine's, and the engine's glass has one recipe per
 * role rather than numbers to move, so most of those knobs had nothing left to
 * turn. They are not deleted: what was set is still stored (see GlassStore) and
 * the choice of what this page becomes is Alessio's. What is left here is what
 * still changes something on screen:
 *
 * - the frost of the bottom bar, which is the bar's own base radius;
 * - the wash under the selected tab;
 * - whether the player's Canvas plays behind its glass;
 * - whether the bar folds away as you scroll.
 */
@Composable
fun GlassSection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as SquareApplication).glass
    }
    val config by store.config.collectAsStateWithLifecycle()
    val folds by store.barFolds.collectAsStateWithLifecycle()

    Section(stringResource(R.string.glass)) {
        // The bar's base radius: the engine multiplies it for the role, so the
        // useful range is short. Above a few dp the bar shows the average of
        // what is behind it rather than the thing itself, which is a fill.
        FluidSliderRow(
            title = stringResource(R.string.glass_blur),
            value = config.blurRadius.coerceIn(0f, MaxBarFrost),
            onValueChange = store::setBlur,
            valueRange = 0f..MaxBarFrost,
            valueLabel = "${config.blurRadius.coerceIn(0f, MaxBarFrost).roundToInt()} dp",
        )
        RowDivider()
        FluidSliderRow(
            title = stringResource(R.string.glass_indicator),
            value = config.puckOpacity,
            onValueChange = store::setPuckOpacity,
            valueRange = 0f..1f,
            valueLabel = "${(config.puckOpacity * 100).roundToInt()}%",
        )
        RowDivider()
        SwitchRow(
            label = stringResource(R.string.glass_where_player),
            note = null,
            checked = config.playerEnabled,
            onCheckedChange = store::setPlayerEnabled,
        )
        RowDivider()
        SwitchRow(
            label = stringResource(R.string.bar_folds),
            note = stringResource(R.string.bar_folds_note),
            checked = folds,
            onCheckedChange = store::setBarFolds,
        )
    }
}

/** The most frost the bar is offered, as its base radius in dp. */
private const val MaxBarFrost = 8f
