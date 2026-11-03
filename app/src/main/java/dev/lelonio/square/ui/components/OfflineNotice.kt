package dev.lelonio.square.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CloudSlash
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.lelonio.square.R
import dev.lelonio.square.playback.OfflineMode

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
        enter = fadeIn(FluidMotion.fadeIn()) + expandVertically(FluidMotion.intSize()),
        exit = fadeOut(FluidMotion.fadeOut()) + shrinkVertically(FluidMotion.intSize()),
        modifier = modifier,
    ) {
        val manual = reason == OfflineMode.Reason.MANUAL
        // The engine's inline message. Only touchable when there is something a tap can do:
        // offline because the network is gone is not something to be talked out of.
        FluidInlineMessage(
            message = stringResource(
                when (reason) {
                    OfflineMode.Reason.MANUAL -> R.string.offline_banner_manual
                    OfflineMode.Reason.SLOW -> R.string.offline_banner_slow
                    else -> R.string.offline_banner_no_network
                },
            ),
            tone = FluidTone.Neutral,
            onClick = if (manual) onTurnOff else null,
            leading = {
                Icon(PhosphorIcons.Regular.CloudSlash, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            glass = true,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )
    }
}
