/*
 * Ported from Pampa Widgets (com.pampa.widgets.widget.media), the same author's widget app.
 *
 * SCENA v2 replaces the original thumbnail composition with a full-surface scene.
 * Playback snapshots and explicit app-owned PendingIntent routing remain native.
 */
package dev.lelonio.square.widget.media

import android.app.UiModeManager
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
import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Path
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.FluidSystemContrast
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.fluidColorScheme
import dev.lelonio.square.ui.theme.Amethyst
import dev.lelonio.square.ui.MainActivity
import dev.lelonio.square.R

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Background render bounds used for launcher-size-specific bitmaps. */
private const val BackgroundMinSidePx = 220
private const val BackgroundMaxSidePx = 384

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
 * Builds static RemoteViews for the media widget.
 *
 * SCENA: one bounded artwork composite with local reading frost and native controls.
 */
object MediaWidgetUpdater {
  private val stateLock = Any()
  private val visualStates = mutableMapOf<Int, MediaWidgetVisualState>()
  @Volatile private var contrastObserved = false

  private fun observePhotoContrast(context: Context) {
    if (android.os.Build.VERSION.SDK_INT < 34 || contrastObserved) return
    synchronized(stateLock) {
      if (contrastObserved) return
      val app = context.applicationContext
      val manager = app.getSystemService(UiModeManager::class.java) ?: return
      // Process-lifetime listener, application context only; retain no snapshot or bitmap.
      manager.addContrastChangeListener(app.mainExecutor, UiModeManager.ContrastChangeListener {
        MediaWidgetStyle.clearCache()
        // Existing redraw route preserves the bridge's offline/cache guards.
        NowPlayingWidgetBridge.redraw(app)
      })
      contrastObserved = true
    }
  }

  /** Draw these widgets with this snapshot. The one way in; see NowPlayingWidgetBridge. */
  fun update(
    context: Context,
    appWidgetIds: IntArray,
    snapshot: MediaPlaybackSnapshot,
    settings: AppSettings,
  ) {
    if (appWidgetIds.isNotEmpty()) observePhotoContrast(context)
    updateWithSnapshot(context, AppWidgetManager.getInstance(context), appWidgetIds, snapshot, settings)
  }

  fun onDeleted(appWidgetIds: IntArray) {
    synchronized(stateLock) {
      appWidgetIds.forEach(visualStates::remove)
      MediaWidgetStyle.clearCache()
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
      fontScale = context.resources.configuration.fontScale,
    )
    val style = MediaWidgetStyle.from(context, snapshot, settings, layout)
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
      val options = appWidgetManager.getAppWidgetOptions(widgetId)
      val sizes = if (android.os.Build.VERSION.SDK_INT >= 31) {
        @Suppress("DEPRECATION")
        options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
          ?.filter { it.width.isFinite() && it.height.isFinite() && it.width >= 48 && it.height >= 48 }
          ?.distinct()?.take(4)
      } else null
      fun render(layout: MediaWidgetLayoutSpec): RemoteViews {
        val style = MediaWidgetStyle.from(context, snapshot, settings, layout)
        val plan = visualPlanFor(widgetId, snapshot, style, animateChanges = false)
        return buildRemoteViews(context, snapshot, settings, style, layout, plan, interaction, widgetId)
      }
      val views = if (android.os.Build.VERSION.SDK_INT >= 31 && !sizes.isNullOrEmpty()) {
        RemoteViews(sizes.associateWith { size ->
          render(MediaWidgetLayoutSpec.fromDimensions(size.width.toInt(), size.height.toInt(), settings,
            context.resources.configuration.fontScale))
        })
      } else {
        val legacy = MediaWidgetLayoutSpec.from(options, settings)
        render(MediaWidgetLayoutSpec.fromDimensions(legacy.widthDp, legacy.heightDp, settings,
          context.resources.configuration.fontScale))
      }
      // Replace image actions atomically; partial updates retain old image allocations.
      appWidgetManager.updateAppWidget(widgetId, views)
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
    val animatePlayback = false
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
    // Match the baked material to the widget's own locale direction, not a stale host parent.
    views.setInt(R.id.media_widget_root, "setLayoutDirection", context.resources.configuration.layoutDirection)

    applyBackground(views, style, visualPlan)
    applyLayout(context, views, style, layout)
    views.setTextViewText(R.id.media_widget_source, snapshot.sourceLabel)
    views.setTextViewText(R.id.media_widget_title, snapshot.title.ifBlank { context.getString(R.string.widget_v2_empty) })
    views.setTextViewText(R.id.media_widget_artist, snapshot.artist)
    views.setTextViewText(R.id.media_widget_status, context.getString(if (snapshot.availability != MediaPlaybackAvailability.Active) R.string.widget_v2_empty else if (snapshot.isBuffering) R.string.widget_v2_loading else if (snapshot.isPlaying) R.string.widget_v2_playing else R.string.widget_v2_paused))
    views.setTextColor(R.id.media_widget_source, style.sourceTextColor)
    views.setTextColor(R.id.media_widget_title, style.primaryTextColor)
    views.setTextColor(R.id.media_widget_artist, style.secondaryTextColor)
    views.setTextColor(R.id.media_widget_permission, style.secondaryTextColor)
    views.setTextColor(R.id.media_widget_status, style.secondaryTextColor)
    views.setTextColor(R.id.media_widget_time, style.secondaryTextColor)
    views.setColorStateListCompat(
      R.id.media_widget_source,
      "setBackgroundTintList",
      ColorStateList.valueOf(style.sourcePillColor),
    )
    views.setColorStateListCompat(
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

    views.setViewVisibility(R.id.media_widget_artwork_frame, View.GONE)
    applyControls(context, views, snapshot, settings, style, layout, visualPlan, interaction)
    applyProgress(views, snapshot, style, layout)
    applyTime(views, snapshot, layout)

    val playIntent = if (snapshot.availability == MediaPlaybackAvailability.PermissionRequired) {
      settingsPendingIntent(context, widgetId)
    } else {
      broadcastPendingIntent(context, NowPlayingWidget.ActionTogglePlayPause, 1, widgetId)
    }
    views.setOnClickPendingIntent(R.id.media_widget_play_pause, playIntent)
    // Only the enabled ImageButton owns this hit area.
    views.setOnClickPendingIntent(R.id.media_widget_permission, settingsPendingIntent(context, widgetId))
    val openMediaIntent = openMediaAppPendingIntent(context, snapshot, widgetId)
    views.setOnClickPendingIntent(R.id.media_widget_background, openMediaIntent)
    views.setContentDescription(R.id.media_widget_background, context.getString(R.string.widget_v2_open))
    // Text stack and artwork background open the existing player destination.
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
    // Single composited image; the compatibility second slot remains unused.
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
    views.setViewLayoutHeightCompat(
      R.id.media_widget_controls,
      layout.controlsRowHeightDp.toFloat(),
      TypedValue.COMPLEX_UNIT_DIP,
    )
    views.setViewLayoutHeightCompat(
      R.id.media_widget_extra_actions,
      layout.extraRowHeightDp.toFloat(),
      TypedValue.COMPLEX_UNIT_DIP,
    )
    views.setViewLayoutHeightCompat(
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
    views.setInt(R.id.media_widget_time, "setMinWidth", style.clockWidthPx)
    views.setInt(R.id.media_widget_time, "setGravity", if (style.clockWidthPx > 0) Gravity.END else Gravity.TOP or Gravity.START)
    views.setInt(R.id.media_widget_title, "setMaxLines", layout.titleMaxLines)
    views.setViewVisibility(R.id.media_widget_meta_row, if (layout.showMeta) View.VISIBLE else View.GONE)
    views.setViewVisibility(
      R.id.media_widget_extra_actions,
      if (layout.showExtraActions) View.VISIBLE else View.GONE,
    )
    if (style.glassControls) {
      listOf(R.id.media_widget_previous, R.id.media_widget_play_pause, R.id.media_widget_next,
        R.id.media_widget_refresh, R.id.media_widget_open_media, R.id.media_widget_open_pampa).forEach {
        views.setInt(it, "setBackgroundResource", R.drawable.bg_media_control_scene_glass)
        if (android.os.Build.VERSION.SDK_INT >= 31) views.setColorStateList(it, "setBackgroundTintList", null)
      }
    } else {
      listOf(R.id.media_widget_refresh, R.id.media_widget_open_media, R.id.media_widget_open_pampa).forEach {
        views.setColorStateListCompat(it, "setBackgroundTintList", ColorStateList.valueOf(style.controlSurfaceColor))
      }
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
    val playEnabled = snapshot.availability == MediaPlaybackAvailability.Active && snapshot.canPlayPause
    views.setContentDescription(R.id.media_widget_play_pause, context.getString(if (snapshot.isPlaying) R.string.widget_v2_pause else R.string.widget_v2_play))
    views.setContentDescription(R.id.media_widget_open_pampa, context.getString(R.string.widget_v2_open))
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

    if (!style.glassControls) {
      views.setColorStateListCompat(
        R.id.media_widget_previous,
        "setBackgroundTintList",
        ColorStateList.valueOf(
          if (snapshot.canSkipPrevious) style.controlSurfaceColor else style.disabledControlSurfaceColor,
        ),
      )
      views.setColorStateListCompat(
        R.id.media_widget_next,
        "setBackgroundTintList",
        ColorStateList.valueOf(
          if (snapshot.canSkipNext) style.controlSurfaceColor else style.disabledControlSurfaceColor,
        ),
      )
      views.setColorStateListCompat(
        R.id.media_widget_play_pause,
        "setBackgroundTintList",
        ColorStateList.valueOf(if (playEnabled) style.playSurfaceColor else style.disabledControlSurfaceColor),
      )
    }
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
    views.setColorStateListCompat(
      R.id.media_widget_previous,
      "setImageTintList",
      ColorStateList.valueOf(if (snapshot.canSkipPrevious) style.controlIconColor else style.disabledControlIconColor),
    )
    views.setColorStateListCompat(
      R.id.media_widget_next,
      "setImageTintList",
      ColorStateList.valueOf(if (snapshot.canSkipNext) style.controlIconColor else style.disabledControlIconColor),
    )
    listOf(R.id.media_widget_play_glyph, R.id.media_widget_pause_glyph).forEach { glyphId ->
      views.setColorStateListCompat(
        glyphId,
        "setImageTintList",
        ColorStateList.valueOf(if (playEnabled) style.playIconColor else style.disabledControlIconColor),
      )
    }
    listOf(R.id.media_widget_refresh, R.id.media_widget_open_media, R.id.media_widget_open_pampa).forEach { iconId ->
      views.setColorStateListCompat(iconId, "setImageTintList", ColorStateList.valueOf(style.controlIconColor))
    }
    views.setColorStateListCompat(
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
    views.setViewLayoutHeightCompat(
      R.id.media_widget_progress,
      layout.progressHeightDp,
      TypedValue.COMPLEX_UNIT_DIP,
    )
    views.setViewVisibility(
      R.id.media_widget_progress,
      when {
        !layout.showProgress -> View.GONE
        snapshot.durationMs > 0 && snapshot.positionKnown && snapshot.availability == MediaPlaybackAvailability.Active -> View.VISIBLE
        else -> View.INVISIBLE
      },
    )
    views.setColorStateListCompat(
      R.id.media_widget_progress,
      "setProgressTintList",
      ColorStateList.valueOf(style.progressColor),
    )
    views.setColorStateListCompat(
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
    val showTime = layout.showMeta && snapshot.durationMs > 0L && snapshot.positionKnown && snapshot.availability == MediaPlaybackAvailability.Active
    views.setViewVisibility(R.id.media_widget_time, if (showTime) View.VISIBLE else View.GONE)
    if (showTime) views.setTextViewText(R.id.media_widget_time,
      "${snapshot.positionMs.coerceIn(0L, snapshot.durationMs).clockLabel()} / ${snapshot.durationMs.clockLabel()}")
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
    val style = MediaWidgetStyle.from(context, snapshot, settings, layout)
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
    // The receiver for the card's own actions, which is not exported: see NowPlayingWidgetActions.
    val intent = Intent(context, NowPlayingWidgetActions::class.java).setAction(action)
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
  val fontScale: Float = 1f,
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
      // Legacy orientation pairs from the host are estimates, not launcher cell pixels.
      val widthDp = (if (landscape) maxWidth else minWidth).takeIf { it > 0 } ?: 250
      val heightDp = (if (landscape) minHeight else maxHeight).takeIf { it > 0 } ?: 170
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
      fontScale: Float = 1f,
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
      val showText = widthDp >= 150 && heightDp >= (100 + 35 * fontScale)
      val showProgress = showText && heightDp >= (132 + 30 * fontScale)
      val showMeta = sizeClass in setOf(MediaWidgetSizeClass.Expanded, MediaWidgetSizeClass.Full) && widthDp >= (260 * fontScale) && heightDp >= (240 + 40 * fontScale)
      val showExtraActions = sizeClass in setOf(MediaWidgetSizeClass.Expanded, MediaWidgetSizeClass.Full) &&
        widthDp >= 224 && heightDp >= (280 + 40 * fontScale)
      val showSideControls = widthDp >= 2 * horizontalPadding + 50 * 2 + 68 + 28 && heightDp >= 118
      val playButton = when (sizeClass) {
        MediaWidgetSizeClass.Mini -> 48
        MediaWidgetSizeClass.Compact -> 50
        MediaWidgetSizeClass.Standard -> 54
        MediaWidgetSizeClass.Expanded -> 60
        MediaWidgetSizeClass.Full -> 68
      }
      val sideButton = when (sizeClass) {
        MediaWidgetSizeClass.Mini -> 48
        MediaWidgetSizeClass.Compact -> 48
        MediaWidgetSizeClass.Standard -> 48
        MediaWidgetSizeClass.Expanded -> 48
        MediaWidgetSizeClass.Full -> 50
      }
      val controlsRowHeight = max(playButton, sideButton)
      val extraRowHeight = if (showExtraActions) {
        when (sizeClass) {
          MediaWidgetSizeClass.Full -> 52
          else -> 48
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
        showArtist = showText && heightDp >= (132 + 30 * fontScale),
        showProgress = showProgress,
        showMeta = showMeta,
        showExtraActions = showExtraActions,
        showSideControls = showSideControls,
        titleMaxLines = if (fontScale > 1.3f) 1 else when (sizeClass) {
          MediaWidgetSizeClass.Full, MediaWidgetSizeClass.Expanded, MediaWidgetSizeClass.Standard -> 2
          else -> 1
        },
        sourcePillHeightDp = if (sizeClass == MediaWidgetSizeClass.Full) 24f else 20f,
        sourceSp = if (sizeClass == MediaWidgetSizeClass.Full) 11.5f else 10f,
        titleSp = when (sizeClass) {
          MediaWidgetSizeClass.Mini -> 13f
          MediaWidgetSizeClass.Compact -> 17f
          MediaWidgetSizeClass.Standard -> 18f
          MediaWidgetSizeClass.Expanded -> 22f
          MediaWidgetSizeClass.Full -> 25f
        },
        artistSp = when (sizeClass) {
          MediaWidgetSizeClass.Full -> 16f
          MediaWidgetSizeClass.Expanded -> 15f
          else -> 13f
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
        extraButtonDp = 48,
        extraIconDp = if (sizeClass == MediaWidgetSizeClass.Full) 25 else 22,
        fontScale = fontScale,
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
): Long = backgroundSize.width.toLong() * backgroundSize.height * 4L

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
  val glassControls: Boolean,
  val background: Bitmap,
  val backgroundSignature: String,
  val primaryTextColor: Int,
  val sourceTextColor: Int,
  val clockWidthPx: Int,
  val foregroundIdentity: String?,
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
    private val cache = android.util.LruCache<String, MediaWidgetStyle>(8)
    fun clearCache() { synchronized(cache) { cache.evictAll() } }
    fun from(
      context: Context,
      snapshot: MediaPlaybackSnapshot,
      settings: AppSettings,
      layout: MediaWidgetLayoutSpec,
    ): MediaWidgetStyle {
      val artwork = snapshot.artwork?.takeUnless { it.isRecycled }
      val glassControls = android.os.Build.VERSION.SDK_INT >= 31
      val photo = artwork != null && glassControls
      val systemContrast = if (android.os.Build.VERSION.SDK_INT >= 34) runCatching {
        FluidSystemContrast.level(context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f)
      }.getOrDefault(0f) else 0f
      val textKey = listOf(snapshot.title, snapshot.artist, snapshot.sourceLabel).joinToString("") { "${it.length}:$it" }
      // Cache the geometry, not the advancing timestamp. Equal-width clock strings
      // reuse the scene; a digit-count/visibility change gets the correctly sized halo.
      val timeWidthKey: Any? = if (photo && layout.showMeta) sceneTimeLabel(snapshot)?.map {
        if (it.isDigit()) '#' else it
      }?.joinToString("") else if (layout.showMeta) sceneTimeLabel(snapshot)?.let {
        kotlin.math.ceil(Layout.getDesiredWidth(it, sceneTextPaint(context, layout.metaSp,
          "sans-serif-medium", tracking = -0.02f)).toDouble()).toInt()
      } else null
      val key = "${artwork?.generationId}:${artwork?.hashCode()}:${settings}:$layout:${context.resources.configuration}:" +
        "${snapshot.availability}:${snapshot.canPlayPause}:${snapshot.canSkipPrevious}:${snapshot.canSkipNext}:" +
        "$textKey:${snapshot.isPlaying}:${snapshot.isBuffering}:$glassControls:$timeWidthKey:photo-v4.2:$systemContrast"
      synchronized(cache) { cache.get(key)?.let { return it } }
      val foregroundIdentity = if (photo) "photo-v4.2:${artwork?.generationId}:${artwork?.hashCode()}:$settings:$layout:" +
        "${context.resources.configuration}:$textKey:${snapshot.availability}:$systemContrast" else null
      // Reuse the SAME LRU8, not a second polarity cache. Cold selection below also uses
      // canonical status geometry, so eviction cannot make play/pause pick another ink.
      val frozenInk = if (foregroundIdentity == null) null else synchronized(cache) {
        cache.snapshot().values.firstOrNull { it.foregroundIdentity == foregroundIdentity }?.primaryTextColor
      }
      val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
      val dark = when (settings.mediaWidgetTheme) {
        MediaWidgetTheme.DarkGlass -> true
        MediaWidgetTheme.LightGlass -> false
        else -> systemDark
      }
      val scheme = fluidColorScheme(EngineSettings(dynamicColorEnabled = false, amoledEnabled = settings.amoled), dark, Amethyst)
      val accent = artwork?.let { extractPalette(it).vibrant } ?: scheme.primary.toArgb()
      val base = scheme.surfaceContainerLow.toArgb().blendWith(accent, 0.04f)
      val primary = scheme.primaryContainer.toArgb()
      val tokens = mediaWidgetColorTokens(accent, if (dark) MediaWidgetTheme.DarkGlass else MediaWidgetTheme.LightGlass).copy(
        backgroundColor = base, primaryTextColor = scheme.onSurface.toArgb(),
        secondaryTextColor = scheme.onSurface.toArgb(), controlIconColor = scheme.onSurface.toArgb(),
        controlSurfaceColor = base, playSurfaceColor = primary, playIconColor = scheme.onPrimaryContainer.toArgb(),
        progressColor = scheme.primary.toArgb(), progressTrackColor = scheme.outline.toArgb())
      val density = context.resources.displayMetrics.density
      val requested = widgetBackgroundBitmapSize(layout.widthDp, layout.heightDp, density)
      // Four exact-size variants together use at most one display buffer (plus vector icons).
      val maxSide = kotlin.math.sqrt((context.resources.displayMetrics.widthPixels.toLong() *
        context.resources.displayMetrics.heightPixels / 4).coerceAtLeast(1).toDouble()).toInt().coerceAtMost(384)
      val ratio = min(1f, maxSide.toFloat() / max(requested.width, requested.height))
      val backgroundSize = WidgetBitmapSize((requested.width * ratio).toInt().coerceAtLeast(1),
        (requested.height * ratio).toInt().coerceAtLeast(1))
      val textMetrics = if (layout.showText) measureSceneText(context, snapshot, settings, layout, stableClock = photo) else SceneTextMetrics(emptyList(), null, null)
      val lightScheme = if (photo) fluidColorScheme(EngineSettings(dynamicColorEnabled = false, amoledEnabled = settings.amoled), false, Amethyst) else scheme
      val darkScheme = if (photo) fluidColorScheme(EngineSettings(dynamicColorEnabled = false, amoledEnabled = settings.amoled), true, Amethyst) else scheme
      val composed = if (photo) photoScene(context, artwork!!, layout, textMetrics, backgroundSize, tokens,
        lightInk = darkScheme.onSurface.toArgb(), darkInk = lightScheme.onSurface.toArgb(),
        lightSurface = lightScheme.surfaceContainerLow.toArgb(), darkSurface = darkScheme.surfaceContainerLow.toArgb(),
        shadow = scheme.scrim.toArgb(), film = scheme.surfaceContainerHigh.toArgb().blendWith(scheme.primary.toArgb(), 0.18f), contrast = systemContrast, preferredInk = frozenInk, snapshot = snapshot) else null
      val background = composed?.bitmap ?: createBackgroundBitmap(
        backgroundColor = tokens.backgroundColor,
        width = backgroundSize.width,
        height = backgroundSize.height,
        darkSurface = tokens.darkSurface,
        contentColor = tokens.primaryTextColor,
        shadowColor = scheme.scrim.toArgb(),
        controlFilm = scheme.surfaceContainerHigh.toArgb().blendWith(scheme.primary.toArgb(), 0.20f),
        tokens = tokens,
        snapshot = snapshot,
        glassControls = glassControls,
        rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
        textMetrics = textMetrics,
        artwork = artwork,
        layout = layout,
      )
      return MediaWidgetStyle(
        glassControls = glassControls,
        background = background,
        backgroundSignature = "$key:ink=${composed?.ink}",
        primaryTextColor = composed?.ink ?: tokens.primaryTextColor,
        sourceTextColor = tokens.primaryTextColor,
        foregroundIdentity = foregroundIdentity,
        clockWidthPx = if (photo) ((textMetrics.time?.widthDp ?: 0f) * density).roundToInt() else 0,
        secondaryTextColor = composed?.ink ?: tokens.secondaryTextColor,
        controlIconColor = composed?.ink ?: tokens.controlIconColor,
        disabledControlIconColor = composed?.ink ?: tokens.disabledControlIconColor,
        playIconColor = composed?.ink ?: tokens.playIconColor,
        controlSurfaceColor = tokens.controlSurfaceColor,
        disabledControlSurfaceColor = tokens.disabledControlSurfaceColor,
        playSurfaceColor = tokens.playSurfaceColor,
        sourcePillColor = tokens.sourcePillColor,
        artworkFrameColor = tokens.artworkFrameColor,
        progressColor = tokens.progressColor,
        progressTrackColor = tokens.progressTrackColor,
      ).also { synchronized(cache) { cache.put(key, it) } }
    }

    // Static photographic translation of the Fluid material. All scratch storage is bounded
    // by the final <=384px scene; no retained mask cache and no additional RemoteViews bitmap.
    private data class PhotoScene(val bitmap: Bitmap, val ink: Int)
    private data class Lens(val rect: RectF, val primary: Boolean, val utility: Boolean,
                            val icons: List<Int>, val iconDp: Int, val enabled: Boolean)
    private data class LensStencil(val lens: Lens, val left: Int, val top: Int, val w: Int, val h: Int,
                                   val alpha: IntArray, val distance: FloatArray, val ink: BooleanArray)

    private fun photoScene(
      context: Context, artwork: Bitmap, layout: MediaWidgetLayoutSpec, metrics: SceneTextMetrics,
      size: WidgetBitmapSize, chrome: MediaWidgetColorTokens, lightInk: Int, darkInk: Int,
      lightSurface: Int, darkSurface: Int, shadow: Int, film: Int, contrast: Float, preferredInk: Int?, snapshot: MediaPlaybackSnapshot,
    ): PhotoScene {
      val w = size.width; val h = size.height
      val sx = w.toFloat() / layout.widthDp; val sy = h.toFloat() / layout.heightDp
      val scale = min(sx, sy)
      val scene = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      try {
        val canvas = Canvas(scene)
        canvas.drawColor(chrome.backgroundColor)
        val crop = max(w.toFloat() / artwork.width, h.toFloat() / artwork.height)
        val dw = artwork.width * crop; val dh = artwork.height * crop
        canvas.drawBitmap(artwork, null, RectF((w-dw)/2, (h-dh)/2, (w+dw)/2, (h+dh)/2), Paint(Paint.FILTER_BITMAP_FLAG))
        // User recipe v4.2: progressive perimeter BLUR, with only a very slight
        // black tendency in BOTH chrome themes. No theme-imposed white vignette.
        photoPerimeter(scene, shadow, chrome.darkSurface, scale)
        val (textInk, decisionInk) = photoTextMasks(context, layout, metrics, w, h)
        val lenses = photoLensStencils(context, layout, w, h, snapshot)
        // Decision uses title/artist/permission and BOTH transport silhouettes. Status,
        // elapsed digits, play/pause and loading do not participate in polarity selection.
        for (stencil in lenses) for (i in stencil.ink.indices) if (stencil.ink[i]) {
          val x = stencil.left + i % stencil.w; val y = stencil.top + i / stencil.w
          if (x in 0 until w && y in 0 until h) decisionInk[y*w+x] = true
        }
        val pixels = IntArray(w*h).also { scene.getPixels(it, 0, w, 0, 0, w, h) }
        val decisionDistance = inkDistance(decisionInk, w, h)
        val textDistance = inkDistance(textInk, w, h)
        val blurred = blurredScene(scene, (6f * scale).roundToInt())
        val soft = IntArray(w*h)
        try { blurred.getPixels(soft, 0, w, 0, 0, w, h) } finally { blurred.recycle() }
        val veil = FluidSystemContrast.veil(contrast)
        fun intervention(ink: Int, surface: Int): Double {
          var cost = 0.0
          for (i in pixels.indices) if (decisionDistance[i] <= 1f + scale) {
            val a = contrastFilmAlpha(soft[i], surface, ink, 5.0) / 255f
            val changed = soft[i].blendWith(surface, a)
            cost += abs(ColorUtils.calculateLuminance(changed) - ColorUtils.calculateLuminance(soft[i])) * (1.0 + a*a)
          }
          return cost
        }
        val lightCost = if (preferredInk == null) intervention(lightInk, darkSurface) else 0.0
        val darkCost = if (preferredInk == null) intervention(darkInk, lightSurface) else 0.0
        val useLight = preferredInk?.let { it == lightInk } ?:
          (lightCost < darkCost - 1e-7 || (abs(lightCost-darkCost) <= 1e-7 && chrome.darkSurface))
        val ink = if (useLight) lightInk else darkInk
        val surface = if (useLight) darkSurface else lightSurface
        applyInkAtmosphere(pixels, soft, textDistance, scale, context.resources.displayMetrics.density,
          ink, surface, veil, shadow)
        photoButtonBackdrop(pixels, lenses, layout, w, h, sx, sy, scale)
        scene.setPixels(pixels, 0, w, 0, 0, w, h)
        // Frozen BEFORE any shadow, film or lens. Every lens samples this same array.
        val frozen = pixels.copyOf()
        val refracted = frozen.copyOf()
        for (s in lenses) for (y in 0 until s.h) for (x in 0 until s.w) {
          val i = y*s.w+x
          if (s.alpha[i] == 0) continue
          val gx = s.left+x; val gy = s.top+y
          if (gx !in 0 until w || gy !in 0 until h) continue
          fun d(dx: Int, dy: Int) = s.distance[(y+dy).coerceIn(0,s.h-1)*s.w+(x+dx).coerceIn(0,s.w-1)]
          val nx = d(1,0)-d(-1,0); val ny = d(0,1)-d(0,-1)
          val length = sqrt(nx*nx+ny*ny).coerceAtLeast(0.001f)
          val t = (s.distance[i] / (6f*scale).coerceAtLeast(1f)).coerceIn(0f,1f)
          val shift = 2f*scale*4f*t*(1f-t)
          refracted[gy*w+gx] = photoSample(frozen,w,h,gx-nx/length*shift,gy-ny/length*shift)
        }
        val optical = Bitmap.createBitmap(refracted,w,h,Bitmap.Config.ARGB_8888)
        val frost = try { blurredScene(optical,(1.5f*scale).roundToInt()) } finally { optical.recycle() }
        val lensPixels = IntArray(w*h)
        try { frost.getPixels(lensPixels,0,w,0,0,w,h) } finally { frost.recycle() }
        // Shadows are OUTSIDE the shape and inside the native row, never trapped inside glass.
        for (s in lenses) {
          val path = continuousWidgetPath(s.lens.rect,FluidRadius.Control.value*scale)
          val save = canvas.save()
          photoRowClip(canvas,s.lens,layout,sx,sy,w,h)
          canvas.clipOutPath(path)
          canvas.drawPath(Path(path).apply { offset(0f,1.5f*scale) }, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=ColorUtils.setAlphaComponent(shadow,54)
            maskFilter=BlurMaskFilter(3f*scale,BlurMaskFilter.Blur.NORMAL)
          })
          canvas.restoreToCount(save)
        }
        scene.getPixels(pixels,0,w,0,0,w,h)
        for (s in lenses) {
          val local = IntArray(s.w*s.h)
          val iconDistance = inkDistance(s.ink,s.w,s.h)
          val protection = FloatArray(local.size)
          val edgeAlpha = if (!s.lens.enabled) 0.035f else if (s.lens.primary) 0.08f else 0.06f
          for (y in 0 until s.h) for (x in 0 until s.w) {
            val i=y*s.w+x; val gx=s.left+x; val gy=s.top+y
            if (gx !in 0 until w || gy !in 0 until h || s.alpha[i]==0) continue
            val depth=photoSmooth(s.distance[i]/max(1f,min(s.lens.rect.width(),s.lens.rect.height())*0.45f))
            val baseAlpha=edgeAlpha+(if(s.lens.enabled)0.08f else 0.045f)*depth
            val filmAlpha=baseAlpha+(1f-baseAlpha)*veil // Engine rule, ONCE.
            var color = photoVibrancy(lensPixels[gy*w+gx]).blendWith(film,filmAlpha)
            // Specular rim = one ANTIALIASED pixel taken from the stencil's own coverage. The
            // chamfer field is already 1 on the first inside ring, so `1-distance/width` drew
            // no rim at <=1.25 px/dp (long side >=307dp) and a broken one at the corners above it.
            fun cover(dx: Int, dy: Int): Float { val xx=x+dx; val yy=y+dy
              return if(xx<0 || yy<0 || xx>=s.w || yy>=s.h) 0f else s.alpha[yy*s.w+xx]/255f }
            val edge=(4f-cover(1,0)-cover(-1,0)-cover(0,1)-cover(0,-1)).coerceIn(0f,1f)
            val nx=cover(1,0)-cover(-1,0); val ny=cover(0,1)-cover(0,-1)
            val normal=sqrt(nx*nx+ny*ny).coerceAtLeast(0.001f)
            val light=if(edge>0f)(nx*0.6f+ny*0.8f)/normal else 0f
            if (light>0f) color=color.blendWith(lightSurface,edge*light*((if(s.lens.enabled)0.46f else 0.24f)+0.20f*veil))
            else color=color.blendWith(shadow,edge*(-light)*0.20f)
            local[i]=color
            // Contrast is solved AFTER refraction, film and the specular rim.
            if (iconDistance[i] <= 1f+scale) protection[i]=contrastFilmAlpha(color,surface,ink,4.5)/255f
          }
          softPenumbra(protection,s.w,s.h,max(1f,3f*scale))
          for (y in 0 until s.h) for (x in 0 until s.w) {
            val i=y*s.w+x; val gx=s.left+x; val gy=s.top+y
            if (gx !in 0 until w || gy !in 0 until h || s.alpha[i]==0) continue
            val rowAllowed=if(s.lens.utility) gy < (layout.contentPaddingVerticalDp+layout.extraRowHeightDp)*sy
              else gy >= (layout.heightDp-layout.contentPaddingVerticalDp-layout.controlsRowHeightDp)*sy
            if (!rowAllowed) continue
            var color=local[i].blendWith(surface,protection[i])
            if(iconDistance[i]<=1f+scale && ColorUtils.calculateContrast(ink,color)<4.5)
              color=color.blendWith(surface,contrastFilmAlpha(color,surface,ink,4.5)/255f)
            pixels[gy*w+gx]=pixels[gy*w+gx].blendWith(color,s.alpha[i]/255f)
          }
        }
        scene.setPixels(pixels,0,w,0,0,w,h)
        val output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val out=Canvas(output)
        val bounds=RectF(0f,0f,w.toFloat(),h.toFloat())
        out.drawPath(continuousWidgetPath(bounds,(min(w,h)*0.14f).coerceIn(44f,84f)),Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE})
        out.drawBitmap(scene,0f,0f,Paint(Paint.ANTI_ALIAS_FLAG).apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.SRC_IN)})
        return PhotoScene(output,ink)
      } finally { scene.recycle() }
    }

    private fun photoTextMasks(context: Context, layout: MediaWidgetLayoutSpec, metrics: SceneTextMetrics,
                               w: Int, h: Int): Pair<BooleanArray,BooleanArray> {
      val mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
      val body=BooleanArray(w*h)
      try {
        val canvas=Canvas(mask); val density=context.resources.displayMetrics.density
        val sx=w.toFloat()/layout.widthDp; val sy=h.toFloat()/layout.heightDp
        val rtl=context.resources.configuration.layoutDirection==View.LAYOUT_DIRECTION_RTL
        val metaH=max(metrics.status?.heightDp?:0f,metrics.time?.heightDp?:0f)
        val progress=if(layout.showProgress) layout.progressHeightDp+16f else 0f
        val bottom=layout.heightDp-layout.contentPaddingVerticalDp-layout.controlsRowHeightDp-progress-
          (if(layout.showMeta) metaH+8f else 0f)-8f
        fun draw(run: SceneTextRun,top: Float,end: Boolean=false) {
          if(run.sourcePill) return
          val right=rtl != end
          val left=if(right) layout.widthDp-layout.contentPaddingHorizontalDp-run.widthDp else layout.contentPaddingHorizontalDp.toFloat()
          val save=canvas.save(); canvas.translate(left*sx,top*sy); canvas.scale(sx/density,sy/density)
          if(run.clockEnvelope) {
            val paint=run.inkLayout!!.paint
            val fm=paint.fontMetricsInt; val baseline=run.inkLayout.getLineBaseline(0).toFloat()
            // Stable, narrow envelope for all digits in this format class; never actual ticks.
            canvas.drawRect(0f,baseline+fm.ascent,run.widthDp*density,baseline+fm.descent,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE})
          } else run.inkLayout?.let { text ->
            val old=text.paint.color; text.paint.color=Color.WHITE
            try { text.draw(canvas) } finally { text.paint.color=old }
          }
          canvas.restoreToCount(save)
        }
        if(layout.showText) {
          var y=bottom-metrics.stack.fold(0f){height,run->height+run.heightDp+run.marginTopDp}
          for(run in metrics.stack) { y+=run.marginTopDp;draw(run,y);y+=run.heightDp }
        }
        val colors=IntArray(w*h); mask.getPixels(colors,0,w,0,0,w,h)
        for(i in colors.indices) body[i]=Color.alpha(colors[i])>0
        if(layout.showMeta) {
          val top=layout.heightDp-layout.contentPaddingVerticalDp-layout.controlsRowHeightDp-8f-metaH
          metrics.status?.let{draw(it,top+(metaH-it.heightDp)/2f)}
          metrics.time?.let{draw(it,top+(metaH-it.heightDp)/2f,true)}
        }
        mask.getPixels(colors,0,w,0,0,w,h)
        val actualInk=BooleanArray(colors.size){Color.alpha(colors[it])>0}
        mask.eraseColor(Color.TRANSPARENT)
        if(layout.showText) {
          val canonicalMeta=if(layout.showMeta) max(metrics.decisionMetaHeightDp,metrics.time?.heightDp?:0f) else 0f
          var y=bottom-(if(layout.showMeta) canonicalMeta-metaH else 0f)-metrics.stack.fold(0f){height,run->height+run.heightDp+run.marginTopDp}
          for(run in metrics.stack) { y+=run.marginTopDp;draw(run,y);y+=run.heightDp }
        }
        mask.getPixels(colors,0,w,0,0,w,h)
        for(i in colors.indices)body[i]=Color.alpha(colors[i])>0
        return actualInk to body
      } finally { mask.recycle() }
    }

    private fun photoLensStencils(context: Context, layout: MediaWidgetLayoutSpec, w: Int, h: Int, snapshot: MediaPlaybackSnapshot): List<LensStencil> {
      val sx=w.toFloat()/layout.widthDp; val sy=h.toFloat()/layout.heightDp; val scale=min(sx,sy)
      val rtl=context.resources.configuration.layoutDirection==View.LAYOUT_DIRECTION_RTL
      val lenses=mutableListOf<Lens>()
      fun add(cx: Float,cy: Float,side: Int,icon: Int,ids: List<Int>,primary: Boolean=false,utility: Boolean=false,enabled: Boolean=true) {
        val x=if(rtl) layout.widthDp-cx else cx
        lenses+=Lens(RectF((x-side/2f)*sx,(cy-side/2f)*sy,(x+side/2f)*sx,(cy+side/2f)*sy),primary,utility,ids,icon,enabled)
      }
      val cy=layout.heightDp-layout.contentPaddingVerticalDp-layout.controlsRowHeightDp/2f
      add(layout.widthDp/2f,cy,layout.playButtonDp,layout.playIconDp,listOf(R.drawable.ic_widget_play,R.drawable.ic_widget_pause),primary=true,enabled=snapshot.availability==MediaPlaybackAvailability.Active && snapshot.canPlayPause)
      if(layout.showSideControls) {
        val offset=layout.playButtonDp/2f+14f+layout.sideButtonDp/2f
        add(layout.widthDp/2f-offset,cy,layout.sideButtonDp,layout.sideIconDp,listOf(R.drawable.ic_widget_previous),enabled=snapshot.canSkipPrevious)
        add(layout.widthDp/2f+offset,cy,layout.sideButtonDp,layout.sideIconDp,listOf(R.drawable.ic_widget_next),enabled=snapshot.canSkipNext)
      }
      // XML order with gravity=end: refresh, open, settings. The LAST child sits on the end
      // edge, so each glyph silhouette must be solved under the key that really draws it.
      if(layout.showExtraActions) for((index,id) in listOf(R.drawable.ic_widget_refresh,R.drawable.ic_widget_open_app,R.drawable.ic_widget_settings).withIndex())
        add(layout.widthDp-layout.contentPaddingHorizontalDp-layout.extraButtonDp/2f-(2-index)*(layout.extraButtonDp+14f),
          layout.contentPaddingVerticalDp+layout.extraButtonDp/2f,layout.extraButtonDp,layout.extraIconDp,listOf(id),utility=true)
      return lenses.map { lens ->
        val left=kotlin.math.floor(lens.rect.left).toInt(); val top=kotlin.math.floor(lens.rect.top).toInt()
        val bw=kotlin.math.ceil(lens.rect.right).toInt()-left; val bh=kotlin.math.ceil(lens.rect.bottom).toInt()-top
        val bitmap=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888)
        try {
          val c=Canvas(bitmap); c.translate(-left.toFloat(),-top.toFloat())
          c.drawPath(continuousWidgetPath(lens.rect,FluidRadius.Control.value*scale),Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE})
          val alpha=IntArray(bw*bh);bitmap.getPixels(alpha,0,bw,0,0,bw,bh)
          for(i in alpha.indices) alpha[i]=Color.alpha(alpha[i])
          val distance=inkDistance(BooleanArray(alpha.size){alpha[it]==0},bw,bh,edgeSeeds=true)
          bitmap.eraseColor(Color.TRANSPARENT)
          for(id in lens.icons) context.getDrawable(id)?.mutate()?.let { drawable ->
            drawable.setTint(Color.WHITE)
            val dp=min(lens.iconDp.toFloat(),max(drawable.intrinsicWidth,drawable.intrinsicHeight)/context.resources.displayMetrics.density)
            drawable.setBounds((lens.rect.centerX()-dp*sx/2f).roundToInt(),(lens.rect.centerY()-dp*sy/2f).roundToInt(),
              (lens.rect.centerX()+dp*sx/2f).roundToInt(),(lens.rect.centerY()+dp*sy/2f).roundToInt())
            drawable.draw(c)
          }
          if(lens.primary) {
            val r=minOf(30,layout.playIconDp+6)*scale/2f
            c.drawCircle(lens.rect.centerX(),lens.rect.centerY(),r,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE})
          }
          val icon=IntArray(bw*bh);bitmap.getPixels(icon,0,bw,0,0,bw,bh)
          LensStencil(lens,left,top,bw,bh,alpha,distance,BooleanArray(icon.size){Color.alpha(icon[it])>0})
        } finally { bitmap.recycle() }
      }
    }

    /** Local frost and shade behind the native text: ONE smooth field, never a glyph-shaped film.
     *
     * The field is 1 inside a registration guard around the stencil and falls with a C1
     * smoothstep to 0 at 15dp. Blur and the slight shade follow the field; the contrast film
     * follows its square, so it stays close to the words while the frost carries the edge.
     * The film is what the FROSTED colour needs for 5:1: inside the guard that is exactly the
     * pixel underneath, outside it is a quantity as smooth as the blur, so the shade never
     * redraws an edge of the artwork. The previous cone was seeded only two pixels from the
     * ink and fell in `correction x 5dp`: a hard silhouette, and a cliff right beside it.
     *
     * The guard is everything the host can mix into "native ink + 1 native px" when it scales
     * this <=384px bitmap: one bitmap pixel of bilinear footprint, one of raster disagreement
     * between this stencil and the real TextView, two native pixels (the antialias tail and the
     * gate's own dilation) and half a dp of layout rounding. */
    private fun applyInkAtmosphere(pixels: IntArray,soft: IntArray,distance: FloatArray,scale: Float,
                                   density: Float,ink: Int,surface: Int,veil: Float,shadow: Int) {
      val guard=2f+scale*(0.5f+2f/density.coerceAtLeast(1f))
      val reach=max(guard+1f,15f*scale)
      for(i in pixels.indices) {
        if(distance[i]>=reach)continue // True air: the artwork is untouched from 15dp outwards.
        val field=1f-photoSmooth((distance[i]-guard)/(reach-guard))
        var color=pixels[i].blendWith(soft[i],field).blendWith(shadow,0.025f*field)
        val required=contrastFilmAlpha(soft[i].blendWith(shadow,0.025f),surface,ink,5.0)/255f
        color=color.blendWith(surface,(required+(1f-required)*veil)*field*field) // Engine veil, ONCE.
        // Check the actual integer composite wherever the gate can look.
        if(distance[i]<=guard && ColorUtils.calculateContrast(ink,color)<5.0)
          color=color.blendWith(surface,contrastFilmAlpha(color,surface,ink,5.0)/255f)
        pixels[i]=color
      }
    }

    private fun photoSmooth(value: Float): Float {
      val t=value.coerceIn(0f,1f)
      return t*t*(3f-2f*t)
    }

    private fun photoPerimeter(scene: Bitmap,shadow: Int,darkChrome: Boolean,scale: Float) {
      val w=scene.width;val h=scene.height
      val blurred=blurredScene(scene,(4f*scale).roundToInt())
      try {
        val pixels=IntArray(w*h);val soft=IntArray(w*h)
        scene.getPixels(pixels,0,w,0,0,w,h);blurred.getPixels(soft,0,w,0,0,w,h)
        for(y in 0 until h)for(x in 0 until w) {
          val nx=(x+0.5f-w*0.5f)/(w*0.5f);val ny=(y+0.5f-h*0.5f)/(h*0.5f)
          val edge=photoSmooth((sqrt(nx*nx+ny*ny)-0.76f)/0.34f)
          val i=y*w+x
          pixels[i]=pixels[i].blendWith(soft[i],edge).blendWith(shadow,edge*(if(darkChrome)0.055f else 0.050f))
        }
        scene.setPixels(pixels,0,w,0,0,w,h)
      } finally { blurred.recycle() }
    }

    /** All button fields are combined before freezing the lens source. Strength rises
     * smoothly from a 4dp exterior penumbra to the CENTRE, as the user requested. */
    private fun photoButtonBackdrop(pixels: IntArray,lenses: List<LensStencil>,layout: MediaWidgetLayoutSpec,
                                     w: Int,h: Int,sx: Float,sy: Float,scale: Float) {
      val shape=BooleanArray(w*h);val inside=FloatArray(w*h)
      for(s in lenses)for(y in 0 until s.h)for(x in 0 until s.w) {
        val i=y*s.w+x;val gx=s.left+x;val gy=s.top+y
        if(gx !in 0 until w || gy !in 0 until h || s.alpha[i]==0)continue
        val index=gy*w+gx;shape[index]=true
        inside[index]=max(inside[index],0.18f+0.82f*photoSmooth(s.distance[i]/max(1f,min(s.lens.rect.width(),s.lens.rect.height())*0.45f)))
      }
      val outside=inkDistance(shape,w,h)
      val source=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
      val blurred=try { blurredScene(source,(5f*scale).roundToInt()) } finally { source.recycle() }
      try {
        val soft=IntArray(w*h);blurred.getPixels(soft,0,w,0,0,w,h)
        for(y in 0 until h)for(x in 0 until w) {
          val transport=y>=(layout.heightDp-layout.contentPaddingVerticalDp-layout.controlsRowHeightDp)*sy
          val utility=layout.showExtraActions && y<(layout.contentPaddingVerticalDp+layout.extraRowHeightDp)*sy
          if(!transport && !utility)continue // Never contaminate native text with a command field.
          val i=y*w+x
          val strength=if(shape[i])inside[i]else 0.18f*(1f-photoSmooth(outside[i]/max(1f,4f*scale)))
          pixels[i]=pixels[i].blendWith(soft[i],strength)
        }
      } finally { blurred.recycle() }
    }

    /** Two-pass 8-neighbour chamfer field; no glyph-sized bitmap allocations. */
    private fun inkDistance(seeds: BooleanArray,w: Int,h: Int,edgeSeeds: Boolean=false): FloatArray {
      val d=FloatArray(seeds.size){i->if(seeds[i])0f else if(edgeSeeds) minOf(i%w+1,i/w+1,w-i%w,h-i/w).toFloat() else 10000f}
      for(y in 0 until h) for(x in 0 until w) {
        val i=y*w+x
        if(x>0)d[i]=min(d[i],d[i-1]+1f)
        if(y>0){d[i]=min(d[i],d[i-w]+1f);if(x>0)d[i]=min(d[i],d[i-w-1]+1.414214f);if(x+1<w)d[i]=min(d[i],d[i-w+1]+1.414214f)}
      }
      for(y in h-1 downTo 0) for(x in w-1 downTo 0) {
        val i=y*w+x
        if(x+1<w)d[i]=min(d[i],d[i+1]+1f)
        if(y+1<h){d[i]=min(d[i],d[i+w]+1f);if(x>0)d[i]=min(d[i],d[i+w-1]+1.414214f);if(x+1<w)d[i]=min(d[i],d[i+w+1]+1.414214f)}
      }
      return d
    }

    /** Max of PROPORTIONAL falloffs, seed*e^(-distance/radius): bounded O(w*h), every seed is
     * preserved exactly and never averaged, and the halo keeps one soft width whatever the
     * correction, instead of a glyph-shaped edge that hardens as the correction grows. */
    private fun softPenumbra(field: FloatArray,w: Int,h: Int,radius: Float) {
      val keep=kotlin.math.exp(-1f/radius)
      for(y in 0 until h)for(x in 0 until w){val i=y*w+x;if(x>0)field[i]=max(field[i],field[i-1]*keep);if(y>0)field[i]=max(field[i],field[i-w]*keep)}
      for(y in h-1 downTo 0)for(x in w-1 downTo 0){val i=y*w+x;if(x+1<w)field[i]=max(field[i],field[i+1]*keep);if(y+1<h)field[i]=max(field[i],field[i+w]*keep)}
    }

    private fun photoSample(pixels: IntArray,w: Int,h: Int,x: Float,y: Float): Int {
      val fx=x.coerceIn(0f,(w-1).toFloat());val fy=y.coerceIn(0f,(h-1).toFloat())
      val ix=fx.toInt();val iy=fy.toInt();val nx=min(ix+1,w-1);val ny=min(iy+1,h-1)
      return pixels[iy*w+ix].blendWith(pixels[iy*w+nx],fx-ix).blendWith(
        pixels[ny*w+ix].blendWith(pixels[ny*w+nx],fx-ix),fy-iy)
    }

    private fun photoVibrancy(color: Int): Int {
      val grey=0.2126f*Color.red(color)+0.7152f*Color.green(color)+0.0722f*Color.blue(color)
      // Fluid's 1.6 vibrancy at a restrained static intensity of 0.25.
      fun channel(value: Int)=(grey+(value-grey)*1.15f).roundToInt().coerceIn(0,255)
      return Color.rgb(channel(Color.red(color)),channel(Color.green(color)),channel(Color.blue(color)))
    }

    private fun photoRowClip(canvas: Canvas,lens: Lens,layout: MediaWidgetLayoutSpec,sx: Float,sy: Float,w: Int,h: Int) {
      if(lens.utility)canvas.clipRect(0f,0f,w.toFloat(),(layout.contentPaddingVerticalDp+layout.extraRowHeightDp)*sy)
      else canvas.clipRect(0f,(layout.heightDp-layout.contentPaddingVerticalDp-layout.controlsRowHeightDp)*sy,w.toFloat(),h.toFloat())
    }

    private fun createBackgroundBitmap(
      backgroundColor: Int,
      width: Int,
      height: Int,
      darkSurface: Boolean,
      contentColor: Int,
      shadowColor: Int,
      controlFilm: Int,
      tokens: MediaWidgetColorTokens,
      snapshot: MediaPlaybackSnapshot,
      glassControls: Boolean,
      rtl: Boolean,
      textMetrics: SceneTextMetrics,
      artwork: Bitmap?,
      layout: MediaWidgetLayoutSpec,
    ): Bitmap {
      val radius = (min(width, height) * 0.14f).coerceIn(44f, 84f)
      val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())

      val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val layerCanvas = Canvas(layer)

      layerCanvas.drawColor(backgroundColor)
      if (artwork != null && !artwork.isRecycled) {
        val scale = max(width.toFloat() / artwork.width, height.toFloat() / artwork.height)
        val dw = artwork.width * scale; val dh = artwork.height * scale
        layerCanvas.drawBitmap(artwork, null, RectF((width-dw)/2, (height-dh)/2, (width+dw)/2, (height+dh)/2), Paint(Paint.FILTER_BITMAP_FLAG))
      }
      val scaleX = width.toFloat() / layout.widthDp
      val scaleY = height.toFloat() / layout.heightDp
      val scaleDp = min(scaleX, scaleY)
      // Photographic vignette: the centre remains clear, with a soft elliptical edge falloff.
      val vignette = RadialGradient(0f, 0f, 1f,
        intArrayOf(ColorUtils.setAlphaComponent(shadowColor, 0), ColorUtils.setAlphaComponent(shadowColor, 124)),
        floatArrayOf(0.30f, 1f), Shader.TileMode.CLAMP).apply {
        setLocalMatrix(Matrix().apply {
          setScale(width * 0.76f, height * 0.82f)
          postTranslate(width * 0.5f, height * 0.40f)
        })
      }
      layerCanvas.drawRect(bounds, Paint().apply { shader = vignette })
      if (layout.showText) {
        // Each native wrap_content TextView gets its own halo; there is no full-width
        // reading strip. The 8dp bottom padding is real XML geometry, not a test inset.
        val controls = if (glassControls) layout.controlsRowHeightDp else 48
        val progress = if (layout.showProgress) (if (glassControls) layout.progressHeightDp else 4f) + 16f else 0f
        val metaHeight = max(textMetrics.status?.heightDp ?: 0f, textMetrics.time?.heightDp ?: 0f)
        val meta = if (layout.showMeta) metaHeight + 8f else 0f
        val trackBottom = layout.heightDp - layout.contentPaddingVerticalDp - controls - progress - meta - 8f
        val regions = mutableListOf<RectF>()
        fun region(run: SceneTextRun, top: Float, atEnd: Boolean = false) {
          val onRight = rtl != atEnd
          val left = if (onRight) layout.widthDp - layout.contentPaddingHorizontalDp - run.widthDp
            else layout.contentPaddingHorizontalDp.toFloat()
          // Cover integer measurement, bitmap downsampling and fallback-font rounding.
          regions += RectF((left - 2f) * scaleX, (top - 2f) * scaleY,
            (left + run.widthDp + 2f) * scaleX, (top + run.heightDp + 2f) * scaleY)
        }
        var y = trackBottom - textMetrics.stack.fold(0f) { height, run -> height + run.heightDp + run.marginTopDp }
        for (run in textMetrics.stack) {
          y += run.marginTopDp
          region(run, y)
          y += run.heightDp
        }
        if (layout.showMeta) {
          val top = layout.heightDp - layout.contentPaddingVerticalDp - controls - 8f - metaHeight
          textMetrics.status?.let { region(it, top + (metaHeight - it.heightDp) / 2f) }
          textMetrics.time?.let { region(it, top + (metaHeight - it.heightDp) / 2f, atEnd = true) }
        }
        frostReadingArea(layer, regions, backgroundColor, contentColor, shadowColor,
          scaleDp, layout.fontScale, darkSurface)
      }
      if (glassControls) {
        // Material is baked into the SAME scene bitmap; the real buttons retain native
        // glyphs, accessibility, PendingIntent ownership, ripple and their >=48dp rectangles.
        drawGlassControls(layer, layout, snapshot, tokens, controlFilm, shadowColor, scaleX, scaleY, rtl)
      }

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
      layer.recycle()
      return output
    }

    /** One blur field, combined soft halos, and a pixel-local contrast correction. Already
     * readable album colours keep their hue instead of receiving the worst pixel's film.
     * Halos are combined with max(), so adjacent title/artist shadows do not accumulate. */
    private fun frostReadingArea(
      scene: Bitmap,
      regions: List<RectF>,
      surface: Int,
      content: Int,
      shadow: Int,
      scaleDp: Float,
      fontScale: Float,
      darkSurface: Boolean,
    ) {
      if (regions.isEmpty()) return
      val blurred = blurredScene(scene, (16f * scaleDp * fontScale.coerceIn(1f, 1.35f)).roundToInt())
      try {
        val pixels = IntArray(scene.width * scene.height)
        val soft = IntArray(pixels.size)
        scene.getPixels(pixels, 0, scene.width, 0, 0, scene.width, scene.height)
        blurred.getPixels(soft, 0, scene.width, 0, 0, scene.width, scene.height)
        for (y in 0 until scene.height) for (x in 0 until scene.width) {
          val index = y * scene.width + x
          var mask = 0f
          var shadowMask = 0f
          for (region in regions) {
            mask = max(mask, featherMask(x + 0.5f, y + 0.5f, region, 24f * scaleDp))
            shadowMask = max(shadowMask, featherMask(x + 0.5f, y + 0.5f - 2f * scaleDp, region, 32f * scaleDp))
          }
          if (shadowMask == 0f && mask == 0f) continue
          val depth = shadowMask * (if (darkSurface) 0.18f else 0.08f)
          val underlay = pixels[index].blendWith(shadow, depth)
          val frosted = soft[index].blendWith(shadow, depth)
          val alpha = contrastFilmAlpha(frosted, surface, content, 5.0)
          pixels[index] = underlay.blendWith(frosted.blendWith(surface, alpha / 255f), mask)
        }
        scene.setPixels(pixels, 0, scene.width, 0, 0, scene.width, scene.height)
      } finally {
        blurred.recycle()
      }
    }

    private fun featherMask(x: Float, y: Float, core: RectF, feather: Float): Float {
      val dx = max(max(core.left - x, x - core.right), 0f)
      val dy = max(max(core.top - y, y - core.bottom), 0f)
      val t = (sqrt(dx * dx + dy * dy) / feather.coerceAtLeast(1f)).coerceIn(0f, 1f)
      return 1f - t * t * (3f - 2f * t)
    }

    private data class SceneTextRun(val widthDp: Float, val heightDp: Float, val marginTopDp: Float = 0f,
                                    val inkLayout: StaticLayout? = null, val sourcePill: Boolean = false,
                                    val clockEnvelope: Boolean = false)
    private data class SceneTextMetrics(val stack: List<SceneTextRun>, val status: SceneTextRun?, val time: SceneTextRun?,
                                        val decisionMetaHeightDp: Float = 0f)

    private fun sceneTimeLabel(snapshot: MediaPlaybackSnapshot): String? =
      if (snapshot.availability == MediaPlaybackAvailability.Active && snapshot.durationMs > 0L && snapshot.positionKnown)
        "${snapshot.positionMs.coerceIn(0L, snapshot.durationMs).clockLabel()} / ${snapshot.durationMs.clockLabel()}" else null

    private fun sceneTextPaint(context: Context, sp: Float, family: String, bold: Boolean = false,
                               tracking: Float = 0f, adjustWeight: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
      if (adjustWeight && android.os.Build.VERSION.SDK_INT >= 31) {
        val adjustment = context.resources.configuration.fontWeightAdjustment
        if (adjustment != Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED && adjustment != 0) {
          val base = requireNotNull(typeface)
          typeface = Typeface.create(base, (base.weight + adjustment).coerceIn(1, 1000), base.isItalic)
        }
      }
      textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
      letterSpacing = tracking
      textLocales = context.resources.configuration.locales
    }

    /** Mirrors wrap_content native text, typefaces, SP conversion, fallback spacing and
     * line caps. The native rectangles, including their blank pixels, remain the gate. */
    private fun measureSceneText(
      context: Context,
      snapshot: MediaPlaybackSnapshot,
      settings: AppSettings,
      layout: MediaWidgetLayoutSpec,
      stableClock: Boolean = false,
    ): SceneTextMetrics {
      val metrics = context.resources.displayMetrics
      val density = metrics.density
      val available = ((layout.widthDp - 2 * layout.contentPaddingHorizontalDp) * density).roundToInt().coerceAtLeast(1)
      fun run(text: String, sp: Float, family: String, bold: Boolean = false,
              lines: Int = 1, padding: Boolean = false, tracking: Float = 0f, margin: Float = 0f,
              maxWidth: Int = available, forcedWidth: Int? = null): SceneTextRun {
        val paint = sceneTextPaint(context, sp, family, bold, tracking, adjustWeight = stableClock)
        val width = (forcedWidth ?: kotlin.math.ceil(Layout.getDesiredWidth(text, paint).toDouble()).toInt()).coerceIn(1, maxWidth.coerceAtLeast(1))
        val measured = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
          .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(padding).setMaxLines(lines)
          .setEllipsize(TextUtils.TruncateAt.END).setEllipsizedWidth(width)
          .setBreakStrategy(if (stableClock) LineBreaker.BREAK_STRATEGY_SIMPLE else LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
          .setHyphenationFrequency(if (stableClock) Layout.HYPHENATION_FREQUENCY_NONE else Layout.HYPHENATION_FREQUENCY_NORMAL)
          .setTextDirection(if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
          .apply { if (android.os.Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }
          .build()
        return SceneTextRun(width / density, measured.height / density, margin, inkLayout = measured)
      }
      val stack = mutableListOf<SceneTextRun>()
      if (layout.showSource && settings.mediaWidgetShowSource) {
        val label = run(snapshot.sourceLabel, layout.sourceSp, "sans-serif", bold = true, maxWidth = (available - 18 * density).toInt())
        stack += label.copy(widthDp = (label.widthDp + 18f).coerceAtMost(available / density),
          heightDp = if (android.os.Build.VERSION.SDK_INT >= 31) layout.sourcePillHeightDp else 20f, sourcePill = true)
      }
      stack += run(snapshot.title.ifBlank { context.getString(R.string.widget_v2_empty) },
        layout.titleSp, "sans-serif-medium", bold = true, lines = layout.titleMaxLines, tracking = -0.02f)
      if (layout.showArtist && settings.mediaWidgetShowArtist) stack += run(snapshot.artist, layout.artistSp, "sans-serif", margin = 3f)
      if (snapshot.availability == MediaPlaybackAvailability.PermissionRequired) stack +=
        run(context.getString(R.string.widget_nothing_playing_hint), layout.permissionSp, "sans-serif", padding = true, margin = 4f)
      if (!layout.showMeta) return SceneTextMetrics(stack, null, null)
      val time = sceneTimeLabel(snapshot)?.let { label ->
        if (!stableClock) run(label, layout.metaSp, "sans-serif-medium", tracking = -0.02f) else {
          val paint = sceneTextPaint(context, layout.metaSp, "sans-serif-medium", tracking = -0.02f, adjustWeight = true)
          val template = label.map { if (it.isDigit()) '#' else it }.joinToString("")
          val width = kotlin.math.ceil(('0'..'9').maxOf { digit ->
            Layout.getDesiredWidth(template.replace('#', digit), paint).toDouble()
          }).toInt() + (4f * density).roundToInt()
          run(template.replace('#','8'), layout.metaSp, "sans-serif-medium", tracking = -0.02f,
            forcedWidth = width).copy(clockEnvelope = true)
        }
      }
      val status = context.getString(if (snapshot.availability != MediaPlaybackAvailability.Active) R.string.widget_v2_empty
        else if (snapshot.isBuffering) R.string.widget_v2_loading else if (snapshot.isPlaying) R.string.widget_v2_playing else R.string.widget_v2_paused)
      val canonicalMeta = if (!stableClock) 0f else listOf(R.string.widget_v2_empty, R.string.widget_v2_loading,
        R.string.widget_v2_playing, R.string.widget_v2_paused).maxOf {
        run(context.getString(it), layout.metaSp, "sans-serif-medium", tracking = -0.02f).heightDp
      }
      return SceneTextMetrics(stack, run(status, layout.metaSp, "sans-serif-medium", tracking = -0.02f,
        maxWidth = available - ((time?.widthDp ?: 0f) * density).roundToInt()), time, canonicalMeta)
    }

    private fun drawGlassControls(
      scene: Bitmap,
      layout: MediaWidgetLayoutSpec,
      snapshot: MediaPlaybackSnapshot,
      tokens: MediaWidgetColorTokens,
      film: Int,
      shadow: Int,
      scaleX: Float,
      scaleY: Float,
      rtl: Boolean,
    ) {
      val scaleDp = min(scaleX, scaleY)
      // Small-control frost (3.5dp), a slight optical magnification, tint, specular rim and
      // external shadow approximate the Fluid material using only the available album scene.
      val backdrop = blurredScene(scene, (3.5f * scaleDp).roundToInt())
      try {
        val canvas = Canvas(scene)
        fun control(cx: Float, cy: Float, side: Int, tint: Int, icon: Int, primary: Boolean = false,
                    utility: Boolean = false) {
          val materialSave = canvas.save()
          if (utility) canvas.clipRect(0f, 0f, scene.width.toFloat(),
            (layout.contentPaddingVerticalDp + layout.extraRowHeightDp) * scaleY)
          else canvas.clipRect(0f, (layout.heightDp - layout.contentPaddingVerticalDp - layout.controlsRowHeightDp) * scaleY,
            scene.width.toFloat(), scene.height.toFloat())
          val x = if (rtl) layout.widthDp - cx else cx
          val rect = RectF((x - side / 2f) * scaleX, (cy - side / 2f) * scaleY,
            (x + side / 2f) * scaleX, (cy + side / 2f) * scaleY)
          val corner = FluidRadius.Control.value * scaleDp
          val path = continuousWidgetPath(rect, corner)
          val drop = Path(path).apply { offset(0f, 2f * scaleDp) }
          canvas.drawPath(drop, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(shadow, 76)
            maskFilter = BlurMaskFilter(4f * scaleDp, BlurMaskFilter.Blur.NORMAL)
          })
          val floor = if (primary) 96 else if (tokens.darkSurface) 64 else 72
          val alpha = minimumFilmAlpha(backdrop, tint, icon, rect, 4.5, floor)
          val save = canvas.save()
          canvas.clipPath(path)
          canvas.save()
          canvas.scale(1.04f, 1.04f, rect.centerX(), rect.centerY())
          canvas.drawBitmap(backdrop, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
          canvas.restore()
          canvas.drawColor(ColorUtils.setAlphaComponent(tint, alpha))
          val glint = tint.lighten(0.86f)
          canvas.drawRect(rect, Paint().apply { shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(ColorUtils.setAlphaComponent(glint, 20), ColorUtils.setAlphaComponent(glint, 0)),
            floatArrayOf(0f, 0.72f), Shader.TileMode.CLAMP) })
          // Draw the rim INSIDE the same clip, on an inset path. A centred outer
          // stroke must never contaminate the text's last downsampled bitmap row.
          val rimWidth = max(1f, scaleDp * 0.8f)
          canvas.drawPath(continuousWidgetPath(rect.insetBy(rimWidth / 2f), (corner - rimWidth / 2f).coerceAtLeast(0f)), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = rimWidth
            shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
              intArrayOf(ColorUtils.setAlphaComponent(glint, 124), ColorUtils.setAlphaComponent(glint, 22),
                ColorUtils.setAlphaComponent(shadow, 68)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
          })
          canvas.restoreToCount(save)
          canvas.restoreToCount(materialSave)
        }
        val cy = layout.heightDp - layout.contentPaddingVerticalDp - layout.controlsRowHeightDp / 2f
        val cx = layout.widthDp / 2f
        val playEnabled = snapshot.availability == MediaPlaybackAvailability.Active && snapshot.canPlayPause
        control(cx, cy, layout.playButtonDp, if (playEnabled) tokens.playSurfaceColor else film,
          if (playEnabled) tokens.playIconColor else tokens.disabledControlIconColor, primary = true)
        if (layout.showSideControls) {
          val offset = layout.playButtonDp / 2f + 14f + layout.sideButtonDp / 2f
          control(cx - offset, cy, layout.sideButtonDp, film,
            if (snapshot.canSkipPrevious) tokens.controlIconColor else tokens.disabledControlIconColor)
          control(cx + offset, cy, layout.sideButtonDp, film,
            if (snapshot.canSkipNext) tokens.controlIconColor else tokens.disabledControlIconColor)
        }
        if (layout.showExtraActions) {
          val y = layout.contentPaddingVerticalDp + layout.extraButtonDp / 2f
          val end = layout.widthDp - layout.contentPaddingHorizontalDp - layout.extraButtonDp / 2f
          for (index in 0..2) control(end - index * (layout.extraButtonDp + 14f), y,
            layout.extraButtonDp, film, tokens.controlIconColor, utility = true)
        }
      } finally {
        backdrop.recycle()
      }
    }

    /** Three separable box passes, edge-clamped, on the already bounded opaque scene.
     * O(width*height), two scratch arrays, no RenderScript/GPU/live-launcher dependency. */
    private fun blurredScene(source: Bitmap, radius: Int): Bitmap {
      val w = source.width
      val h = source.height
      val r = radius.coerceIn(1, 32)
      val a = IntArray(w * h)
      val b = IntArray(a.size)
      source.getPixels(a, 0, w, 0, 0, w, h)
      fun pass(input: IntArray, output: IntArray, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val count = 2 * r + 1
        for (line in 0 until lines) {
          fun at(position: Int): Int = if (horizontal) line * w + position.coerceIn(0, w - 1)
            else position.coerceIn(0, h - 1) * w + line
          var red = 0; var green = 0; var blue = 0
          for (offset in -r..r) {
            val pixel = input[at(offset)]
            red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel)
          }
          for (position in 0 until length) {
            output[at(position)] = Color.rgb(red / count, green / count, blue / count)
            val leaving = input[at(position - r)]
            val entering = input[at(position + r + 1)]
            red += Color.red(entering) - Color.red(leaving)
            green += Color.green(entering) - Color.green(leaving)
            blue += Color.blue(entering) - Color.blue(leaving)
          }
        }
      }
      repeat(3) { pass(a, b, true); pass(b, a, false) }
      return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    /** A channel envelope bounds every blurred pixel under the protected rectangle.
     * Text targets 5:1; icon material targets 4.5:1 before the weak specular highlight. */
    private fun minimumFilmAlpha(
      cover: Bitmap,
      surface: Int,
      content: Int,
      region: RectF,
      contrast: Double,
      floor: Int,
    ): Int {
      val darkSurface = ColorUtils.calculateLuminance(surface) < ColorUtils.calculateLuminance(content)
      var red = if (darkSurface) 0 else 255
      var green = red
      var blue = red
      val row = IntArray(cover.width)
      val left = region.left.toInt().coerceIn(0, cover.width - 1)
      val right = (region.right.toInt() + 1).coerceIn(left + 1, cover.width)
      val top = region.top.toInt().coerceIn(0, cover.height - 1)
      val bottom = (region.bottom.toInt() + 1).coerceIn(top + 1, cover.height)
      fun extreme(a: Int, b: Int) = if (darkSurface) max(a, b) else min(a, b)
      for (y in top until bottom) {
        cover.getPixels(row, 0, cover.width, 0, y, cover.width, 1)
        for (x in left until right) {
          val pixel = row[x]
          red = extreme(red, Color.red(pixel))
          green = extreme(green, Color.green(pixel))
          blue = extreme(blue, Color.blue(pixel))
        }
      }
      val envelope = Color.rgb(red, green, blue)
      return max(floor, contrastFilmAlpha(envelope, surface, content, contrast))
    }

    private fun contrastFilmAlpha(background: Int, surface: Int, content: Int, contrast: Double): Int {
      if (ColorUtils.calculateContrast(content, background) >= contrast) return 0
      var low = 0
      var high = 255
      while (low < high) {
        val alpha = (low + high) / 2
        val composite = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(surface, alpha), background)
        if (ColorUtils.calculateContrast(content, composite) >= contrast) high = alpha
        else low = alpha + 1
      }
      return high
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
  if (src !== artwork) src.recycle()
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
  setViewLayoutWidthCompat(viewId, sizeDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
  setViewLayoutHeightCompat(viewId, sizeDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
}

private fun RemoteViews.setScale(viewId: Int, scale: Float) {
  setFloat(viewId, "setScaleX", scale)
  setFloat(viewId, "setScaleY", scale)
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

/** Older hosts keep the opaque XML tint and dimensions; these RemoteViews operations start at 31. */
private fun RemoteViews.setColorStateListCompat(viewId: Int, method: String, colors: ColorStateList) {
  if (android.os.Build.VERSION.SDK_INT >= 31) setColorStateList(viewId, method, colors)
  else when (method) {
    "setImageTintList" -> setInt(viewId, "setColorFilter", colors.defaultColor)
    "setBackgroundTintList" -> setInt(viewId, "setBackgroundColor", colors.defaultColor)
  }
}
private fun RemoteViews.setViewLayoutHeightCompat(viewId: Int, value: Float, unit: Int) {
  if (android.os.Build.VERSION.SDK_INT >= 31) setViewLayoutHeight(viewId, value, unit)
}
private fun RemoteViews.setViewLayoutWidthCompat(viewId: Int, value: Float, unit: Int) {
  if (android.os.Build.VERSION.SDK_INT >= 31) setViewLayoutWidth(viewId, value, unit)
}
