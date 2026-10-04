package dev.pampa.fluidify.wear.update

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Started by a visible user tap. The system keeps the original confirmation in a PendingIntent. */
class WatchInstallConfirmationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent.getParcelableExtra("confirmation", Intent::class.java)?.let {
            runCatching { startActivity(it) }
        }
        finish()
    }
    companion object {
        private fun intent(context: Context) = Intent(context, WatchInstallConfirmationActivity::class.java)
            .setAction("dev.pampa.fluidify.CONFIRM_UPDATE")
        fun create(context: Context, session: Int, confirmation: Intent): PendingIntent = PendingIntent.getActivity(
            context, session, intent(context).putExtra("confirmation", confirmation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun find(context: Context, session: Int): PendingIntent? = PendingIntent.getActivity(
            context, session, intent(context), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
