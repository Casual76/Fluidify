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
 * way Spotify's watch app opens on its player from each of them.
 */
object PlayerIntents {

    const val ACTION_SHOW_PLAYER = "dev.pampa.fluidify.wear.SHOW_PLAYER"

    /** The same request as an extra, for launchers that can set extras but not an action. */
    const val EXTRA_SHOW_PLAYER = "show_player"

    fun wantsPlayer(intent: Intent?): Boolean =
        intent != null && (intent.action == ACTION_SHOW_PLAYER || intent.getBooleanExtra(EXTRA_SHOW_PLAYER, false))

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

    private const val REQUEST_OPEN_PLAYER = 1
}
