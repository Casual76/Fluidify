/*
 * Ported from Pampa Widgets (com.pampa.widgets.widget.media), the same author's widget app.
 *
 * Kept as close to its source as the move allows, so a fix there can be diffed in here. What
 * changed is where the music comes from: that app reads whatever session is playing through a
 * notification listener, because it is a widget for other people's players. This one *is* the
 * player, so the snapshot is handed to it by the playback service and there is no listener, no
 * permission and no package to resolve.
 */
package dev.lelonio.square.widget.media

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import dev.lelonio.square.ui.MainActivity
import dev.lelonio.square.R

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Background render bounds used for launcher-size-specific bitmaps. */
private const val BackgroundMinSidePx = 220
private const val BackgroundMaxSidePx = 720

/**
 * A button that has just been pressed, drawn before the player has answered.
 *
 * In the app this came from the coordinator owned this; here there is no coordinator, because the
 * player is in this app and answers in a frame or two. It is kept because the renderer draws from
 * it, and because "a frame or two" across a binder and back out to a launcher is still long enough
 * to read as a button that did nothing.
 */
internal data class MediaWidgetInteraction(
  val action: MediaControlAction,
  val showFeedback: Boolean,
  val expectedPlaying: Boolean?,
)

/** State owned by this process so a RemoteViews [android.widget.ViewFlipper] can alternate layers. */
private data class MediaWidgetVisualState(
  val background: Bitmap,
  val backgroundSignature: String,
  val backgroundSlot: Int,
  val artwork: Bitmap?,
  val artworkSignature: String,
  val artworkSlot: Int,
  val isPlaying: Boolean,
  val playPauseSlot: Int,
  val renderSignature: MediaWidgetRenderSignature,
)

internal data class MediaWidgetRenderSignature(
  val availability: MediaPlaybackAvailability,
  val trackKey: String,
  val title: String,
  val artist: String,
  val sourceLabel: String,
  val packageName: String,
  val isPlaying: Boolean,
  val canPlayPause: Boolean,
  val canSkipNext: Boolean,
  val canSkipPrevious: Boolean,
  val artworkSignature: String,
  val durationMs: Long,
  val playbackAnchorSecond: Long,
  val playbackSpeed: Float,
  val isFromCache: Boolean,
  val theme: MediaWidgetTheme,
  val artworkSize: MediaWidgetArtworkSize,
  val showSource: Boolean,
  val showArtist: Boolean,
  val keepLastSong: Boolean,
  val instantControls: Boolean,
  val animatedFeedback: Boolean,
  val layout: MediaWidgetLayoutSpec,
  val interaction: MediaWidgetInteraction?,
)

private data class MediaWidgetVisualPlan(
  val initialize: Boolean,
  val backgroundChanged: Boolean,
  val animateBackground: Boolean,
  val previousBackground: Bitmap,
  val targetBackgroundSlot: Int,
  val artworkChanged: Boolean,
  val animateArtwork: Boolean,
  val previousArtwork: Bitmap?,
  val targetArtworkSlot: Int,
  val playbackChanged: Boolean,
  val animatePlayback: Boolean,
  val previousIsPlaying: Boolean,
  val targetPlayPauseSlot: Int,
)

/**
 * Builds the RemoteViews for the Media Controls widget and drives the press feedback
 * animations.
 *
 * Visual language: Samsung-like album-tinted glass. Artwork is shown only in the square
 * cover frame; its palette is used as a flat tint, never as the card background image.
 */
object MediaWidgetUpdater {
  private val stateLock = Any()
  private val visualStates = mutableMapOf<Int, MediaWidgetVisualState>()

  /** Draw these widgets with this snapshot. The one way in; see NowPlayingWidgetBridge. */
  fun update(
    context: Context,
    appWidgetIds: IntArray,
    snapshot: MediaPlaybackSnapshot,
    settings: AppSettings,
  ) {
    updateWithSnapshot(context, AppWidgetManager.getInstance(context), appWidgetIds, snapshot, settings)
  }

  fun onDeleted(appWidgetIds: IntArray) {
    synchronized(stateLock) {
      appWidgetIds.forEach(visualStates::remove)
    }
  }

  internal fun buildRemoteViewsForTest(
    context: Context,
    snapshot: MediaPlaybackSnapshot,
    settings: AppSettings,
    widthDp: Int,
    heightDp: Int,
  ): RemoteViews {
    val layout = MediaWidgetLayoutSpec.fromDimensions(
      widthDp = widthDp,
      heightDp = heightDp,
      settings = settings,
    )
    val style = MediaWidgetStyle.from(context, snapshot.artwork, settings, layout)
    return buildRemoteViews(
      context = context,
      snapshot = snapshot,
      settings = settings,
      style = style,
      layout = layout,
      visualPlan = MediaWidgetVisualPlan(
        initialize = true,
        backgroundChanged = false,
        animateBackground = false,
        previousBackground = style.background,
        targetBackgroundSlot = 0,
        artworkChanged = false,
        animateArtwork = false,
        previousArtwork = snapshot.artwork,
        targetArtworkSlot = 0,
        playbackChanged = false,
        animatePlayback = false,
        previousIsPlaying = snapshot.isPlaying,
        targetPlayPauseSlot = 0,
      ),
      interaction = null,
    )
  }

  private fun updateWithSnapshot(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
    snapshot: MediaPlaybackSnapshot,
    settings: AppSettings,
    interaction: MediaWidgetInteraction? = null,
  ) {
    if (appWidgetIds.isEmpty()) return
    val elapsedRealtimeMs = SystemClock.elapsedRealtime()
    appWidgetIds.forEach { widgetId ->
      val layout = MediaWidgetLayoutSpec.from(appWidgetManager.getAppWidgetOptions(widgetId), settings)
      val renderSignature = mediaWidgetRenderSignature(
        snapshot = snapshot,
        settings = settings,
        layout = layout,
        interaction = interaction,
        elapsedRealtimeMs = elapsedRealtimeMs,
      )
      val alreadyRendered = synchronized(stateLock) {
        visualStates[widgetId]?.renderSignature == renderSignature
      }
      if (alreadyRendered) return@forEach

      val style = MediaWidgetStyle.from(context, snapshot.artwork, settings, layout)
      val visualPlan = visualPlanFor(
        widgetId = widgetId,
        snapshot = snapshot,
        style = style,
        animateChanges = settings.mediaWidgetAnimatedFeedback && android.animation.ValueAnimator.areAnimatorsEnabled(),
      )
      val views = buildRemoteViews(context, snapshot, settings, style, layout, visualPlan, interaction, widgetId)
      if (visualPlan.initialize) {
        appWidgetManager.updateAppWidget(widgetId, views)
      } else {
        appWidgetManager.partiallyUpdateAppWidget(widgetId, views)
      }
      commitVisualState(widgetId, snapshot, style, visualPlan, renderSignature)
    }
  }

  private fun visualPlanFor(
    widgetId: Int,
    snapshot: MediaPlaybackSnapshot,
    style: MediaWidgetStyle,
    animateChanges: Boolean,
  ): MediaWidgetVisualPlan {
    val previous = synchronized(stateLock) { visualStates[widgetId] }
    if (previous == null) {
      return MediaWidgetVisualPlan(
        initialize = true,
        backgroundChanged = false,
        animateBackground = false,
        previousBackground = style.background,
        targetBackgroundSlot = 0,
        artworkChanged = false,
        animateArtwork = false,
        previousArtwork = snapshot.artwork,
        targetArtworkSlot = 0,
        playbackChanged = false,
        animatePlayback = false,
        previousIsPlaying = snapshot.isPlaying,
        targetPlayPauseSlot = 0,
      )
    }
    val backgroundChanged = previous.backgroundSignature != style.backgroundSignature
    val artworkChanged = previous.artworkSignature != snapshot.artworkSignature()
    val playbackChanged = previous.isPlaying != snapshot.isPlaying
    // RemoteViews ViewFlipper transitions for bitmap-heavy layers briefly expose the
    // launcher wallpaper on One UI. Keep both slots current and update them atomically;
    // lightweight playback glyph feedback remains safe to animate.
    val animateBackground = false
    val animateArtwork = false
    val animatePlayback = playbackChanged && animateChanges
    return MediaWidgetVisualPlan(
      initialize = false,
      backgroundChanged = backgroundChanged,
      animateBackground = animateBackground,
      previousBackground = previous.background,
      targetBackgroundSlot = if (animateBackground) {
        1 - previous.backgroundSlot
      } else {
        previous.backgroundSlot
      },
      artworkChanged = artworkChanged,
      animateArtwork = animateArtwork,
      previousArtwork = previous.artwork,
      targetArtworkSlot = if (animateArtwork) {
        1 - previous.artworkSlot
      } else {
        previous.artworkSlot
      },
      playbackChanged = playbackChanged,
      animatePlayback = animatePlayback,
      previousIsPlaying = previous.isPlaying,
      targetPlayPauseSlot = if (animatePlayback) {
        1 - previous.playPauseSlot
      } else {
        previous.playPauseSlot
      },
    )
  }

  private fun commitVisualState(
    widgetId: Int,
    snapshot: MediaPlaybackSnapshot,
    style: MediaWidgetStyle,
    visualPlan: MediaWidgetVisualPlan,
    renderSignature: MediaWidgetRenderSignature,
  ) {
    synchronized(stateLock) {
      visualStates[widgetId] = MediaWidgetVisualState(
        background = style.background,
        backgroundSignature = style.backgroundSignature,
        backgroundSlot = visualPlan.targetBackgroundSlot,
        artwork = snapshot.artwork,
        artworkSignature = snapshot.artworkSignature(),
        artworkSlot = visualPlan.targetArtworkSlot,
        isPlaying = snapshot.isPlaying,
        playPauseSlot = visualPlan.targetPlayPauseSlot,
        renderSignature = renderSignature,
      )
    }
  }

  private fun buildRemoteViews(
    context: Context,
    snapshot: MediaPlaybackSnapshot,
    settings: AppSettings,
    style: MediaWidgetStyle,
    layout: MediaWidgetLayoutSpec,
    visualPlan: MediaWidgetVisualPlan,
    interaction: MediaWidgetInteraction?,
    widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
  ): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_media_controls)

    applyBackground(views, style, visualPlan)
    applyLayout(context, views, style, layout)
    views.setTextViewText(R.id.media_widget_source, snapshot.sourceLabel)
    views.setTextViewText(R.id.media_widget_title, snapshot.title)
    views.setTextViewText(R.id.media_widget_artist, snapshot.artist)
    views.setTextViewText(R.id.media_widget_status, interaction?.statusLabel() ?: snapshot.statusLabel())
    views.setTextColor(R.id.media_widget_source, style.primaryTextColor)
    views.setTextColor(R.id.media_widget_title, style.primaryTextColor)
    views.setTextColor(R.id.media_widget_artist, style.secondaryTextColor)
    views.setTextColor(R.id.media_widget_permission, style.secondaryTextColor)
    views.setTextColor(R.id.media_widget_status, style.secondaryTextColor)
    views.setTextColor(R.id.media_widget_time, style.secondaryTextColor)
    views.setColorStateList(
      R.id.media_widget_source,
      "setBackgroundTintList",
      ColorStateList.valueOf(style.sourcePillColor),
    )
    views.setColorStateList(
      R.id.media_widget_artwork_frame,
      "setBackgroundTintList",
      ColorStateList.valueOf(style.artworkFrameColor),
    )
    views.setViewVisibility(
      R.id.media_widget_text_stack,
      if (layout.showText) View.VISIBLE else View.GONE,
    )
    views.setViewVisibility(
      R.id.media_widget_source,
      if (layout.showText && layout.showSource && settings.mediaWidgetShowSource) View.VISIBLE else View.GONE,
    )
    views.setViewVisibility(
      R.id.media_widget_artist,
      if (layout.showText && layout.showArtist && settings.mediaWidgetShowArtist) View.VISIBLE else View.GONE,
    )
    views.setViewVisibility(
      R.id.media_widget_permission,
      if (layout.showText && snapshot.availability == MediaPlaybackAvailability.PermissionRequired) {
        View.VISIBLE
      } else {
        View.GONE
      },
    )

    applyArtwork(context, views, snapshot, layout, visualPlan)
    applyControls(context, views, snapshot, settings, style, layout, visualPlan, interaction)
    applyProgress(views, snapshot, style, layout)
    applyTime(views, snapshot, layout)

    val playIntent = if (snapshot.availability == MediaPlaybackAvailability.PermissionRequired) {
      settingsPendingIntent(context, widgetId)
    } else {
      broadcastPendingIntent(context, NowPlayingWidget.ActionTogglePlayPause, 1, widgetId)
    }
    views.setOnClickPendingIntent(R.id.media_widget_play_pause, playIntent)
    views.setOnClickPendingIntent(R.id.media_widget_play_pause_container, playIntent)
    views.setOnClickPendingIntent(R.id.media_widget_permission, settingsPendingIntent(context, widgetId))
    val openMediaIntent = openMediaAppPendingIntent(context, snapshot, widgetId)
    views.setOnClickPendingIntent(R.id.media_widget_root, openMediaIntent)
    views.setOnClickPendingIntent(R.id.media_widget_track_row, openMediaIntent)
    views.setOnClickPendingIntent(R.id.media_widget_artwork_frame, openMediaIntent)
    views.setOnClickPendingIntent(R.id.media_widget_text_stack, openMediaIntent)
    views.setOnClickPendingIntent(
      R.id.media_widget_previous,
      broadcastPendingIntent(context, NowPlayingWidget.ActionPrevious, 2, widgetId),
    )
    views.setOnClickPendingIntent(
      R.id.media_widget_next,
      broadcastPendingIntent(context, NowPlayingWidget.ActionNext, 3, widgetId),
    )
    views.setOnClickPendingIntent(
      R.id.media_widget_refresh,
      broadcastPendingIntent(context, NowPlayingWidget.ActionRefresh, 4, widgetId),
    )
    views.setOnClickPendingIntent(R.id.media_widget_open_media, openMediaIntent)
    views.setOnClickPendingIntent(R.id.media_widget_open_pampa, configurationPendingIntent(context, widgetId))
    applyAnimatedTransitions(views, visualPlan)
    return views
  }

  private fun applyAnimatedTransitions(
    views: RemoteViews,
    visualPlan: MediaWidgetVisualPlan,
  ) {
    if (visualPlan.animateBackground) {
      views.setDisplayedChild(R.id.media_widget_background_flipper, visualPlan.targetBackgroundSlot)
    }
    if (visualPlan.animateArtwork) {
      views.setDisplayedChild(R.id.media_widget_artwork_flipper, visualPlan.targetArtworkSlot)
    }
    if (visualPlan.animatePlayback) {
      views.setDisplayedChild(R.id.media_widget_play_pause_glyph_flipper, visualPlan.targetPlayPauseSlot)
    }
  }

  private fun applyBackground(
    views: RemoteViews,
    style: MediaWidgetStyle,
    visualPlan: MediaWidgetVisualPlan,
  ) {
    val first = if (visualPlan.animateBackground && visualPlan.targetBackgroundSlot != 0) {
      visualPlan.previousBackground
    } else {
      style.background
    }
    val second = if (visualPlan.animateBackground && visualPlan.targetBackgroundSlot != 1) {
      visualPlan.previousBackground
    } else {
      style.background
    }
    views.setImageViewBitmap(R.id.media_widget_background, first)
    views.setImageViewBitmap(R.id.media_widget_background_next, second)
  }

  private fun applyLayout(
    context: Context,
    views: RemoteViews,
    style: MediaWidgetStyle,
    layout: MediaWidgetLayoutSpec,
  ) {
    views.setViewPadding(
      R.id.media_widget_content,
      context.dp(layout.contentPaddingHorizontalDp),
      context.dp(layout.contentPaddingVerticalDp),
      context.dp(layout.contentPaddingHorizontalDp),
      context.dp(layout.contentPaddingVerticalDp),
    )
    views.setInt(R.id.media_widget_track_row, "setGravity", layout.trackGravity)
    views.setViewLayoutHeight(
      R.id.media_widget_controls,
      layout.controlsRowHeightDp.toFloat(),
      TypedValue.COMPLEX_UNIT_DIP,
    )
    views.setViewLayoutHeight(
      R.id.media_widget_extra_actions,
      layout.extraRowHeightDp.toFloat(),
      TypedValue.COMPLEX_UNIT_DIP,
    )
    views.setViewLayoutHeight(
      R.id.media_widget_source,
      layout.sourcePillHeightDp,
      TypedValue.COMPLEX_UNIT_DIP,
    )
    views.setTextViewTextSize(R.id.media_widget_source, TypedValue.COMPLEX_UNIT_SP, layout.sourceSp)
    views.setTextViewTextSize(R.id.media_widget_title, TypedValue.COMPLEX_UNIT_SP, layout.titleSp)
    views.setTextViewTextSize(R.id.media_widget_artist, TypedValue.COMPLEX_UNIT_SP, layout.artistSp)
    views.setTextViewTextSize(R.id.media_widget_permission, TypedValue.COMPLEX_UNIT_SP, layout.permissionSp)
    views.setTextViewTextSize(R.id.media_widget_status, TypedValue.COMPLEX_UNIT_SP, layout.metaSp)
    views.setTextViewTextSize(R.id.media_widget_time, TypedValue.COMPLEX_UNIT_SP, layout.metaSp)
    views.setInt(R.id.media_widget_title, "setMaxLines", layout.titleMaxLines)
    views.setViewVisibility(R.id.media_widget_meta_row, if (layout.showMeta) View.VISIBLE else View.GONE)
    views.setViewVisibility(
      R.id.media_widget_extra_actions,
      if (layout.showExtraActions) View.VISIBLE else View.GONE,
    )
    views.setColorStateList(
      R.id.media_widget_refresh,
      "setBackgroundTintList",
      ColorStateList.valueOf(style.controlSurfaceColor),
    )
    views.setColorStateList(
      R.id.media_widget_open_media,
      "setBackgroundTintList",
      ColorStateList.valueOf(style.controlSurfaceColor),
    )
    views.setColorStateList(
      R.id.media_widget_open_pampa,
      "setBackgroundTintList",
      ColorStateList.valueOf(style.controlSurfaceColor),
    )
  }

  private fun applyArtwork(
    context: Context,
    views: RemoteViews,
    snapshot: MediaPlaybackSnapshot,
    layout: MediaWidgetLayoutSpec,
    visualPlan: MediaWidgetVisualPlan,
  ) {
    val artworkDp = layout.artworkDp
    views.setViewLayoutWidth(R.id.media_widget_artwork_frame, artworkDp, TypedValue.COMPLEX_UNIT_DIP)
    views.setViewLayoutHeight(R.id.media_widget_artwork_frame, artworkDp, TypedValue.COMPLEX_UNIT_DIP)
    val first = if (visualPlan.animateArtwork && visualPlan.targetArtworkSlot != 0) {
      visualPlan.previousArtwork
    } else {
      snapshot.artwork
    }
    val second = if (visualPlan.animateArtwork && visualPlan.targetArtworkSlot != 1) {
      visualPlan.previousArtwork
    } else {
      snapshot.artwork
    }
    applyArtworkImage(context, views, R.id.media_widget_artwork, first, layout)
    applyArtworkImage(context, views, R.id.media_widget_artwork_next, second, layout)
  }

  private fun applyArtworkImage(
    context: Context,
    views: RemoteViews,
    imageViewId: Int,
    artwork: Bitmap?,
    layout: MediaWidgetLayoutSpec,
  ) {
    if (artwork != null) {
      views.setViewPadding(imageViewId, 0, 0, 0, 0)
      views.setImageViewBitmap(imageViewId, artwork.roundedSquare())
    } else {
      val padding = context.dp((layout.artworkDp * 0.18f).toInt().coerceAtLeast(10))
      views.setViewPadding(imageViewId, padding, padding, padding, padding)
      views.setImageViewResource(imageViewId, R.drawable.ic_widget_music_note)
    }
  }

  private fun applyControls(
    context: Context,
    views: RemoteViews,
    snapshot: MediaPlaybackSnapshot,
    settings: AppSettings,
    style: MediaWidgetStyle,
    layout: MediaWidgetLayoutSpec,
    visualPlan: MediaWidgetVisualPlan,
    interaction: MediaWidgetInteraction?,
  ) {
    val playEnabled = snapshot.canPlayPause || snapshot.availability == MediaPlaybackAvailability.PermissionRequired
    val commandAction = interaction?.action
    val showFeedback = interaction?.showFeedback == true
    val controlsLocked = interaction != null
    val sidePadding = context.dp(((layout.sideButtonDp - layout.sideIconDp) / 2).coerceAtLeast(4))
    val playPadding = context.dp(((layout.playButtonDp - layout.playIconDp) / 2).coerceAtLeast(4))
    val extraPadding = context.dp(((layout.extraButtonDp - layout.extraIconDp) / 2).coerceAtLeast(4))

    views.setViewPadding(R.id.media_widget_play_pause, 0, 0, 0, 0)
    views.setViewPadding(R.id.media_widget_previous, sidePadding, sidePadding, sidePadding, sidePadding)
    views.setViewPadding(R.id.media_widget_next, sidePadding, sidePadding, sidePadding, sidePadding)
    views.setViewPadding(R.id.media_widget_refresh, extraPadding, extraPadding, extraPadding, extraPadding)
    views.setViewPadding(R.id.media_widget_open_media, extraPadding, extraPadding, extraPadding, extraPadding)
    views.setViewPadding(R.id.media_widget_open_pampa, extraPadding, extraPadding, extraPadding, extraPadding)
    views.setViewPadding(R.id.media_widget_play_glyph, playPadding, playPadding, playPadding, playPadding)
    views.setViewPadding(R.id.media_widget_pause_glyph, playPadding, playPadding, playPadding, playPadding)

    views.setSquareDp(R.id.media_widget_previous, layout.sideButtonDp)
    views.setSquareDp(R.id.media_widget_next, layout.sideButtonDp)
    views.setSquareDp(R.id.media_widget_play_pause_container, layout.playButtonDp)
    views.setSquareDp(R.id.media_widget_play_pause, layout.playButtonDp)
    views.setSquareDp(R.id.media_widget_play_pause_glyph_flipper, layout.playButtonDp)
    views.setSquareDp(R.id.media_widget_command_pending, minOf(30, layout.playIconDp + 6))
    views.setSquareDp(R.id.media_widget_refresh, layout.extraButtonDp)
    views.setSquareDp(R.id.media_widget_open_media, layout.extraButtonDp)
    views.setSquareDp(R.id.media_widget_open_pampa, layout.extraButtonDp)
    views.setViewVisibility(R.id.media_widget_previous, if (layout.showSideControls) View.VISIBLE else View.GONE)
    views.setViewVisibility(R.id.media_widget_next, if (layout.showSideControls) View.VISIBLE else View.GONE)

    views.setColorStateList(
      R.id.media_widget_previous,
      "setBackgroundTintList",
      ColorStateList.valueOf(
        if (snapshot.canSkipPrevious) style.controlSurfaceColor else style.disabledControlSurfaceColor,
      ),
    )
    views.setColorStateList(
      R.id.media_widget_next,
      "setBackgroundTintList",
      ColorStateList.valueOf(
        if (snapshot.canSkipNext) style.controlSurfaceColor else style.disabledControlSurfaceColor,
      ),
    )
    views.setColorStateList(
      R.id.media_widget_play_pause,
      "setBackgroundTintList",
      ColorStateList.valueOf(if (playEnabled) style.playSurfaceColor else style.disabledControlSurfaceColor),
    )
    views.setImageViewResource(R.id.media_widget_previous, R.drawable.ic_widget_previous)
    views.setImageViewResource(R.id.media_widget_next, R.drawable.ic_widget_next)
    views.setImageViewResource(R.id.media_widget_refresh, R.drawable.ic_widget_refresh)
    views.setImageViewResource(R.id.media_widget_open_media, R.drawable.ic_widget_open_app)
    views.setImageViewResource(R.id.media_widget_open_pampa, R.drawable.ic_widget_settings)
    val currentGlyph = if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
    if (visualPlan.animatePlayback) {
      val previousGlyph = if (visualPlan.previousIsPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
      val firstGlyph = if (visualPlan.targetPlayPauseSlot == 0) currentGlyph else previousGlyph
      val secondGlyph = if (visualPlan.targetPlayPauseSlot == 1) currentGlyph else previousGlyph
      views.setImageViewResource(R.id.media_widget_play_glyph, firstGlyph)
      views.setImageViewResource(R.id.media_widget_pause_glyph, secondGlyph)
    } else {
      views.setImageViewResource(R.id.media_widget_play_glyph, currentGlyph)
      views.setImageViewResource(R.id.media_widget_pause_glyph, currentGlyph)
    }
    views.setColorStateList(
      R.id.media_widget_previous,
      "setImageTintList",
      ColorStateList.valueOf(if (snapshot.canSkipPrevious) style.controlIconColor else style.disabledControlIconColor),
    )
    views.setColorStateList(
      R.id.media_widget_next,
      "setImageTintList",
      ColorStateList.valueOf(if (snapshot.canSkipNext) style.controlIconColor else style.disabledControlIconColor),
    )
    listOf(R.id.media_widget_play_glyph, R.id.media_widget_pause_glyph).forEach { glyphId ->
      views.setColorStateList(
        glyphId,
        "setImageTintList",
        ColorStateList.valueOf(if (playEnabled) style.playIconColor else style.disabledControlIconColor),
      )
    }
    listOf(R.id.media_widget_refresh, R.id.media_widget_open_media, R.id.media_widget_open_pampa).forEach { iconId ->
      views.setColorStateList(iconId, "setImageTintList", ColorStateList.valueOf(style.controlIconColor))
    }
    views.setColorStateList(
      R.id.media_widget_command_pending,
      "setIndeterminateTintList",
      ColorStateList.valueOf(style.playIconColor),
    )
    val isTogglePending = commandAction == MediaControlAction.TogglePlayPause && showFeedback
    val isNextPending = commandAction == MediaControlAction.Next && showFeedback
    val isPreviousPending = commandAction == MediaControlAction.Previous && showFeedback
    views.setViewVisibility(
      R.id.media_widget_command_pending,
      if (isTogglePending) View.VISIBLE else View.GONE,
    )
    views.setFloat(R.id.media_widget_play_pause_glyph_flipper, "setAlpha", if (isTogglePending) 0.16f else 1f)
    views.setScale(R.id.media_widget_play_pause_container, if (isTogglePending) 0.93f else 1f)
    views.setScale(R.id.media_widget_next, if (isNextPending) 0.91f else 1f)
    views.setScale(R.id.media_widget_previous, if (isPreviousPending) 0.91f else 1f)

    views.setBoolean(R.id.media_widget_previous, "setEnabled", snapshot.canSkipPrevious && !controlsLocked)
    views.setBoolean(R.id.media_widget_next, "setEnabled", snapshot.canSkipNext && !controlsLocked)
    views.setBoolean(R.id.media_widget_play_pause, "setEnabled", playEnabled && !controlsLocked)
  }

  private fun applyProgress(
    views: RemoteViews,
    snapshot: MediaPlaybackSnapshot,
    style: MediaWidgetStyle,
    layout: MediaWidgetLayoutSpec,
  ) {
    val progress = snapshot.progressPermille()
    views.setViewLayoutHeight(
      R.id.media_widget_progress,
      layout.progressHeightDp,
      TypedValue.COMPLEX_UNIT_DIP,
    )
    views.setViewVisibility(
      R.id.media_widget_progress,
      when {
        !layout.showProgress -> View.GONE
        progress > 0 -> View.VISIBLE
        else -> View.INVISIBLE
      },
    )
    views.setColorStateList(
      R.id.media_widget_progress,
      "setProgressTintList",
      ColorStateList.valueOf(style.progressColor),
    )
    views.setColorStateList(
      R.id.media_widget_progress,
      "setProgressBackgroundTintList",
      ColorStateList.valueOf(style.progressTrackColor),
    )
    views.setProgressBar(R.id.media_widget_progress, 1000, progress, false)
  }

  private fun applyTime(
    views: RemoteViews,
    snapshot: MediaPlaybackSnapshot,
    layout: MediaWidgetLayoutSpec,
  ) {
    val showTime = layout.showMeta && snapshot.durationMs > 0L
    views.setViewVisibility(R.id.media_widget_time, if (showTime) View.VISIBLE else View.GONE)
    if (!showTime) {
      views.setChronometer(R.id.media_widget_time, SystemClock.elapsedRealtime(), null, false)
      return
    }
    val position = snapshot.positionMs.coerceIn(0L, snapshot.durationMs)
    val base = SystemClock.elapsedRealtime() - position
    views.setChronometer(
      R.id.media_widget_time,
      base,
      "%s / ${snapshot.durationMs.clockLabel()}",
      snapshot.isPlaying,
    )
  }

  internal fun buildRemoteViewsSequenceForTest(
    context: Context,
    widgetId: Int,
    snapshot: MediaPlaybackSnapshot,
    settings: AppSettings,
    widthDp: Int,
    heightDp: Int,
  ): RemoteViews {
    val layout = MediaWidgetLayoutSpec.fromDimensions(widthDp, heightDp, settings)
    val style = MediaWidgetStyle.from(context, snapshot.artwork, settings, layout)
    val visualPlan = visualPlanFor(
      widgetId = widgetId,
      snapshot = snapshot,
      style = style,
      animateChanges = settings.mediaWidgetAnimatedFeedback && android.animation.ValueAnimator.areAnimatorsEnabled(),
    )
    val renderSignature = mediaWidgetRenderSignature(
      snapshot = snapshot,
      settings = settings,
      layout = layout,
      interaction = null,
      elapsedRealtimeMs = SystemClock.elapsedRealtime(),
    )
    return buildRemoteViews(context, snapshot, settings, style, layout, visualPlan, interaction = null)
      .also {
        commitVisualState(widgetId, snapshot, style, visualPlan, renderSignature)
      }
  }

  internal fun resetVisualStateForTest(widgetId: Int) {
    synchronized(stateLock) { visualStates.remove(widgetId) }
  }

  private fun broadcastPendingIntent(
    context: Context,
    action: String,
    requestCode: Int,
    widgetId: Int = -1,
  ): PendingIntent {
    val intent = Intent(context, NowPlayingWidget::class.java).setAction(action)
      .setData(android.net.Uri.parse("fluidify://widget/$widgetId/$action"))
      .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
    return PendingIntent.getBroadcast(
      context,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun settingsPendingIntent(context: Context, widgetId: Int): PendingIntent {
    return PendingIntent.getActivity(
      context,
      widgetId,
      context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_DEFAULT),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  /**
   * In the app this came from, this opened a screen for choosing which player to watch. Here the
   * player is the app, so there is nothing to choose and the button is the app's front door.
   */
  private fun configurationPendingIntent(context: Context, widgetId: Int): PendingIntent =
    settingsPendingIntent(context, widgetId)

  private fun openMediaAppPendingIntent(context: Context, snapshot: MediaPlaybackSnapshot, widgetId: Int): PendingIntent {
    if (widgetId <= 0) {
      val launch = mediaAppLaunchIntent(context, snapshot)
        ?: return broadcastPendingIntent(context, NowPlayingWidget.ActionRefresh, 30)
      return PendingIntent.getActivity(context, 30, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    // Straight to the player, with the window open on it. The app this came from went through a
    // launcher activity of its own because it has to work out *which* app to open; here the answer
    // is always this one, and a hop through an activity to decide nothing is a hop that shows.
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?.setData(android.net.Uri.parse("fluidify://widget/$widgetId/open"))
      ?.putExtra(dev.lelonio.square.playback.EXTRA_OPEN_PLAYER, true)
      ?: return broadcastPendingIntent(context, NowPlayingWidget.ActionRefresh, 30)
    return PendingIntent.getActivity(
      context, widgetId, open,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

}

/**
 * Which app to open. Always this one: the source label the app this came from used to resolve a
 * foreign player is empty here, because the player is not foreign.
 */
internal fun MediaPlaybackSnapshot.launchPackageName(): String = packageName

internal fun mediaAppLaunchIntent(
  context: Context,
  snapshot: MediaPlaybackSnapshot,
): Intent? = snapshot.launchPackageName()
  .takeIf { it.isNotBlank() }
  ?.let(context.packageManager::getLaunchIntentForPackage)

internal fun mediaWidgetRenderSignature(
  snapshot: MediaPlaybackSnapshot,
  settings: AppSettings,
  layout: MediaWidgetLayoutSpec,
  interaction: MediaWidgetInteraction?,
  elapsedRealtimeMs: Long,
): MediaWidgetRenderSignature {
  val playbackAnchorMs = if (snapshot.isPlaying && snapshot.playbackSpeed > 0f) {
    elapsedRealtimeMs - (snapshot.positionMs / snapshot.playbackSpeed).toLong()
  } else {
    snapshot.positionMs
  }
  return MediaWidgetRenderSignature(
    availability = snapshot.availability,
    trackKey = snapshot.trackKey,
    title = snapshot.title,
    artist = snapshot.artist,
    sourceLabel = snapshot.sourceLabel,
    packageName = snapshot.packageName,
    isPlaying = snapshot.isPlaying,
    canPlayPause = snapshot.canPlayPause,
    canSkipNext = snapshot.canSkipNext,
    canSkipPrevious = snapshot.canSkipPrevious,
    artworkSignature = snapshot.artworkSignature(),
    durationMs = snapshot.durationMs,
    playbackAnchorSecond = playbackAnchorMs / 1_000L,
    playbackSpeed = snapshot.playbackSpeed,
    isFromCache = snapshot.isFromCache,
    theme = settings.mediaWidgetTheme,
    artworkSize = settings.mediaWidgetArtworkSize,
    showSource = settings.mediaWidgetShowSource,
    showArtist = settings.mediaWidgetShowArtist,
    keepLastSong = settings.mediaWidgetKeepLastSong,
    instantControls = settings.mediaWidgetInstantControls,
    animatedFeedback = settings.mediaWidgetAnimatedFeedback,
    layout = layout,
    interaction = interaction,
  )
}

internal enum class MediaWidgetSizeClass {
  Mini,
  Compact,
  Standard,
  Expanded,
  Full,
}

internal data class MediaWidgetLayoutSpec(
  val widthDp: Int,
  val heightDp: Int,
  val sizeClass: MediaWidgetSizeClass,
  val contentPaddingHorizontalDp: Int,
  val contentPaddingVerticalDp: Int,
  val trackGravity: Int,
  val artworkDp: Float,
  val showText: Boolean,
  val showSource: Boolean,
  val showArtist: Boolean,
  val showProgress: Boolean,
  val showMeta: Boolean,
  val showExtraActions: Boolean,
  val showSideControls: Boolean,
  val titleMaxLines: Int,
  val sourcePillHeightDp: Float,
  val sourceSp: Float,
  val titleSp: Float,
  val artistSp: Float,
  val permissionSp: Float,
  val metaSp: Float,
  val controlsRowHeightDp: Int,
  val playButtonDp: Int,
  val sideButtonDp: Int,
  val playIconDp: Int,
  val sideIconDp: Int,
  val extraRowHeightDp: Int,
  val extraButtonDp: Int,
  val extraIconDp: Int,
  val progressHeightDp: Float,
) {
  companion object {
    fun from(options: Bundle?, settings: AppSettings): MediaWidgetLayoutSpec {
      // The size the host will actually draw, when it is willing to say.
      //
      // `OPTION_APPWIDGET_MIN_HEIGHT` is not that: it is the height the cell *guarantees*, which on
      // a four-by-two Pixel cell is about a hundred and ten points against the two hundred and
      // thirty it really gets. Sized from that, every threshold below it fails at once — the card
      // came out with no title, no artist, no progress and no skip buttons, on a widget with room
      // for all four. Android 12 and up hands over the real sizes; the portrait one is the one to
      // build for, because it is the smaller of the two the host will use.
      val sizes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        runCatching {
          @Suppress("DEPRECATION")
          options?.getParcelableArrayList<android.util.SizeF>(
            AppWidgetManager.OPTION_APPWIDGET_SIZES,
          )
        }.getOrNull()?.takeIf { it.isNotEmpty() }
      } else {
        null
      }
      // The list holds one entry per orientation the host may show, so "the portrait one" is the
      // **taller** of them, not the shorter. Picking the shorter is picking the landscape size, and
      // on a phone held upright that is a card built for half the room it has — which is the shape
      // the first attempt at this produced: no words and no skip buttons on a four-by-two cell.
      val landscape = Resources.getSystem().configuration.orientation ==
        Configuration.ORIENTATION_LANDSCAPE
      val chosen = if (landscape) sizes?.minByOrNull { it.height } else sizes?.maxByOrNull { it.height }
      if (chosen != null) {
        return fromDimensions(
          widthDp = chosen.width.toInt().coerceAtLeast(96),
          heightDp = chosen.height.toInt().coerceAtLeast(96),
          settings = settings,
        )
      }

      val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
      val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
      val maxWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0
      val maxHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
      // Below that, the midpoint rather than the floor: the guaranteed minimum understates the cell
      // by about half, and the landscape maximum overstates it by as much.
      val widthDp = midpoint(minWidth, maxWidth, fallback = 250)
      val heightDp = midpoint(minHeight, maxHeight, fallback = 170)
      return fromDimensions(widthDp = widthDp, heightDp = heightDp, settings = settings)
    }

    private fun midpoint(low: Int, high: Int, fallback: Int): Int = when {
      low > 0 && high > 0 -> (low + high) / 2
      low > 0 -> low
      high > 0 -> high
      else -> fallback
    }.coerceAtLeast(96)

    fun fromDimensions(
      widthDp: Int,
      heightDp: Int,
      settings: AppSettings,
    ): MediaWidgetLayoutSpec {
      val sizeClass = when {
        widthDp < 185 || heightDp < 150 -> MediaWidgetSizeClass.Mini
        widthDp < 250 || heightDp < 185 -> MediaWidgetSizeClass.Compact
        heightDp >= 420 || (widthDp >= 430 && heightDp >= 320) -> MediaWidgetSizeClass.Full
        widthDp >= 340 || heightDp >= 260 -> MediaWidgetSizeClass.Expanded
        else -> MediaWidgetSizeClass.Standard
      }

      val horizontalPadding = when (sizeClass) {
        MediaWidgetSizeClass.Mini -> 10
        MediaWidgetSizeClass.Compact -> 12
        MediaWidgetSizeClass.Standard -> 16
        MediaWidgetSizeClass.Expanded -> 20
        MediaWidgetSizeClass.Full -> 24
      }
      val verticalPadding = when (sizeClass) {
        MediaWidgetSizeClass.Mini -> 9
        MediaWidgetSizeClass.Compact -> 12
        MediaWidgetSizeClass.Standard -> 15
        MediaWidgetSizeClass.Expanded -> 20
        MediaWidgetSizeClass.Full -> 24
      }
      val showText = widthDp >= 170 && heightDp >= 135
      val showProgress = sizeClass != MediaWidgetSizeClass.Mini && heightDp >= 164
      val showMeta = sizeClass in setOf(MediaWidgetSizeClass.Expanded, MediaWidgetSizeClass.Full) && heightDp >= 280
      val showExtraActions = sizeClass in setOf(MediaWidgetSizeClass.Expanded, MediaWidgetSizeClass.Full) &&
        (heightDp >= 320 || (widthDp >= 460 && heightDp >= 260))
      val showSideControls = widthDp >= 176 && heightDp >= 118
      val playButton = when (sizeClass) {
        MediaWidgetSizeClass.Mini -> 44
        MediaWidgetSizeClass.Compact -> 50
        MediaWidgetSizeClass.Standard -> 54
        MediaWidgetSizeClass.Expanded -> 60
        MediaWidgetSizeClass.Full -> 68
      }
      val sideButton = when (sizeClass) {
        MediaWidgetSizeClass.Mini -> 36
        MediaWidgetSizeClass.Compact -> 40
        MediaWidgetSizeClass.Standard -> 42
        MediaWidgetSizeClass.Expanded -> 46
        MediaWidgetSizeClass.Full -> 50
      }
      val controlsRowHeight = max(playButton, sideButton)
      val extraRowHeight = if (showExtraActions) {
        when (sizeClass) {
          MediaWidgetSizeClass.Full -> 52
          else -> 46
        }
      } else {
        1
      }
      val progressBlock = if (showProgress) 22 else 0
      val metaBlock = if (showMeta) 30 else 0
      val extraBlock = if (showExtraActions) extraRowHeight + 10 else 0
      val availableTrackHeight = (
        heightDp - verticalPadding * 2 - controlsRowHeight - progressBlock - metaBlock - extraBlock
        ).coerceAtLeast(48)
      val desiredArtwork = when (settings.mediaWidgetArtworkSize) {
        MediaWidgetArtworkSize.Compact -> 82
        MediaWidgetArtworkSize.Balanced -> 98
        MediaWidgetArtworkSize.Large -> 112
      } + when (sizeClass) {
        MediaWidgetSizeClass.Mini -> -24
        MediaWidgetSizeClass.Compact -> -12
        MediaWidgetSizeClass.Standard -> 0
        MediaWidgetSizeClass.Expanded -> 30
        MediaWidgetSizeClass.Full -> 128
      }
      val widthArtworkLimit = if (showText) {
        (widthDp * if (sizeClass == MediaWidgetSizeClass.Full) 0.46f else 0.42f).toInt()
      } else {
        widthDp - horizontalPadding * 2
      }.coerceAtLeast(48)
      val artwork = minOf(desiredArtwork, availableTrackHeight, widthArtworkLimit)
        .coerceAtLeast(if (sizeClass == MediaWidgetSizeClass.Mini) 48 else 58)
        .toFloat()

      return MediaWidgetLayoutSpec(
        widthDp = widthDp,
        heightDp = heightDp,
        sizeClass = sizeClass,
        contentPaddingHorizontalDp = horizontalPadding,
        contentPaddingVerticalDp = verticalPadding,
        trackGravity = if (showText) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER,
        artworkDp = artwork,
        showText = showText,
        showSource = sizeClass != MediaWidgetSizeClass.Mini && heightDp >= 160,
        showArtist = sizeClass !in setOf(MediaWidgetSizeClass.Mini, MediaWidgetSizeClass.Compact) || heightDp >= 176,
        showProgress = showProgress,
        showMeta = showMeta,
        showExtraActions = showExtraActions,
        showSideControls = showSideControls,
        titleMaxLines = when (sizeClass) {
          MediaWidgetSizeClass.Full -> 3
          MediaWidgetSizeClass.Expanded -> 2
          MediaWidgetSizeClass.Standard -> 2
          MediaWidgetSizeClass.Compact -> if (heightDp >= 210) 2 else 1
          else -> 1
        },
        sourcePillHeightDp = if (sizeClass == MediaWidgetSizeClass.Full) 24f else 20f,
        sourceSp = if (sizeClass == MediaWidgetSizeClass.Full) 11.5f else 10f,
        titleSp = when (sizeClass) {
          MediaWidgetSizeClass.Mini -> 13f
          MediaWidgetSizeClass.Compact -> 15f
          MediaWidgetSizeClass.Standard -> 18f
          MediaWidgetSizeClass.Expanded -> 21f
          MediaWidgetSizeClass.Full -> 25f
        },
        artistSp = when (sizeClass) {
          MediaWidgetSizeClass.Full -> 16f
          MediaWidgetSizeClass.Expanded -> 15f
          else -> 14f
        },
        permissionSp = if (sizeClass == MediaWidgetSizeClass.Full) 12f else 11f,
        metaSp = if (sizeClass == MediaWidgetSizeClass.Full) 13f else 12f,
        controlsRowHeightDp = controlsRowHeight,
        playButtonDp = playButton,
        sideButtonDp = sideButton,
        playIconDp = when (sizeClass) {
          MediaWidgetSizeClass.Mini -> 24
          MediaWidgetSizeClass.Compact -> 26
          MediaWidgetSizeClass.Standard -> 28
          MediaWidgetSizeClass.Expanded -> 31
          MediaWidgetSizeClass.Full -> 35
        },
        sideIconDp = when (sizeClass) {
          MediaWidgetSizeClass.Full -> 27
          MediaWidgetSizeClass.Expanded -> 25
          else -> 23
        },
        extraRowHeightDp = extraRowHeight,
        extraButtonDp = if (sizeClass == MediaWidgetSizeClass.Full) 48 else 42,
        extraIconDp = if (sizeClass == MediaWidgetSizeClass.Full) 25 else 22,
        progressHeightDp = if (sizeClass == MediaWidgetSizeClass.Full) 5f else 4f,
      )
    }
  }
}

internal data class WidgetBitmapSize(val width: Int, val height: Int)

internal fun widgetBackgroundBitmapSize(
  widthDp: Int,
  heightDp: Int,
  density: Float,
): WidgetBitmapSize {
  val rawWidth = (widthDp * density).toInt().coerceAtLeast(1)
  val rawHeight = (heightDp * density).toInt().coerceAtLeast(1)
  val longest = maxOf(rawWidth, rawHeight)
  val scale = when {
    longest > BackgroundMaxSidePx -> BackgroundMaxSidePx.toFloat() / longest
    longest < BackgroundMinSidePx -> BackgroundMinSidePx.toFloat() / longest
    else -> 1f
  }
  return WidgetBitmapSize(
    width = (rawWidth * scale).toInt().coerceAtLeast(1),
    height = (rawHeight * scale).toInt().coerceAtLeast(1),
  )
}

internal fun estimatedRemoteViewsBitmapBytes(
  backgroundSize: WidgetBitmapSize,
  artworkSidePx: Int = 320,
): Long = 2L * backgroundSize.width * backgroundSize.height * 4L +
  2L * artworkSidePx * artworkSidePx * 4L

internal data class MediaWidgetColorTokens(
  val backgroundColor: Int,
  val darkSurface: Boolean,
  val primaryTextColor: Int,
  val secondaryTextColor: Int,
  val controlIconColor: Int,
  val disabledControlIconColor: Int,
  val playIconColor: Int,
  val controlSurfaceColor: Int,
  val disabledControlSurfaceColor: Int,
  val playSurfaceColor: Int,
  val sourcePillColor: Int,
  val artworkFrameColor: Int,
  val progressColor: Int,
  val progressTrackColor: Int,
)

internal fun mediaWidgetColorTokens(
  accentColor: Int,
  theme: MediaWidgetTheme,
): MediaWidgetColorTokens {
  val accent = accentColor.enrich(0.08f)
  val background = accent.toWidgetBackgroundColor(theme)
  val darkSurface = when (theme) {
    MediaWidgetTheme.DarkGlass -> true
    MediaWidgetTheme.LightGlass -> false
    else -> background.luminance() < 0.48f
  }
  val controlSurface = background.secondaryControlColor(darkSurface)
  val disabledControlSurface = background.disabledControlColor(darkSurface)
  val playSurface = background.playButtonColor(darkSurface)
  return MediaWidgetColorTokens(
    backgroundColor = background,
    darkSurface = darkSurface,
    primaryTextColor = background.bestContentColor(),
    secondaryTextColor = background.contentVariantColor(darkSurface),
    controlIconColor = controlSurface.bestContentColor(),
    disabledControlIconColor = disabledControlSurface.bestContentColor().blendWith(disabledControlSurface, 0.24f),
    playIconColor = playSurface.bestContentColor(),
    controlSurfaceColor = controlSurface,
    disabledControlSurfaceColor = disabledControlSurface,
    playSurfaceColor = playSurface,
    sourcePillColor = background.sourcePillColor(darkSurface),
    artworkFrameColor = background.artworkFrameColor(darkSurface),
    progressColor = playSurface,
    progressTrackColor = controlSurface,
  )
}

private data class MediaWidgetStyle(
  val background: Bitmap,
  val backgroundSignature: String,
  val primaryTextColor: Int,
  val secondaryTextColor: Int,
  val controlIconColor: Int,
  val disabledControlIconColor: Int,
  val playIconColor: Int,
  val controlSurfaceColor: Int,
  val disabledControlSurfaceColor: Int,
  val playSurfaceColor: Int,
  val sourcePillColor: Int,
  val artworkFrameColor: Int,
  val progressColor: Int,
  val progressTrackColor: Int,
) {
  companion object {
    fun from(
      context: Context,
      artwork: Bitmap?,
      settings: AppSettings,
      layout: MediaWidgetLayoutSpec,
    ): MediaWidgetStyle {
      val palette = extractPalette(artwork)
      val tokens = mediaWidgetColorTokens(palette.vibrant, settings.mediaWidgetTheme)
      val density = context.resources.displayMetrics.density
      val backgroundSize = widgetBackgroundBitmapSize(layout.widthDp, layout.heightDp, density)
      val background = createBackgroundBitmap(
        backgroundColor = tokens.backgroundColor,
        width = backgroundSize.width,
        height = backgroundSize.height,
        darkSurface = tokens.darkSurface,
      )
      return MediaWidgetStyle(
        background = background,
        backgroundSignature = "${tokens.backgroundColor}:${layout.widthDp}x${layout.heightDp}",
        primaryTextColor = tokens.primaryTextColor,
        secondaryTextColor = tokens.secondaryTextColor,
        controlIconColor = tokens.controlIconColor,
        disabledControlIconColor = tokens.disabledControlIconColor,
        playIconColor = tokens.playIconColor,
        controlSurfaceColor = tokens.controlSurfaceColor,
        disabledControlSurfaceColor = tokens.disabledControlSurfaceColor,
        playSurfaceColor = tokens.playSurfaceColor,
        sourcePillColor = tokens.sourcePillColor,
        artworkFrameColor = tokens.artworkFrameColor,
        progressColor = tokens.progressColor,
        progressTrackColor = tokens.progressTrackColor,
      )
    }

    private fun createBackgroundBitmap(
      backgroundColor: Int,
      width: Int,
      height: Int,
      darkSurface: Boolean,
    ): Bitmap {
      val radius = (min(width, height) * 0.14f).coerceIn(44f, 84f)
      val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())

      val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val layerCanvas = Canvas(layer)

      val topTone = backgroundColor.lighten(0.055f)
      val middleTone = backgroundColor.lighten(0.015f)
      val lowerTone = backgroundColor.darken(0.025f)
      val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
          0f,
          0f,
          width.toFloat(),
          height.toFloat(),
          intArrayOf(topTone, middleTone, lowerTone),
          floatArrayOf(0f, 0.54f, 1f),
          Shader.TileMode.CLAMP,
        )
      }
      layerCanvas.drawPath(continuousWidgetPath(bounds, radius), gradientPaint)

      // Mask everything into the rounded card.
      val canvas = Canvas(output)
      val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
      canvas.drawPath(continuousWidgetPath(bounds, radius), maskPaint)
      val contentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
      }
      canvas.drawBitmap(layer, 0f, 0f, contentPaint)
      contentPaint.xfermode = null

      // 1px inner highlight stroke for the premium glass rim.
      val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        color = if (darkSurface) backgroundColor.lighten(0.18f) else backgroundColor.darken(0.10f)
      }
      canvas.drawPath(continuousWidgetPath(bounds.insetBy(0.7f), radius), rimPaint)
      return output
    }
  }
}

// ---------- palette ----------

private data class CoverPalette(val dominant: Int, val vibrant: Int)

/**
 * Extracts a [dominant] average and a [vibrant] colour (high saturation × brightness) from
 * the artwork. The vibrant pick is what makes the background read as the cover's colour
 * rather than a muddy grey.
 */
private fun extractPalette(artwork: Bitmap?): CoverPalette {
  if (artwork == null) {
    val base = Color.rgb(58, 84, 116)
    return CoverPalette(dominant = base, vibrant = base)
  }
  val src = if (artwork.width > 96 || artwork.height > 96) {
    Bitmap.createScaledBitmap(artwork, 96, 96, true)
  } else {
    artwork
  }
  val w = src.width
  val h = src.height
  var rSum = 0L
  var gSum = 0L
  var bSum = 0L
  var count = 0L

  var accentRSum = 0.0
  var accentGSum = 0.0
  var accentBSum = 0.0
  var accentWeightSum = 0.0

  var bestScore = -1f
  var bestR = 0
  var bestG = 0
  var bestB = 0
  var bestSat = 0f
  var bestBrightness = 0f

  var y = 0
  while (y < h) {
    var x = 0
    while (x < w) {
      val c = src.getPixel(x, y)
      if (Color.alpha(c) < 160) { x += 2; continue }
      val r = Color.red(c)
      val g = Color.green(c)
      val b = Color.blue(c)
      rSum += r; gSum += g; bSum += b; count++
      val maxC = max(r, max(g, b))
      val minC = min(r, min(g, b))
      val brightness = maxC / 255f
      val sat = if (maxC == 0) 0f else (maxC - minC) / maxC.toFloat()
      val balance = 1f - (abs(brightness - 0.62f) / 0.62f).coerceIn(0f, 1f)
      val score = sat * (0.45f + 0.55f * brightness) * (0.65f + 0.35f * balance)
      if (score > bestScore) {
        bestScore = score
        bestR = r; bestG = g; bestB = b
        bestSat = sat
        bestBrightness = brightness
      }
      if (sat >= 0.16f && brightness in 0.14f..0.94f) {
        val weight = (sat * sat) * (0.50f + 0.50f * brightness) * (0.70f + 0.30f * balance)
        accentRSum += r * weight
        accentGSum += g * weight
        accentBSum += b * weight
        accentWeightSum += weight
      }
      x += 2
    }
    y += 2
  }

  val dominant = if (count == 0L) {
    Color.rgb(58, 84, 116)
  } else {
    Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
  }

  val vibrant = if (accentWeightSum >= 1.0) {
    Color.rgb(
      (accentRSum / accentWeightSum).toInt().coerceIn(0, 255),
      (accentGSum / accentWeightSum).toInt().coerceIn(0, 255),
      (accentBSum / accentWeightSum).toInt().coerceIn(0, 255),
    ).enrich(0.16f)
  } else if (bestSat >= 0.14f && bestBrightness >= 0.12f) {
    Color.rgb(bestR, bestG, bestB).enrich(0.12f)
  } else {
    dominant.enrich(0.45f)
  }
  return CoverPalette(dominant = dominant, vibrant = vibrant)
}

// ---------- bitmap helpers ----------

private fun MediaPlaybackSnapshot.progressPermille(): Int {
  if (durationMs <= 0L) return 0
  return ((positionMs.coerceIn(0L, durationMs) * 1000L) / durationMs)
    .toInt()
    .coerceIn(0, 1000)
}

private fun MediaPlaybackSnapshot.statusLabel(): String =
  when (availability) {
    MediaPlaybackAvailability.PermissionRequired -> "Accesso richiesto"
    MediaPlaybackAvailability.AppMissing -> "App non installata"
    MediaPlaybackAvailability.NoSession -> if (isFromCache) "Ultima sessione" else "Nessuna sessione"
    MediaPlaybackAvailability.Active -> if (isPlaying) "In riproduzione" else "In pausa"
  }

private fun MediaWidgetInteraction.statusLabel(): String =
  when (action) {
    MediaControlAction.TogglePlayPause -> if (expectedPlaying == true) {
      "Avvio riproduzione…"
    } else {
      "Metto in pausa…"
    }
    MediaControlAction.Next -> "Passo al brano successivo…"
    MediaControlAction.Previous -> "Torno al brano precedente…"
  }

private fun MediaPlaybackSnapshot.artworkSignature(): String =
  "$trackKey|${artworkKey.ifBlank { artwork?.widgetContentSignature().orEmpty() }}|${artwork != null}"

private fun Long.clockLabel(): String {
  val totalSeconds = (this / 1000L).coerceAtLeast(0L)
  val seconds = (totalSeconds % 60).toInt()
  val minutes = ((totalSeconds / 60) % 60).toInt()
  val hours = (totalSeconds / 3600).toInt()
  fun two(value: Int) = if (value < 10) "0$value" else value.toString()
  return if (hours > 0) {
    "$hours:${two(minutes)}:${two(seconds)}"
  } else {
    "$minutes:${two(seconds)}"
  }
}

private fun RemoteViews.setSquareDp(viewId: Int, sizeDp: Int) {
  setViewLayoutWidth(viewId, sizeDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
  setViewLayoutHeight(viewId, sizeDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
}

private fun RemoteViews.setScale(viewId: Int, scale: Float) {
  setFloat(viewId, "setScaleX", scale)
  setFloat(viewId, "setScaleY", scale)
}

private fun Bitmap.roundedSquare(): Bitmap {
  val size = minOf(width, height)
  val left = (width - size) / 2
  val top = (height - size) / 2
  val output = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(output)
  val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  val rect = Rect(0, 0, output.width, output.height)
  val source = Rect(left, top, left + size, top + size)
  val rounded = RectF(rect)
  canvas.drawPath(continuousWidgetPath(rounded, min(rounded.width(), rounded.height()) * 0.22f), paint.apply { color = Color.WHITE })
  paint.shader = null
  paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
  canvas.drawBitmap(this, source, rect, paint)
  paint.xfermode = null
  return output
}

private fun Bitmap.widgetContentSignature(): String {
  var hash = 1125899906842597L
  val samples = 6
  repeat(samples) { yIndex ->
    repeat(samples) { xIndex ->
      val x = ((width - 1) * xIndex / (samples - 1)).coerceAtLeast(0)
      val y = ((height - 1) * yIndex / (samples - 1)).coerceAtLeast(0)
      hash = hash * 31L + getPixel(x, y)
    }
  }
  return "${width}x$height:${hash.toULong().toString(16)}"
}

internal fun Int.luminance(): Float =
  (0.299f * Color.red(this) + 0.587f * Color.green(this) + 0.114f * Color.blue(this)) / 255f

internal fun Int.toWidgetBackgroundColor(theme: MediaWidgetTheme): Int {
  val source = enrich(0.10f)
  val color = when (theme) {
    MediaWidgetTheme.SamsungGlass -> source.lighten(0.42f)
    MediaWidgetTheme.AdaptiveGlass -> source.lighten(if (source.luminance() < 0.45f) 0.46f else 0.34f)
    MediaWidgetTheme.LightGlass -> source.lighten(0.68f).blendWith(Color.rgb(250, 251, 246), 0.24f)
    MediaWidgetTheme.DarkGlass -> source.darken(0.48f).blendWith(Color.rgb(18, 22, 20), 0.30f)
    MediaWidgetTheme.AlbumColor -> source
  }
  return when {
    theme == MediaWidgetTheme.AlbumColor && color.luminance() > 0.82f -> color.darken(0.08f)
    theme == MediaWidgetTheme.AlbumColor && color.luminance() < 0.12f -> color.lighten(0.10f)
    else -> color
  }
}

private fun Int.bestContentColor(): Int =
  if (luminance() < 0.50f) Color.rgb(250, 255, 251) else Color.rgb(15, 20, 18)

private fun Int.contentVariantColor(darkSurface: Boolean): Int =
  if (darkSurface) Color.rgb(220, 231, 224) else Color.rgb(45, 53, 49)

internal fun Int.playButtonColor(darkSurface: Boolean): Int =
  if (darkSurface) lighten(0.24f) else darken(0.14f)

private fun Int.secondaryControlColor(darkSurface: Boolean): Int =
  if (darkSurface) lighten(0.12f) else darken(0.055f)

private fun Int.disabledControlColor(darkSurface: Boolean): Int =
  if (darkSurface) lighten(0.06f) else darken(0.025f)

private fun Int.sourcePillColor(darkSurface: Boolean): Int =
  if (darkSurface) lighten(0.13f) else lighten(0.08f)

private fun Int.artworkFrameColor(darkSurface: Boolean): Int =
  if (darkSurface) lighten(0.09f) else lighten(0.12f)

private fun Int.lighten(amount: Float): Int = Color.rgb(
  (Color.red(this) + (255 - Color.red(this)) * amount).toInt().coerceIn(0, 255),
  (Color.green(this) + (255 - Color.green(this)) * amount).toInt().coerceIn(0, 255),
  (Color.blue(this) + (255 - Color.blue(this)) * amount).toInt().coerceIn(0, 255),
)

private fun Int.darken(amount: Float): Int = Color.rgb(
  (Color.red(this) * (1f - amount)).toInt().coerceIn(0, 255),
  (Color.green(this) * (1f - amount)).toInt().coerceIn(0, 255),
  (Color.blue(this) * (1f - amount)).toInt().coerceIn(0, 255),
)

private fun Int.blendWith(other: Int, amount: Float): Int {
  val t = amount.coerceIn(0f, 1f)
  fun channel(a: Int, b: Int) = (a * (1f - t) + b * t).toInt().coerceIn(0, 255)
  return Color.rgb(
    channel(Color.red(this), Color.red(other)),
    channel(Color.green(this), Color.green(other)),
    channel(Color.blue(this), Color.blue(other)),
  )
}

/** Pushes a greyish colour toward a richer version of itself by stretching RGB away from grey. */
private fun Int.enrich(amount: Float): Int {
  val r = Color.red(this)
  val g = Color.green(this)
  val b = Color.blue(this)
  val grey = (r + g + b) / 3
  fun channel(c: Int) = (grey + (c - grey) * (1f + amount)).toInt().coerceIn(0, 255)
  return Color.rgb(channel(r), channel(g), channel(b))
}

private fun RectF.insetBy(amount: Float): RectF =
  RectF(left + amount, top + amount, right - amount, bottom - amount)

private fun Context.dp(value: Int): Int =
  (value * resources.displayMetrics.density).toInt()

/** Continuous tangent corners for the opaque RemoteViews surfaces and artwork mask. */
private fun continuousWidgetPath(bounds: RectF, corner: Float): Path {
  val r = corner.coerceAtMost(min(bounds.width(), bounds.height()) / 2)
  val l = bounds.left; val t = bounds.top; val b = bounds.bottom; val right = bounds.right
  val c = r * 0.22f
  return Path().apply {
    moveTo(l + r, t); lineTo(right - r, t)
    cubicTo(right - c, t, right, t + c, right, t + r)
    lineTo(right, b - r); cubicTo(right, b - c, right - c, b, right - r, b)
    lineTo(l + r, b); cubicTo(l + c, b, l, b - c, l, b - r)
    lineTo(l, t + r); cubicTo(l, t + c, l + c, t, l + r, t); close()
  }
}
