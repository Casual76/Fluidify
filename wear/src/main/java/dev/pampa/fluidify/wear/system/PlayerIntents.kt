package dev.pampa.fluidify.wear.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.pampa.fluidify.wear.MainActivity

/**
 * The one way back into the app from outside it: the player, whatever page
 * the app was left on.
 *
 * The icon on the watch face, the tile and the complication all open it, the
 * way Spotify's watch app opens on its player from each of them. The tile's
 * filled heart opens it too, on the question that taking a like back asks: a
 * tile cannot show a dialog of its own.
 */
object PlayerIntents {

    const val ACTION_SHOW_PLAYER = "dev.pampa.fluidify.wear.SHOW_PLAYER"
    const val ACTION_CONFIRM_UNLIKE = "dev.pampa.fluidify.wear.CONFIRM_UNLIKE"

    /** The same request as an extra, for launchers that can set extras but not an action. */
    const val EXTRA_SHOW_PLAYER = "show_player"
    const val EXTRA_CONFIRM_UNLIKE = "confirm_unlike"

    /** What an intent asks the app to show. */
    enum class Request { PLAYER, CONFIRM_UNLIKE }

    fun requestOf(intent: Intent?): Request? = when {
        intent == null -> null
        intent.action == ACTION_CONFIRM_UNLIKE || intent.getBooleanExtra(EXTRA_CONFIRM_UNLIKE, false) -> Request.CONFIRM_UNLIKE
        intent.action == ACTION_SHOW_PLAYER || intent.getBooleanExtra(EXTRA_SHOW_PLAYER, false) -> Request.PLAYER
        else -> null
    }

    fun wantsPlayer(intent: Intent?): Boolean = requestOf(intent) != null

    fun showPlayer(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_SHOW_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openPlayer(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN_PLAYER,
            showPlayer(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The player with "remove from Liked Songs?" over it. */
    fun confirmUnlike(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CONFIRM_UNLIKE,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_CONFIRM_UNLIKE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private const val REQUEST_OPEN_PLAYER = 1
    private const val REQUEST_CONFIRM_UNLIKE = 2
}
