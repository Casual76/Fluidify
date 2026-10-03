package dev.pampa.fluidify.wear.system

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.layout.androidImageResource
import androidx.wear.protolayout.layout.basicImage
import androidx.wear.protolayout.layout.box
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.layout.imageResource
import androidx.wear.protolayout.layout.inlineImageResource
import androidx.wear.protolayout.layout.row
import androidx.wear.protolayout.layout.spacer
import androidx.wear.protolayout.material3.ButtonDefaults.filledButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconButton
import androidx.wear.protolayout.material3.iconEdgeButton
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.background
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.clip
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.dp
import androidx.wear.protolayout.types.layoutString
import dev.antigravity.fluidengine.ui.theme.fluidColorScheme
import dev.antigravity.fluidengine.wear.theme.FluidWearDefaults
import dev.antigravity.fluidengine.wear.theme.fluidWearColorScheme
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.playback.NowPlaying
import dev.pampa.fluidify.wear.ui.theme.FluidifyWearBrand

/**
 * What the tile shows, read from the same state the player reads.
 *
 * Built from [NowPlaying] rather than the phone's last snapshot alone, so a
 * press on the tile shows its result at once (the remote's guess) instead of
 * after the round trip to the phone.
 */
data class PlayerTileModel(
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val liked: Boolean?,
    val trackUri: String?,
    val coverKey: String?,
) {
    val isEmpty: Boolean get() = trackUri == null

    companion object {
        fun from(now: NowPlaying): PlayerTileModel {
            val snapshot = now.snapshot
            val track = snapshot?.track
            return PlayerTileModel(
                title = track?.title.orEmpty(),
                artist = track?.artist.orEmpty(),
                isPlaying = snapshot != null && (snapshot.isPlaying || snapshot.playWhenReady),
                liked = snapshot?.liked,
                trackUri = track?.uri,
                coverKey = track?.artKey,
            )
        }
    }
}

/** The tile's presses, handed in so a test can lay the tile out without a service. */
interface PlayerTileClicks {
    val open: Clickable
    val previous: Clickable
    val toggle: Clickable
    val next: Clickable
    val like: Clickable
    val resume: Clickable
}

/**
 * The player as a tile, after the Spotify watch app's: the song, the artist,
 * the three transport buttons, and the heart on the bottom edge.
 *
 * The play button is the cover itself, dimmed under the play or pause mark:
 * a tile cannot draw the glass, but it can keep the cover as the thing you
 * press, which is what the player does. Without a cover yet it is a plain
 * filled button in the brand colour.
 */
fun MaterialScope.playerTileLayout(
    model: PlayerTileModel,
    clicks: PlayerTileClicks,
    cover: ByteArray?,
    coverSizePx: Int = COVER_PX,
): LayoutElement {
    if (model.isEmpty) return emptyTileLayout(clicks)
    val playLabel = context.getString(if (model.isPlaying) R.string.pause else R.string.play)
    return primaryLayout(
        titleSlot = { text(model.title.layoutString, maxLines = 1) },
        mainSlot = {
            column(
                text(
                    model.artist.layoutString,
                    typography = Typography.BODY_SMALL,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                ),
                spacer(height = ROW_GAP_DP.dp),
                row(
                    transportButton(clicks.previous, R.drawable.ic_tile_previous, context.getString(R.string.previous)),
                    spacer(width = BUTTON_GAP_DP.dp),
                    centreButton(model, clicks.toggle, cover, coverSizePx, playLabel),
                    spacer(width = BUTTON_GAP_DP.dp),
                    transportButton(clicks.next, R.drawable.ic_tile_next, context.getString(R.string.next)),
                    verticalAlignment = VERTICAL_ALIGN_CENTER,
                ),
                horizontalAlignment = HORIZONTAL_ALIGN_CENTER,
            )
        },
        bottomSlot = {
            val liked = model.liked == true
            iconEdgeButton(
                onClick = clicks.like,
                colors = if (liked) filledButtonColors() else filledTonalButtonColors(),
                modifier = LayoutModifier.contentDescription(
                    context.getString(if (liked) R.string.unlike else R.string.like),
                ),
            ) {
                icon(
                    imageResource(androidImageResource(if (liked) R.drawable.ic_tile_heart_fill else R.drawable.ic_tile_heart)),
                    protoLayoutResourceId = if (liked) "heart_fill" else "heart",
                )
            }
        },
        onClick = clicks.open,
    )
}

private fun MaterialScope.emptyTileLayout(clicks: PlayerTileClicks): LayoutElement = primaryLayout(
    titleSlot = { text(context.getString(R.string.app_name).layoutString) },
    mainSlot = {
        text(
            context.getString(R.string.nothing_playing).layoutString,
            typography = Typography.BODY_MEDIUM,
            color = colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    },
    bottomSlot = {
        textEdgeButton(onClick = clicks.resume) { text(context.getString(R.string.resume).layoutString) }
    },
    onClick = clicks.open,
)

private fun MaterialScope.transportButton(onClick: Clickable, drawable: Int, label: String): LayoutElement =
    iconButton(
        onClick = onClick,
        iconContent = { icon(imageResource(androidImageResource(drawable)), protoLayoutResourceId = "icon_$drawable") },
        modifier = LayoutModifier.contentDescription(label),
        width = SIDE_BUTTON_DP.dp,
        height = SIDE_BUTTON_DP.dp,
        colors = filledTonalButtonColors(),
    )

private fun MaterialScope.centreButton(
    model: PlayerTileModel,
    onClick: Clickable,
    cover: ByteArray?,
    coverSizePx: Int,
    label: String,
): LayoutElement {
    val glyph = if (model.isPlaying) R.drawable.ic_tile_pause else R.drawable.ic_tile_play
    if (cover == null || model.coverKey == null) {
        return iconButton(
            onClick = onClick,
            iconContent = { icon(imageResource(androidImageResource(glyph)), protoLayoutResourceId = "icon_$glyph") },
            modifier = LayoutModifier.contentDescription(label),
            width = CENTRE_BUTTON_DP.dp,
            height = CENTRE_BUTTON_DP.dp,
            colors = filledButtonColors(),
        )
    }
    return box(
        protoLayoutScope.basicImage(
            imageResource(inlineImage = inlineImageResource(cover, coverSizePx, coverSizePx)),
            width = CENTRE_BUTTON_DP.dp,
            height = CENTRE_BUTTON_DP.dp,
            protoLayoutResourceId = "cover_${model.coverKey}",
            modifier = LayoutModifier.clip(CENTRE_CORNER_DP),
        ),
        box(
            icon(
                imageResource(androidImageResource(glyph)),
                protoLayoutResourceId = "icon_$glyph",
                tintColor = Color.White.toArgb().argb,
            ),
            width = CENTRE_BUTTON_DP.dp,
            height = CENTRE_BUTTON_DP.dp,
            modifier = LayoutModifier.background(SCRIM.argb).clip(CENTRE_CORNER_DP),
            horizontalAlignment = HORIZONTAL_ALIGN_CENTER,
            verticalAlignment = VERTICAL_ALIGN_CENTER,
        ),
        width = CENTRE_BUTTON_DP.dp,
        height = CENTRE_BUTTON_DP.dp,
        modifier = LayoutModifier.clickable(onClick).contentDescription(label),
    )
}

/**
 * The app's palette, for a surface the app does not draw.
 *
 * The same derivation the watch theme uses (brand → phone scheme → Wear
 * scheme), so the tile's purple is the player's purple and not Material's.
 */
fun fluidifyTileColors(): ColorScheme {
    val wear = fluidWearColorScheme(fluidColorScheme(FluidWearDefaults.settings, isDark = true, brand = FluidifyWearBrand))
    fun Color.layout() = toArgb().argb
    return ColorScheme(
        primary = wear.primary.layout(),
        primaryDim = wear.primaryDim.layout(),
        primaryContainer = wear.primaryContainer.layout(),
        onPrimary = wear.onPrimary.layout(),
        onPrimaryContainer = wear.onPrimaryContainer.layout(),
        secondary = wear.secondary.layout(),
        secondaryDim = wear.secondaryDim.layout(),
        secondaryContainer = wear.secondaryContainer.layout(),
        onSecondary = wear.onSecondary.layout(),
        onSecondaryContainer = wear.onSecondaryContainer.layout(),
        tertiary = wear.tertiary.layout(),
        tertiaryDim = wear.tertiaryDim.layout(),
        tertiaryContainer = wear.tertiaryContainer.layout(),
        onTertiary = wear.onTertiary.layout(),
        onTertiaryContainer = wear.onTertiaryContainer.layout(),
        surfaceContainerLow = wear.surfaceContainerLow.layout(),
        surfaceContainer = wear.surfaceContainer.layout(),
        surfaceContainerHigh = wear.surfaceContainerHigh.layout(),
        onSurface = wear.onSurface.layout(),
        onSurfaceVariant = wear.onSurfaceVariant.layout(),
        outline = wear.outline.layout(),
        outlineVariant = wear.outlineVariant.layout(),
        background = wear.background.layout(),
        onBackground = wear.onBackground.layout(),
        error = wear.error.layout(),
        errorDim = wear.errorDim.layout(),
        errorContainer = wear.errorContainer.layout(),
        onError = wear.onError.layout(),
        onErrorContainer = wear.onErrorContainer.layout(),
    )
}

internal const val COVER_PX = 144
private const val CENTRE_BUTTON_DP = 72f
private const val CENTRE_CORNER_DP = 22f
private const val SIDE_BUTTON_DP = 52f
private const val BUTTON_GAP_DP = 8f
private const val ROW_GAP_DP = 8f

/** Black at 35%: enough for the white mark to read on a bright cover, not so much it hides it. */
private const val SCRIM = 0x59000000
