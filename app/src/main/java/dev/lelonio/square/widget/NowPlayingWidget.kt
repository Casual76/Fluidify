package dev.lelonio.square.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.ColorFilter
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import dev.antigravity.fluidengine.widget.EngineWidgetShape
import dev.antigravity.fluidengine.widget.EngineWidgetSurface
import dev.antigravity.fluidengine.widget.engineWidgetTextStyle
import dev.antigravity.fluidengine.widget.resolveEngineWidgetLayout
import dev.lelonio.square.R
import dev.lelonio.square.playback.PlaybackService
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What is playing, on the home screen.
 *
 * Glance rather than RemoteViews by hand, and the engine's widget palette rather than colours of its
 * own: a widget that resolves its own appearance drifts from the app the first time the theme
 * changes, and a home screen showing last month's colours is the most visible way an app looks
 * unmaintained. The same [dev.antigravity.fluidengine.foundation.EngineSettings] the theme gets goes
 * in here, so pure black is pure black out here too.
 *
 * Deliberately **not** a second player. What it holds is what a glance is for — the cover, what is
 * playing, and the three buttons — and everything else is one tap away, because the whole surface
 * opens the app.
 *
 * The state is pushed rather than pulled. A widget cannot hold a connection to the playback service:
 * it is drawn by the launcher's process out of a bundle the system stored, possibly hours after this
 * one died. So the service writes what is playing into the widget's own state whenever it changes —
 * see [NowPlayingWidgetBridge] — and this only ever reads what is already on disk. The one thing
 * fetched at draw time is the cover, which is a file Coil already has.
 */
class NowPlayingWidget : GlanceAppWidget() {

    // Exact sizes rather than responsive breakpoints inside one layout: Glance hands the host a
    // finished set of RemoteViews per size, and asking for `SizeMode.Exact` is what lets a cell
    // three columns wide get a different composition from one that is five.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read before composing, because composing cannot suspend and decoding a cover can.
        val prefs = androidx.glance.appwidget.state.getAppWidgetState(
            context,
            androidx.glance.state.PreferencesGlanceStateDefinition,
            id,
        )
        val artworkUrl = prefs[ArtworkKey]
        val cover = artworkUrl?.let { loadCover(context, it) }

        provideContent { Content(context, cover) }
    }

    @Composable
    private fun Content(context: Context, cover: Bitmap?) {
        val state = currentState<androidx.datastore.preferences.core.Preferences>()
        val title = state[TitleKey].orEmpty()
        val artist = state[ArtistKey].orEmpty()
        val playing = state[PlayingKey] ?: false
        val hasItem = state[HasItemKey] ?: false

        val size = LocalSize.current
        val layout = resolveEngineWidgetLayout(size)
        val palette = rememberAppWidgetPalette(context)

        // Room for three buttons, which is a question about width and not the one the engine's own
        // layout answers. `EngineWidgetLayout.compact` is about a *list* — a cell too short to give
        // its rows two lines each — and this widget is one row tall by design, so it reads as
        // compact at every size it will ever be placed at. Asked that way, the skip buttons were
        // never drawn once.
        //
        // The number is what the row actually needs: a cover and its gap, two lines of title worth
        // reading, and 118 points of buttons.
        val roomForSkips = size.width >= 300.dp

        EngineWidgetSurface(
            palette = palette,
            layout = layout,
            onClick = actionRunCallback<OpenAppAction>(),
        ) {
            if (!hasItem) {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        context.getString(R.string.widget_nothing_playing),
                        style = engineWidgetTextStyle(
                            color = palette.onSurfaceVariant,
                            size = 13.sp,
                        ),
                    )
                }
                return@EngineWidgetSurface
            }

            // The cover takes the height and the words take what is left, which is the one
            // arrangement that survives a cell being resized: a widget two rows tall and one six
            // columns wide are the same layout with a different amount of room for the title.
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val art = if (roomForSkips) 64.dp else 52.dp
                Box(
                    GlanceModifier
                        .size(art)
                        .background(palette.card)
                        .cornerRadius(EngineWidgetShape.Tile),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cover != null) {
                        Image(
                            provider = ImageProvider(cover),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier.size(art).cornerRadius(EngineWidgetShape.Tile),
                        )
                    } else {
                        Image(
                            provider = ImageProvider(R.drawable.ic_app_mark),
                            contentDescription = null,
                            modifier = GlanceModifier.size(art / 2),
                            colorFilter = ColorFilter.tint(palette.onSurfaceVariant),
                        )
                    }
                }

                Spacer(GlanceModifier.width(12.dp))

                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        title,
                        maxLines = 1,
                        style = engineWidgetTextStyle(
                            color = palette.onSurface,
                            size = if (roomForSkips) 15.sp else 13.sp,
                            weight = androidx.glance.text.FontWeight.Medium,
                        ),
                    )
                    if (artist.isNotEmpty()) {
                        Text(
                            artist,
                            maxLines = 1,
                            style = engineWidgetTextStyle(
                                color = palette.onSurfaceVariant,
                                size = if (roomForSkips) 12.sp else 11.sp,
                            ),
                        )
                    }
                }

                Spacer(GlanceModifier.width(8.dp))

                // Three buttons where there is room and one where there is not. The one that
                // survives is play, because it is the only one of the three that a glance is
                // ever about: skipping is something you do while already looking.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (roomForSkips) {
                        TransportButton(
                            icon = R.drawable.ic_widget_previous,
                            description = context.getString(R.string.previous),
                            command = Command.PREVIOUS,
                            palette = palette,
                            diameter = 34.dp,
                        )
                        Spacer(GlanceModifier.width(4.dp))
                    }
                    TransportButton(
                        icon = if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                        description = context.getString(
                            if (playing) R.string.pause else R.string.play,
                        ),
                        command = Command.TOGGLE,
                        palette = palette,
                        diameter = if (roomForSkips) 42.dp else 36.dp,
                        filled = true,
                    )
                    if (roomForSkips) {
                        Spacer(GlanceModifier.width(4.dp))
                        TransportButton(
                            icon = R.drawable.ic_widget_next,
                            description = context.getString(R.string.next),
                            command = Command.NEXT,
                            palette = palette,
                            diameter = 34.dp,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TransportButton(
        icon: Int,
        description: String,
        command: Command,
        palette: dev.antigravity.fluidengine.widget.EngineWidgetPalette,
        diameter: androidx.compose.ui.unit.Dp,
        filled: Boolean = false,
    ) {
        Box(
            modifier = GlanceModifier
                .size(diameter)
                .background(if (filled) palette.accentContainer else palette.card)
                // Half the diameter, not the diameter: a radius larger than the shorter side is
                // undefined territory for the host's outline provider rather than "more round".
                .cornerRadius(diameter / 2)
                .semantics { contentDescription = description }
                .clickable(
                    actionRunCallback<TransportAction>(
                        actionParametersOf(CommandParam to command.name),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = null,
                modifier = GlanceModifier.size(diameter / 2),
                colorFilter = ColorFilter.tint(
                    if (filled) palette.onAccentContainer else palette.onSurface,
                ),
            )
        }
    }

    /**
     * The cover, small.
     *
     * Small on purpose: a widget's whole composition crosses a binder as RemoteViews, and a bitmap
     * the size of the cell in pixels is most of a megabyte on its own — past the transaction limit
     * the picture simply never arrives and the widget draws blank. A couple of hundred pixels is
     * more than a cell this size can show.
     *
     * Bounded in time as well as size. This runs while the launcher waits for its content, and a
     * cover that has to come off the network on a bad connection is a home screen with a hole in it;
     * without one, the placeholder is drawn and the next update brings the picture.
     */
    private suspend fun loadCover(context: Context, url: String): Bitmap? =
        withTimeoutOrNull(COVER_TIMEOUT_MS) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(COVER_PX)
                    .scale(Scale.FILL)
                    .allowHardware(false)
                    .build()
                // The app's own loader, not a fresh one: this one has the disk cache the rest of
                // the app has been filling, so a cover that has been on screen is already here and
                // a cold one is kept for the next update. A loader built on the spot starts empty
                // every time and pays for the same picture at every track change.
                val result = context.imageLoader.execute(request)
                (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }.getOrNull()
        }

    internal enum class Command { TOGGLE, NEXT, PREVIOUS }

    internal companion object {
        val TitleKey = stringPreferencesKey("title")
        val ArtistKey = stringPreferencesKey("artist")
        val ArtworkKey = stringPreferencesKey("artwork")
        val PlayingKey = booleanPreferencesKey("playing")
        val HasItemKey = booleanPreferencesKey("hasItem")

        val CommandParam = ActionParameters.Key<String>("command")

        private const val COVER_PX = 256
        /**
         * How long the launcher is made to wait for a cover.
         *
         * Generous, because the alternative to waiting is a widget with a hole in it, and mean,
         * because this runs while the home screen is mid-draw. A cached cover returns in
         * milliseconds and never comes near this.
         */
        private const val COVER_TIMEOUT_MS = 5_000L
    }
}

/** The receiver the launcher talks to. See `res/xml/now_playing_widget.xml`. */
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/** The whole surface: the app's front door. */
class OpenAppAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        intent.putExtra(dev.lelonio.square.playback.EXTRA_OPEN_PLAYER, true)
        context.startActivity(intent)
    }
}

/**
 * One of the three buttons.
 *
 * A controller is built here and let go again, which sounds wasteful and is the only correct thing
 * to do: the widget has no process of its own to hold one in, and a session connection is also what
 * *starts* the service when the music has been stopped long enough for it to have gone away. The
 * command is sent and the connection is dropped in the same breath.
 */
class TransportAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val command = parameters[NowPlayingWidget.CommandParam] ?: return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            runCatching { MediaController.Builder(context, token).buildAsync().await() }.getOrNull()
        } ?: return
        try {
            when (NowPlayingWidget.Command.valueOf(command)) {
                NowPlayingWidget.Command.TOGGLE ->
                    if (controller.isPlaying) controller.pause() else controller.play()
                NowPlayingWidget.Command.NEXT -> controller.seekToNextMediaItem()
                NowPlayingWidget.Command.PREVIOUS -> controller.seekToPreviousMediaItem()
            }
            // Nothing is written back from here, and that is deliberate. A controller that has
            // only just connected does not necessarily have the session's queue yet, so what it
            // would report in this instant is "nothing playing" — which would blank a widget that
            // was showing a track, in answer to a button press. The service's own listener pushes
            // the moment the command lands, which is a frame or two, and it is reading from the
            // player rather than from a connection that is a millisecond old.
        } finally {
            controller.release()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000L
    }
}

/**
 * What the playback service tells the home screen.
 *
 * The one direction this can go. A widget is drawn out of stored state by another process, so the
 * only way for it to be right is for whatever knows the truth to have written it down beforehand.
 * Called from the service's own player listener.
 */
object NowPlayingWidgetBridge {

    suspend fun push(
        context: Context,
        title: String,
        artist: String,
        artworkUrl: String?,
        playing: Boolean,
        hasItem: Boolean,
    ) {
        val widget = NowPlayingWidget()
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
        val ids = runCatching { manager.getGlanceIds(NowPlayingWidget::class.java) }
            .getOrDefault(emptyList())
        // Nothing on any home screen: there is no state worth writing and no update worth running.
        if (ids.isEmpty()) return
        ids.forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                prefs[NowPlayingWidget.TitleKey] = title
                prefs[NowPlayingWidget.ArtistKey] = artist
                if (artworkUrl != null) {
                    prefs[NowPlayingWidget.ArtworkKey] = artworkUrl
                } else {
                    prefs.remove(NowPlayingWidget.ArtworkKey)
                }
                prefs[NowPlayingWidget.PlayingKey] = playing
                prefs[NowPlayingWidget.HasItemKey] = hasItem
            }
            widget.update(context, id)
        }
    }

    /** The same, read off a player. */
    suspend fun push(context: Context, player: Player) {
        push(
            context = context,
            title = player.mediaMetadata.title?.toString().orEmpty(),
            artist = player.mediaMetadata.artist?.toString().orEmpty(),
            artworkUrl = player.mediaMetadata.artworkUri?.toString(),
            playing = player.playWhenReady,
            hasItem = player.currentMediaItem != null,
        )
    }
}
