package dev.lelonio.square.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CloudSlash
import dev.lelonio.square.R
import dev.lelonio.square.playback.OfflineMode
import dev.lelonio.square.ui.glass.pressable

/**
 * A line saying the app is offline, and why.
 *
 * Present rather than modal on purpose. Offline is a state the listener browses
 * in for as long as it lasts, not an error to acknowledge, and a dialog in the
 * way of a library that still works would be the wrong shape entirely.
 *
 * Saying *why* is the whole point of it. "No connection", "the connection is
 * worse than your downloads" and "you turned this on" call for three different
 * things from the person reading, and an app that only says "offline" leaves
 * them to guess which one they are in.
 */
@Composable
fun OfflineNotice(modifier: Modifier = Modifier, onTurnOff: () -> Unit = {}) {
    val reason by OfflineMode.reason.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = reason != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val manual = reason == OfflineMode.Reason.MANUAL
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                // Only when there is something a tap can do. Offline because
                // the network is gone is not something to be talked out of.
                .then(
                    if (manual) {
                        Modifier.pressable(onTurnOff, RoundedCornerShape(12.dp), 0.99f)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                PhosphorIcons.Regular.CloudSlash,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(
                    when (reason) {
                        OfflineMode.Reason.MANUAL -> R.string.offline_banner_manual
                        OfflineMode.Reason.SLOW -> R.string.offline_banner_slow
                        else -> R.string.offline_banner_no_network
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
