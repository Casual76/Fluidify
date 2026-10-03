package dev.pampa.fluidify.wear.system

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.CONTENT_SCALE_MODE_CROP
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
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledButtonColors
import androidx.wear.protolayout.material3.CircularProgressIndicatorDefaults
import androidx.wear.protolayout.material3.ProgressIndicatorColors
import androidx.wear.protolayout.material3.circularProgressIndicator
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
import androidx.wear.protolayout.types.LayoutColor
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
    /** Where the song was at [sampledAtEpochMs]; with [durationMs], what the ring draws. */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val sampledAtEpochMs: Long = 0,
    /** What plays next, so a press on "next" can show it before the phone answers. */
    val next: Next? = null,
) {
    val isEmpty: Boolean get() = trackUri == null

    data class Next(val title: String, val artist: String, val trackUri: String, val coverKey: String?, val durationMs: Long)

    /** How far the song is, 0..1, at the moment the model was made. */
    val progress: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * What the tile should show right after [click], before the phone has said anything: the
     * renderer asks for a new layout the moment it is pressed, and a tile that redrew the old song
     * after "next" is exactly what made the skip buttons look as if they had not worked.
     */
    fun afterClick(click: String, nowMs: Long = System.currentTimeMillis()): PlayerTileModel = when (click) {
        TileActions.TOGGLE -> copy(
            isPlaying = !isPlaying,
            positionMs = positionAt(nowMs),
            sampledAtEpochMs = nowMs,
        )
        TileActions.NEXT -> next?.let {
            copy(
                title = it.title,
                artist = it.artist,
                trackUri = it.trackUri,
                coverKey = it.coverKey,
                liked = null,
                positionMs = 0,
                durationMs = it.durationMs,
                sampledAtEpochMs = nowMs,
                next = null,
            )
        } ?: copy(positionMs = 0, sampledAtEpochMs = nowMs)
        TileActions.PREVIOUS -> copy(positionMs = 0, sampledAtEpochMs = nowMs)
        TileActions.LIKE -> copy(liked = true)
        TileActions.UNLIKE -> copy(liked = false)
        else -> this
    }

    private fun positionAt(nowMs: Long): Long =
        if (!isPlaying) positionMs else (positionMs + (nowMs - sampledAtEpochMs).coerceAtLeast(0)).coerceAtMost(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)

    companion object {
        fun from(now: NowPlaying, nowMs: Long = System.currentTimeMillis()): PlayerTileModel {
            val snapshot = now.snapshot
            val track = snapshot?.track
            val next = snapshot?.nextTrack
            return PlayerTileModel(
                title = track?.title.orEmpty(),
                artist = track?.artist.orEmpty(),
                isPlaying = snapshot != null && (snapshot.isPlaying || snapshot.playWhenReady),
                liked = snapshot?.liked,
                trackUri = track?.uri,
                coverKey = track?.artKey,
                positionMs = now.positionAt(nowMs),
                durationMs = track?.durationMs ?: 0,
                sampledAtEpochMs = nowMs,
                next = next?.let { Next(it.title, it.artist, it.uri, it.artKey ?: snapshot.nextArtKey, it.durationMs) },
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
 * The player as a tile, after the Spotify watch app's — the song, the artist, the three transport
 * buttons and the heart on the bottom edge — and after the player's own look: the cover, blurred
 * and darkened, fills the tile behind translucent buttons, the sharp cover is the play button, and
 * the song's progress runs around the edge, leaving the top for the title as the player leaves it
 * for the clock. The ring is worked out by the renderer from the time, so it moves while the tile
 * is on screen without the tile asking to be redrawn.
 */
fun MaterialScope.playerTileLayout(
    model: PlayerTileModel,
    clicks: PlayerTileClicks,
    cover: ByteArray?,
    coverSizePx: Int = COVER_PX,
    backdrop: ByteArray? = null,
): LayoutElement {
    if (model.isEmpty) return emptyTileLayout(clicks)
    val playLabel = context.getString(if (model.isPlaying) R.string.pause else R.string.play)
    val content = primaryLayout(
        titleSlot = { text(model.title.layoutString, maxLines = 1, color = colorScheme.onSurface) },
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
                // The same glass either way; a liked song lights the heart, not the whole edge.
                colors = if (liked) glassButtonColors(icon = colorScheme.primary) else glassButtonColors(),
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
    val layers = listOfNotNull(
        backdrop?.let { bytes ->
            protoLayoutScope.basicImage(
                imageResource(inlineImage = inlineImageResource(bytes, BACKDROP_INLINE_PX, BACKDROP_INLINE_PX)),
                width = expand(),
                height = expand(),
                protoLayoutResourceId = "backdrop_${model.coverKey}",
                contentScaleMode = CONTENT_SCALE_MODE_CROP,
            )
        },
        progressRing(model),
        content,
    )
    return box(*layers.toTypedArray(), width = expand(), height = expand())
}

/**
 * The song's progress around the edge, with a gap at the top for the title. Live while playing:
 * the renderer computes it from the platform's clock every second.
 */
private fun MaterialScope.progressRing(model: PlayerTileModel): LayoutElement {
    val live = if (model.isPlaying) LiveProgress.expression(model.positionMs, model.sampledAtEpochMs, model.durationMs) else null
    return circularProgressIndicator(
        staticProgress = model.progress,
        dynamicProgress = live,
        startAngleDegrees = RING_GAP_DEGREES / 2f,
        endAngleDegrees = 360f - RING_GAP_DEGREES / 2f,
        strokeWidth = CircularProgressIndicatorDefaults.SMALL_STROKE_WIDTH,
        colors = ProgressIndicatorColors(colorScheme.primary, TRACK.argb),
        size = expand(),
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
        textEdgeButton(onClick = clicks.resume, colors = glassButtonColors()) { text(context.getString(R.string.resume).layoutString) }
    },
    onClick = clicks.open,
)

/** A translucent white over the backdrop: the tile's stand-in for the player's glass. */
private fun glassButtonColors(icon: LayoutColor = Color.White.toArgb().argb): ButtonColors {
    val white = Color.White.toArgb().argb
    return ButtonColors(GLASS.argb, icon, white, white)
}

private fun MaterialScope.transportButton(onClick: Clickable, drawable: Int, label: String): LayoutElement =
    iconButton(
        onClick = onClick,
        iconContent = { icon(imageResource(androidImageResource(drawable)), protoLayoutResourceId = "icon_$drawable") },
        modifier = LayoutModifier.contentDescription(label),
        width = SIDE_BUTTON_DP.dp,
        height = SIDE_BUTTON_DP.dp,
        colors = glassButtonColors(),
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

internal const val COVER_PX = 160
private const val CENTRE_BUTTON_DP = 80f
private const val CENTRE_CORNER_DP = 22f
private const val SIDE_BUTTON_DP = 52f
private const val BUTTON_GAP_DP = 8f
private const val ROW_GAP_DP = 8f

/** Black at 35%: enough for the white mark to read on a bright cover, not so much it hides it. */
private const val SCRIM = 0x59000000

/** White at 16%: a button that is there without covering the backdrop. */
private const val GLASS = 0x29FFFFFF

/** White at 14%: the ring's track. */
private const val TRACK = 0x24FFFFFF

/** The top of the ring left open for the title, as the player leaves it for the clock. */
private const val RING_GAP_DEGREES = 64f

/** The backdrop's pixels; see [CoverImages.backdrop]. */
private const val BACKDROP_INLINE_PX = 48
