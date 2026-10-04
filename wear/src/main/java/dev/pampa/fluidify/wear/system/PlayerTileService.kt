package dev.pampa.fluidify.wear.system

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ProtoLayoutScope
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.loadAction
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.tile
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import dev.pampa.fluidify.wear.MainActivity
import dev.pampa.fluidify.wear.WearApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Fluidify tile: what is playing, with its buttons.
 *
 * Asked for a fresh layout whenever the state changes in a way the tile shows (see
 * [SystemSurfaces]); never on a timer — the progress ring moves by itself. The transport presses are
 * load actions: the renderer asks this service for a new layout the instant one is pressed, which
 * is not subject to the limit on [requestUpdate], and the answer already shows the result (the next
 * song's title, the play mark turned to pause). Sent as broadcasts, as before, the redraw that
 * followed a skip could be the old song, and the one with the new song could be dropped by the
 * system's throttle: the "inconsistent" skips the tests found.
 */
class PlayerTileService : Material3TileService(allowDynamicTheme = false, defaultColorScheme = fluidifyTileColors()) {

    private val app get() = application as WearApp

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val clickId = requestParams.currentState.lastClickableId
        // Each layout names its buttons afresh (see [TileActions.id]), so a request that carries an
        // id already acted on is the same press seen again — a redraw, the renderer coming back —
        // and pressing "next" once must not skip twice.
        val clicked = TileActions.nameOf(clickId)?.takeIf { app.tileTaps.claim(clickId) }
        var model = PlayerTileModel.from(app.controls.nowPlaying.value)
        if (clicked != null) {
            TileActions.perform(app, clicked)
            model = model.afterClick(clicked)
            // The tile shows its guess now; once the command has its answer (or none came), it is
            // drawn again from what is true, so a press that failed does not stay on the tile.
            app.scope.launch {
                withTimeoutOrNull(ANSWER_BUDGET_MS) {
                    delay(SETTLE_MS)
                    app.controls.nowPlaying.first { !it.busy }
                }
                requestUpdate(app)
            }
        }
        val (cover, backdrop) = withContext(Dispatchers.IO) {
            CoverImages.compressed(app.art, model.coverKey, COVER_PX) to CoverImages.backdrop(app.art, model.coverKey)
        }
        val layout = playerTileLayout(model, ServiceClicks(this@PlayerTileService, protoLayoutScope, model), cover, backdrop = backdrop)
        return tile(timeline(timelineEntry(layout)))
    }

    private class ServiceClicks(
        private val context: Context,
        private val scope: ProtoLayoutScope,
        private val model: PlayerTileModel,
    ) : PlayerTileClicks {
        override val open: Clickable = scope.clickable(
            PlayerIntents.openPlayer(context),
            id = ID_OPEN,
            fallbackAction = ActionBuilders.launchAction(
                ComponentName(context, MainActivity::class.java),
                mapOf(PlayerIntents.EXTRA_SHOW_PLAYER to ActionBuilders.booleanExtra(true)),
            ),
        )
        override val previous: Clickable = action(TileActions.PREVIOUS)
        override val toggle: Clickable = action(TileActions.TOGGLE)
        override val next: Clickable = action(TileActions.NEXT)

        /**
         * Liking is a press here like the others. Taking a like back asks first, and a tile cannot
         * ask: the filled heart opens the app on the question. Not a song (an episode, a file on the
         * phone): the heart just opens the player.
         */
        override val like: Clickable = when {
            !model.likeable -> open
            model.liked == true -> scope.clickable(
                PlayerIntents.confirmUnlike(context),
                id = ID_CONFIRM_UNLIKE,
                fallbackAction = ActionBuilders.launchAction(
                    ComponentName(context, MainActivity::class.java),
                    mapOf(PlayerIntents.EXTRA_CONFIRM_UNLIKE to ActionBuilders.booleanExtra(true)),
                ),
            )
            else -> action(TileActions.LIKE)
        }
        override val resume: Clickable = action(TileActions.TOGGLE)

        private val layout = System.nanoTime()

        private fun action(name: String): Clickable = clickable(action = loadAction(), id = TileActions.id(name, layout))
    }

    companion object {
        private const val ID_OPEN = "open"
        private const val ID_CONFIRM_UNLIKE = "confirm-unlike"
        private const val ANSWER_BUDGET_MS = 6_000L
        private const val SETTLE_MS = 150L

        /** The last press acted on, by its layout-unique id. */

        fun requestUpdate(context: Context) {
            runCatching { TileService.getUpdater(context).requestUpdate(PlayerTileService::class.java) }
        }
    }
}

/** What a press on the tile does, by name. */
internal object TileActions {
    const val PREVIOUS = "previous"
    const val TOGGLE = "toggle"
    const val NEXT = "next"
    const val LIKE = "like"

    /** The presses a tile can come back with. Taking a like back is not one: it asks first, in the app. */
    val ALL = setOf(PREVIOUS, TOGGLE, NEXT, LIKE)

    /** A button's id in one layout: its name and the layout's mark, so every layout's presses are new. */
    fun id(name: String, layout: Long): String = "$name@$layout"

    /** The press an id names, if it is one of [ALL]. */
    fun nameOf(id: String?): String? = id?.substringBefore('@')?.takeIf { it in ALL }

    private const val ACTION = "dev.pampa.fluidify.wear.TILE_ACTION"
    private const val EXTRA_NAME = "name"

    fun perform(app: WearApp, name: String) {
        val controls = app.controls
        when (name) {
            PREVIOUS -> controls.previous()
            TOGGLE -> controls.togglePlay()
            NEXT -> controls.next()
            LIKE -> controls.setLiked(true)
        }
    }

    fun pendingIntent(context: Context, name: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            name.hashCode(),
            Intent(context, TileActionReceiver::class.java).setAction(ACTION).putExtra(EXTRA_NAME, name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun nameOf(intent: Intent): String? = intent.getStringExtra(EXTRA_NAME).takeIf { intent.action == ACTION }?.takeIf { it in ALL }
}

/**
 * Runs a press from the tile.
 *
 * The command leaves at once and the tile is asked to redraw straight away, so
 * the remote's guess shows while the phone is still answering; the receiver
 * stays alive until the answer is in, or a few seconds at most, so the process
 * is not reclaimed with a command half sent.
 */
class TileActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val name = TileActions.nameOf(intent) ?: return
        val app = context.applicationContext as WearApp
        val pending = goAsync()
        TileActions.perform(app, name)
        PlayerTileService.requestUpdate(app)
        app.scope.launch {
            withTimeoutOrNull(ANSWER_BUDGET_MS) {
                delay(SETTLE_MS)
                app.controls.nowPlaying.first { !it.busy }
            }
            pending.finish()
            PlayerTileService.requestUpdate(app)
        }
    }

    private companion object {
        const val ANSWER_BUDGET_MS = 5_000L
        const val SETTLE_MS = 150L
    }
}
