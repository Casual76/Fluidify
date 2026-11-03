package dev.lelonio.square.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.softShadow

/**
 * What can be done with one track, as a modal.
 *
 * It went through a dropdown and then a row of icons first. Both were wrong for
 * the same reason: the actions here are about a *particular* track, and a menu
 * floating beside a list of fifty rows never says which one. The cover and the
 * title at the top of a sheet do, and are the only part of this that is not a
 * button.
 *
 * A pop-up of the engine's modal host since 3.0: the host draws it above the
 * bars and the player, puts its own scrim behind it and closes it on back. It
 * used to be the app's own panel over a 28 dp blur of the page, made of the
 * app's own glass; the material is now the one every floating pane shares.
 */
@Composable
fun TrackSheet(
    visible: Boolean,
    title: String,
    subtitle: String,
    artworkUrl: String?,
    onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    // The engine's pop-up (3.0): the modal host draws the scrim, the panel is
    // the one glass every floating pane in the family is made of, and the back
    // gesture closes it -- which the app's own sheet never did: a back with it
    // open used to pop the page underneath.
    dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal(
        visible = visible,
        onDismissRequest = onDismiss,
        paneTitle = title,
    ) {
        Column(Modifier.padding(vertical = 22.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    url = artworkUrl,
                    title = title,
                    modifier = Modifier.size(58.dp),
                    corner = 12.dp,
                    decodeSize = 58.dp,
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = dev.lelonio.square.ui.theme.Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = dev.lelonio.square.ui.theme.Ink.copy(alpha = 0.66f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                Modifier
                    .padding(top = 16.dp, bottom = 6.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .size(width = 0.dp, height = 1.dp)
                    .background(dev.lelonio.square.ui.theme.glassFilm(0.12f)),
            )

            actions()
        }
    }
}

@Composable
fun TrackSheetAction(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else Ink
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

