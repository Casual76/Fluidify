package dev.lelonio.square.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
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
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import dev.antigravity.fluidengine.widget.EngineWidgetShape
import dev.antigravity.fluidengine.widget.engineWidgetTextStyle
import dev.lelonio.square.R
import dev.lelonio.square.playback.PlaybackService
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What is playing, on the home screen.
 *
 * The cover is the widget, not a thumbnail on it. Every page of this app is a blurred sleeve with
 * the words standing on it under a veil, and that arrangement is most of what the app looks like —
 * a home-screen card with a small square in one corner would be a different app wearing the same
 * icon. So the sleeve is taken twice: once soft, stretched behind everything, and once sharp, in a
 * rounded tile over it.
 *
 * The blur is done by hand, and it is the app's own trick moved out here: decode the cover tiny and
 * scale it back up with a bilinear filter. There is no `RenderEffect` in a widget — what the
 * launcher gets is `RemoteViews`, a list of views and a bundle of bitmaps — so the only blur
 * available is the one already baked into the pixels. See [softenedCover].
 *
 * Deliberately **not** a second player. What it holds is what a glance is for, and everything else
 * is one tap away because the whole surface opens the app.
 *
 * The state is pushed rather than pulled. A widget cannot hold a connection to the playback service:
 * it is drawn by the launcher's process out of a bundle the system stored, possibly hours after this
 * one died. So the service writes what is playing into the widget's own state whenever it changes —
 * see [NowPlayingWidgetBridge] — and this only ever reads what is already on disk.
 *
 * Three of them, in three shapes, because a home screen is a grid and people arrange it. They are
 * the same widget: the layout is chosen from the size the cell actually is, so one dragged into a
 * different shape becomes the right one for it rather than staying the one it was picked as.
 */
sealed class NowPlayingWidget : GlanceAppWidget() {

    // Exact sizes rather than responsive breakpoints inside one layout: Glance hands the host a
    // finished set of RemoteViews per size, and asking for `SizeMode.Exact` is what lets a cell
    // three columns wide get a different composition from one that is five.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read before composing, because composing cannot suspend and a cover has to be decoded.
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val cover = prefs[ArtworkKey]?.let { loadCover(context, it) }
        provideContent { Content(context, cover) }
    }

    @Composable
    private fun Content(context: Context, cover: Cover?) {
        val state = currentState<Preferences>()
        val title = state[TitleKey].orEmpty()
        val artist = state[ArtistKey].orEmpty()
        val playing = state[PlayingKey] ?: false
        val hasItem = state[HasItemKey] ?: false

        val palette = rememberAppWidgetPalette(context)
        val shape = shapeFor(LocalSize.current)

        Box(
            GlanceModifier
                .fillMaxSize()
                .background(palette.engine.background)
                .cornerRadius(EngineWidgetShape.Container)
                .clickable(actionRunCallback<OpenAppAction>()),
        ) {
            // The sleeve, soft, behind everything — and only when there is one. With no cover the
            // widget is the app's own page colour, which is what the app does too.
            if (cover?.soft != null) {
                Image(
                    provider = ImageProvider(cover.soft),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(EngineWidgetShape.Container),
                )
                Box(
                    GlanceModifier
                        .fillMaxSize()
                        .background(palette.coverVeil)
                        .cornerRadius(EngineWidgetShape.Container),
                ) {}
            }

            when {
                !hasItem -> Empty(context, palette, shape)
                shape == Shape.Row -> RowLayout(context, palette, cover, title, artist, playing)
                else -> ColumnLayout(context, palette, cover, title, artist, playing, shape)
            }
        }
    }

    // ------------------------------------------------------------------ layouts

    @Composable
    private fun Empty(context: Context, palette: AppWidgetPalette, shape: Shape) {
        Box(
            GlanceModifier.fillMaxSize().padding(shape.padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    provider = ImageProvider(R.drawable.ic_app_mark),
                    contentDescription = null,
                    modifier = GlanceModifier.size(if (shape == Shape.Row) 22.dp else 28.dp),
                    colorFilter = ColorFilter.tint(palette.engine.onSurfaceVariant),
                )
                Spacer(GlanceModifier.height(8.dp))
                Text(
                    context.getString(R.string.widget_nothing_playing),
                    maxLines = 2,
                    style = engineWidgetTextStyle(
                        color = palette.engine.onSurfaceVariant,
                        size = 12.sp,
                    ).copy(textAlign = TextAlign.Center),
                )
            }
        }
    }

    /**
     * Short and wide: the sleeve at the start, the words in the middle, the transport at the end.
     *
     * The one arrangement that survives a cell being stretched — what changes with the width is how
     * much of the title is read, and nothing moves.
     */
    @Composable
    private fun RowLayout(
        context: Context,
        palette: AppWidgetPalette,
        cover: Cover?,
        title: String,
        artist: String,
        playing: Boolean,
    ) {
        // Room for three, which is a question about width. The number is what the row needs: a
        // sleeve and its gap, a title worth reading, and a hundred and twenty points of buttons.
        val three = LocalSize.current.width >= 300.dp
        val art = if (three) 56.dp else 46.dp

        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Sleeve(cover, palette, art)
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Title(title, palette, if (three) 15.sp else 13.sp)
                if (artist.isNotEmpty()) {
                    Spacer(GlanceModifier.height(2.dp))
                    Subtitle(artist, palette, if (three) 12.sp else 11.sp)
                }
            }
            Spacer(GlanceModifier.width(10.dp))
            Transport(context, palette, playing, skips = three, scale = if (three) 1f else 0.86f)
        }
    }

    /**
     * Tall: the sleeve on top, the words under it, the transport at the foot.
     *
     * The sleeve takes what the cell can spare rather than a fixed size, because between a
     * two-by-two and a two-by-three that is the whole difference — the words and the buttons cost
     * the same in both, and everything left over is picture.
     */
    @Composable
    private fun ColumnLayout(
        context: Context,
        palette: AppWidgetPalette,
        cover: Cover?,
        title: String,
        artist: String,
        playing: Boolean,
        shape: Shape,
    ) {
        val tight = shape == Shape.Square
        val scale = if (tight) 0.78f else 1f
        val gap = if (tight) 6.dp else 12.dp
        val showArtist = artist.isNotEmpty() && !tight

        // The sleeve is what is left over, and it has to be worked out rather than chosen.
        //
        // Glance has no measurement pass to fall back on: the content becomes RemoteViews and what
        // does not fit is squashed by the host with no callback to react to. Picked as a constant,
        // a sixty-point sleeve plus the words plus the buttons came to more than a two-by-two cell
        // has — and what the host squashed was the buttons, into letterboxes a third of their
        // height. So everything else is given its budget first and the picture takes the rest.
        val budget = LocalSize.current.height -
            shape.padding * 2 -
            transportHeight(scale) -
            TitleLine -
            (if (showArtist) ArtistLine else 0.dp) -
            gap * 2
        val sleeve = budget.coerceIn(SleeveMin, SleeveMax)

        Column(
            modifier = GlanceModifier.fillMaxSize().padding(shape.padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Sleeve(cover, palette, sleeve)
            Spacer(GlanceModifier.height(gap))
            Title(title, palette, shape.titleSize, centred = true)
            // The artist is the first thing to go. On a two-by-two the choice is between a second
            // line and a sleeve worth looking at, and the sleeve says more about what is playing
            // than the name under it does.
            if (showArtist) {
                Subtitle(artist, palette, 12.sp, centred = true)
            }
            Spacer(GlanceModifier.height(gap))
            Transport(context, palette, playing, skips = true, scale = scale)
        }
    }

    // --------------------------------------------------------------- the pieces

    /**
     * The sleeve, sharp, in a rounded tile.
     *
     * The tile is drawn whether or not there is a picture for it: a cover that has not arrived yet
     * leaves a frame in the right place rather than a hole the layout collapses into, and the frame
     * is the app's own film rather than a grey.
     */
    @Composable
    private fun Sleeve(cover: Cover?, palette: AppWidgetPalette, size: Dp) {
        Box(
            GlanceModifier
                .size(size)
                .background(palette.tile)
                .cornerRadius(SleeveCorner),
            contentAlignment = Alignment.Center,
        ) {
            if (cover?.sharp != null) {
                Image(
                    provider = ImageProvider(cover.sharp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.size(size).cornerRadius(SleeveCorner),
                )
            } else {
                Image(
                    provider = ImageProvider(R.drawable.ic_app_mark),
                    contentDescription = null,
                    modifier = GlanceModifier.size(size / 2.6f),
                    colorFilter = ColorFilter.tint(palette.engine.onSurfaceVariant),
                )
            }
        }
    }

    @Composable
    private fun Title(text: String, palette: AppWidgetPalette, size: TextUnit, centred: Boolean = false) {
        Text(
            text,
            maxLines = 1,
            style = engineWidgetTextStyle(
                color = palette.engine.onSurface,
                size = size,
                weight = FontWeight.Medium,
            ).copy(textAlign = if (centred) TextAlign.Center else TextAlign.Start),
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun Subtitle(text: String, palette: AppWidgetPalette, size: TextUnit, centred: Boolean = false) {
        Text(
            text,
            maxLines = 1,
            style = engineWidgetTextStyle(
                color = palette.engine.onSurfaceVariant,
                size = size,
            ).copy(textAlign = if (centred) TextAlign.Center else TextAlign.Start),
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }

    /**
     * The three buttons, on a strip of their own.
     *
     * The strip is there for the same reason the veil is: over a photograph, a glyph with nothing
     * behind it is legible on about half of the covers there are. On the app's own surfaces this is
     * what a pane of glass would be doing, and a widget has no glass — so it is a wash, at the
     * weight the side it is on asks for.
     */
    @Composable
    private fun Transport(
        context: Context,
        palette: AppWidgetPalette,
        playing: Boolean,
        skips: Boolean,
        scale: Float,
    ) {
        val small = 34.dp * scale
        val large = 44.dp * scale
        Row(
            modifier = GlanceModifier
                // Asked for explicitly, because a Row that only wraps its children is a Row the
                // host is free to squash when the column above it has asked for too much.
                .height(transportHeight(scale))
                .background(palette.controlVeil)
                .cornerRadius(large / 2 + StripPadding)
                .padding(horizontal = StripPadding, vertical = StripPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (skips) {
                TransportButton(
                    icon = R.drawable.ic_widget_previous,
                    description = context.getString(R.string.previous),
                    command = Command.PREVIOUS,
                    palette = palette,
                    diameter = small,
                )
                Spacer(GlanceModifier.width(2.dp))
            }
            TransportButton(
                icon = if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                description = context.getString(if (playing) R.string.pause else R.string.play),
                command = Command.TOGGLE,
                palette = palette,
                diameter = large,
                filled = true,
            )
            if (skips) {
                Spacer(GlanceModifier.width(2.dp))
                TransportButton(
                    icon = R.drawable.ic_widget_next,
                    description = context.getString(R.string.next),
                    command = Command.NEXT,
                    palette = palette,
                    diameter = small,
                )
            }
        }
    }

    @Composable
    private fun TransportButton(
        icon: Int,
        description: String,
        command: Command,
        palette: AppWidgetPalette,
        diameter: Dp,
        filled: Boolean = false,
    ) {
        Box(
            modifier = GlanceModifier
                .size(diameter)
                .then(
                    if (filled) {
                        GlanceModifier
                            .background(palette.engine.accentContainer)
                            // Half the diameter, not the diameter: a radius larger than the shorter
                            // side is undefined territory for the host's outline provider rather
                            // than "more round".
                            .cornerRadius(diameter / 2)
                    } else {
                        GlanceModifier
                    },
                )
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
                modifier = GlanceModifier.size(diameter * (if (filled) 0.42f else 0.5f)),
                colorFilter = ColorFilter.tint(
                    if (filled) palette.engine.onAccentContainer else palette.engine.onSurface,
                ),
            )
        }
    }

    // ----------------------------------------------------------------- the cover

    /**
     * The sleeve, twice.
     *
     * [soft] is the one behind everything and [sharp] is the one in the tile. Two bitmaps rather
     * than one scaled twice, because the soft one is deliberately tiny: what crosses to the launcher
     * is a bundle with a hard limit, and a full-resolution copy of the same picture in it twice is
     * how a widget ends up drawing nothing at all.
     */
    @Immutable
    data class Cover(val sharp: Bitmap?, val soft: Bitmap?)

    /**
     * The sleeve, decoded once however many widgets are asking.
     *
     * Three of these can be on a home screen, and a change of track updates all three at the same
     * instant — so three identical fetches start together. Coil's disk cache hands the editor for an
     * entry to one writer and refuses the others, so two of the three came back empty and drew the
     * placeholder while the third drew the cover. It looked like a flaky network and was a race.
     *
     * Behind the lock, and with the last one kept: the second and third asker get the bitmap the
     * first decoded, which also saves two decodes and two scalings of a picture that is the same
     * picture.
     */
    private suspend fun loadCover(context: Context, url: String): Cover? = coverLock.withLock {
        cached?.takeIf { it.first == url }?.second
            ?: fetchCover(context, url)?.also { cached = url to it }
    }

    private suspend fun fetchCover(context: Context, url: String): Cover? =
        withTimeoutOrNull(COVER_TIMEOUT_MS) {
            runCatching {
                // The app's own loader, not a fresh one: this one has the disk cache the rest of the
                // app has been filling, so a cover that has been on screen is already here and a
                // cold one is kept for the next update.
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(SHARP_PX)
                    .scale(Scale.FILL)
                    .allowHardware(false)
                    .build()
                val sharp = (context.imageLoader.execute(request).drawable
                    as? android.graphics.drawable.BitmapDrawable)?.bitmap
                Cover(sharp = sharp, soft = sharp?.let(::softenedCover))
            }.getOrNull()
        }

    /**
     * The app's blur, in the one form a widget can carry.
     *
     * Down to a handful of pixels and back up with a bilinear filter. That upscale *is* the blur —
     * it is exactly what the app's own backdrop does, and the note there is worth repeating: a
     * `Modifier.blur` is a `RenderEffect` over the whole window, re-run whenever the layer changes,
     * and out here there is no such thing at all. Thirty-two down and ninety-six up, because below
     * about thirty-two a bilinear upscale starts showing square blocks rather than a wash, and above
     * ninety-six the bundle grows for something that is already out of focus.
     */
    private fun softenedCover(source: Bitmap): Bitmap? = runCatching {
        val tiny = Bitmap.createScaledBitmap(source, SOFT_DOWN_PX, SOFT_DOWN_PX, true)
        Bitmap.createScaledBitmap(tiny, SOFT_UP_PX, SOFT_UP_PX, true)
    }.getOrNull()

    // ------------------------------------------------------------------- shapes

    /** Which of the three arrangements a cell of this size gets. See the class note. */
    internal enum class Shape(val padding: Dp, val titleSize: TextUnit) {
        Row(padding = 14.dp, titleSize = 15.sp),
        Square(padding = 12.dp, titleSize = 13.sp),
        Tall(padding = 14.dp, titleSize = 15.sp),
    }

    internal enum class Command { TOGGLE, NEXT, PREVIOUS }

    internal companion object {
        val TitleKey = stringPreferencesKey("title")
        val ArtistKey = stringPreferencesKey("artist")
        val ArtworkKey = stringPreferencesKey("artwork")
        val PlayingKey = booleanPreferencesKey("playing")
        val HasItemKey = booleanPreferencesKey("hasItem")

        val CommandParam = ActionParameters.Key<String>("command")

        /**
         * Chosen from the size the cell actually is, not from which of the three was picked.
         *
         * A widget dragged into a different shape has to become the right one for it. Short and wide
         * is a row whatever it was added as; past two hundred points of height there is room for the
         * sleeve to be the widget rather than a thumbnail on it.
         */
        fun shapeFor(size: DpSize): Shape = when {
            size.height < 130.dp -> Shape.Row
            size.height >= 200.dp -> Shape.Tall
            else -> Shape.Square
        }

        private val SleeveCorner = 12.dp

        /** What the strip costs, buttons and its own padding together. */
        private fun transportHeight(scale: Float): Dp = 44.dp * scale + StripPadding * 2

        private val StripPadding = 5.dp

        /**
         * What a line of writing costs.
         *
         * Written down rather than measured, because there is nothing to measure with — and these
         * are the two the budget above spends before the picture gets what is left.
         */
        private val TitleLine = 20.dp
        private val ArtistLine = 17.dp

        /**
         * How small and how large the sleeve is allowed to get.
         *
         * The floor is where a cover stops being a picture and becomes a bullet point; the ceiling
         * is where a widget three cells tall would otherwise be one enormous square with a caption.
         */
        private val SleeveMin = 44.dp
        private val SleeveMax = 132.dp

        private const val SHARP_PX = 224
        private const val SOFT_DOWN_PX = 32
        private const val SOFT_UP_PX = 96

        /**
         * How long the launcher is made to wait for a cover.
         *
         * Generous, because the alternative to waiting is a widget with a hole in it, and mean,
         * because this runs while the home screen is mid-draw. A cached cover returns in
         * milliseconds and never comes near this.
         */
        private const val COVER_TIMEOUT_MS = 5_000L

        /** See [loadCover]. Shared by all three shapes, which is the point of it. */
        private val coverLock = Mutex()
        private var cached: Pair<String, Cover>? = null

        /** All three, for whatever has to reach every one of them. See [NowPlayingWidgetBridge]. */
        val all: List<NowPlayingWidget>
            get() = listOf(NowPlayingWidgetRow, NowPlayingWidgetSquare, NowPlayingWidgetTall)
    }
}

/**
 * The three shapes the picker offers.
 *
 * Three types rather than one used three times: Glance finds a widget's instances by the class its
 * receiver hands back, so three entries in the picker answering to the same class are three entries
 * that cannot be told apart when it is time to redraw one of them.
 */
object NowPlayingWidgetRow : NowPlayingWidget()

object NowPlayingWidgetSquare : NowPlayingWidget()

object NowPlayingWidgetTall : NowPlayingWidget()

class NowPlayingWidgetRowReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidgetRow
}

class NowPlayingWidgetSquareReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidgetSquare
}

class NowPlayingWidgetTallReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidgetTall
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
            // Nothing is written back from here, and that is deliberate. A controller that has only
            // just connected does not necessarily have the session's queue yet, so what it would
            // report in this instant is "nothing playing" — which would blank a widget that was
            // showing a track, in answer to a button press. The service's own listener pushes the
            // moment the command lands, and it reads from the player rather than from a connection
            // a millisecond old.
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
        val manager = GlanceAppWidgetManager(context)
        NowPlayingWidget.all.forEach { widget ->
            val ids = runCatching { manager.getGlanceIds(widget.javaClass) }
                .getOrDefault(emptyList())
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

    /** Redraws every one of them without touching what they say. For a change of theme. */
    suspend fun redraw(context: Context) {
        NowPlayingWidget.all.forEach { widget ->
            runCatching { widget.updateAll(context) }
        }
    }
}
