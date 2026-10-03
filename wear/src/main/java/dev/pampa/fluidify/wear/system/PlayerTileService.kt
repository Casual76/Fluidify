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

/**
 * The Fluidify tile: what is playing on the phone, with its buttons.
 *
 * Asked for a fresh layout whenever the phone's state changes in a way the
 * tile shows (see [SystemSurfaces]); never on a timer. Presses go out as
 * pending intents to [TileActionReceiver] where the renderer can send them,
 * which Wear OS 6 does, and otherwise come back here as a load action.
 */
class PlayerTileService : Material3TileService(allowDynamicTheme = false, defaultColorScheme = fluidifyTileColors()) {

    private val app get() = application as WearApp

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        // Older renderers cannot send a pending intent; their presses arrive as the id of what
        // was pressed, on the request that follows it.
        if (!protoLayoutScope.hasCapability(ProtoLayoutScope.RendererCapability.PENDING_INTENT_ACTION)) {
            requestParams.currentState.lastClickableId.takeIf { it.isNotEmpty() }?.let { id ->
                TileActions.perform(app, id)
            }
        }
        val model = PlayerTileModel.from(app.controls.nowPlaying.value)
        val cover = CoverImages.compressed(app.art, model.coverKey, COVER_PX)
        val layout = playerTileLayout(model, ServiceClicks(this@PlayerTileService, protoLayoutScope, model), cover)
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
        override val like: Clickable = action(if (model.liked == true) TileActions.UNLIKE else TileActions.LIKE)
        override val resume: Clickable = action(TileActions.TOGGLE)

        private fun action(name: String): Clickable =
            scope.clickable(TileActions.pendingIntent(context, name), id = name, fallbackAction = loadAction())
    }

    companion object {
        private const val ID_OPEN = "open"

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
    const val UNLIKE = "unlike"

    private const val ACTION = "dev.pampa.fluidify.wear.TILE_ACTION"
    private const val EXTRA_NAME = "name"

    fun perform(app: WearApp, name: String) {
        val controls = app.controls
        when (name) {
            PREVIOUS -> controls.previous()
            TOGGLE -> controls.togglePlay()
            NEXT -> controls.next()
            LIKE -> controls.setLiked(true)
            UNLIKE -> controls.setLiked(false)
        }
    }

    fun pendingIntent(context: Context, name: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            name.hashCode(),
            Intent(context, TileActionReceiver::class.java).setAction(ACTION).putExtra(EXTRA_NAME, name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun nameOf(intent: Intent): String? = intent.getStringExtra(EXTRA_NAME).takeIf { intent.action == ACTION }
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
        }
    }

    private companion object {
        const val ANSWER_BUDGET_MS = 5_000L
        const val SETTLE_MS = 150L
    }
}
