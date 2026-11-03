package dev.lelonio.square.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.Plus
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.lelonio.square.ui.theme.rememberArtworkPalette
import dev.lelonio.square.ui.theme.softShadow
import androidx.compose.ui.res.stringResource

/**
 * One song, arriving from outside the app.
 *
 * The track menu would have done the job and was the wrong thing to reach for:
 * a link from a friend is not a row somebody long-pressed, and answering it with
 * the same list of chores made the app look like it had not noticed. What a
 * shared song wants is to be looked at first. The cover is the card, at the size
 * a record deserves; its own colours are what the panel glows with; and the two
 * things anybody does with a song someone sent them — hear it, or keep it — are
 * the only buttons.
 *
 * Drawn at app level like [TrackSheet], and for the same reason: it belongs over
 * the tab bar and the now-playing bar, not inside a screen.
 */
@Composable
fun SharedTrackCard(
    visible: Boolean,
    track: CatalogTrack?,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette by rememberArtworkPalette(track?.artworkUrl)
    // The engine's pop-up (3.0): the modal host draws the scrim, the panel is
    // the glass every floating pane in the family is made of, and back closes
    // it. The app's own panel stood over a 28 dp blur of the page in its own
    // glass, and with no BackHandler a back used to pop the page underneath.
    dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal(
        visible = visible,
        onDismissRequest = onDismiss,
        paneTitle = track?.name,
    ) {
        // A box, as the panel was: the record's light sits behind the card.
        Box {
            // The record's own light, thrown on the panel behind it. Blurred
            // past any shape of its own, so what is left is colour: the card of
            // a red cover is warm and the card of a blue one is cold, without
            // either being drawn as anything.
            if (palette.isNotEmpty()) {
                Box(
                    Modifier
                        .matchParentSize()
                        .blur(60.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    palette.first().copy(alpha = 0.55f),
                                    palette.getOrElse(1) { palette.first() }.copy(alpha = 0.18f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }

            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.shared_song),
                    style = MaterialTheme.typography.labelSmall,
                    color = dev.lelonio.square.ui.theme.Ink.copy(alpha = 0.55f),
                    letterSpacing = 1.6.sp,
                )

                Artwork(
                    url = track?.artworkUrl,
                    title = track?.name.orEmpty(),
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .softShadow(RoundedCornerShape(22.dp), elevation = 24.dp, spot = 0.45f),
                    corner = 22.dp,
                )

                Text(
                    track?.name.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = dev.lelonio.square.ui.theme.Ink,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 22.dp),
                )
                Text(
                    track?.artist.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = dev.lelonio.square.ui.theme.Ink.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // The album, quietly. It is what tells two versions of the same
                // song apart, which is exactly the doubt a link raises.
                track?.album?.takeIf { it.isNotEmpty() && it != track.name }?.let { album ->
                    Text(
                        album,
                        style = MaterialTheme.typography.bodySmall,
                        color = dev.lelonio.square.ui.theme.Ink.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 26.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CardButton(
                        label = stringResource(R.string.play),
                        icon = PhosphorIcons.Fill.Play,
                        filled = true,
                        onClick = onPlay,
                        modifier = Modifier.weight(1f),
                    )
                    CardButton(
                        label = stringResource(R.string.save),
                        icon = PhosphorIcons.Regular.Plus,
                        filled = false,
                        onClick = onAddToPlaylist,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * One of the two answers.
 *
 * Filled for the one most people want and clear for the other, which is the
 * whole of the hierarchy: two buttons of equal weight would make a card that
 * asks a question rather than one that offers a song.
 */
@Composable
private fun CardButton(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .clip(shape)
            .background(
                if (filled) dev.lelonio.square.ui.theme.Ink else dev.lelonio.square.ui.theme.glassFilm(0.12f),
                shape,
            )
            .fluidPressable(onClick = onClick, pressedScale = 0.94f)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (filled) dev.lelonio.square.ui.theme.InkInverse else dev.lelonio.square.ui.theme.Ink,
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) dev.lelonio.square.ui.theme.InkInverse else dev.lelonio.square.ui.theme.Ink,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
