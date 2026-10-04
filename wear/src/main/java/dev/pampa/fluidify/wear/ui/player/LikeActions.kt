package dev.pampa.fluidify.wear.ui.player

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.HeartBreak
import dev.antigravity.fluidengine.wear.theme.FluidWearDimens
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.TrackInfo

/**
 * What the heart does, wherever it is — the player, the cover page, the tile.
 *
 * Putting a song in Liked Songs is one tap and done: it is what the heart is for, and easily
 * undone. Taking it out asks first ([UnlikeDialog]): on a watch a heart is a thumb's width from the
 * queue and the output, and a song dropped from the library by a stray tap is a song the listener
 * may not notice is gone until they look for it.
 */
class LikeActions(
    private val controls: PlaybackControls,
    /** Asks the listener to confirm taking the like back: shows [UnlikeDialog]. */
    private val confirmUnlike: () -> Unit,
) {
    /** The heart pressed while the song is [liked] or not. */
    fun toggle(liked: Boolean) {
        if (liked) confirmUnlike() else controls.setLiked(true)
    }
}

/** Only songs are liked: an episode or a file on the phone has no place in Liked Songs. */
fun TrackInfo?.likeable(): Boolean = this?.uri?.startsWith("spotify:track:") == true

/** "Remove from Liked Songs?", with the song's name under it. */
@Composable
fun UnlikeDialog(
    visible: Boolean,
    title: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = onConfirm) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = onDismiss) },
        icon = { Icon(PhosphorIcons.Regular.HeartBreak, contentDescription = null, modifier = Modifier.size(FluidWearDimens.IconMedium)) },
        title = { Text(stringResource(R.string.unlike_confirm), textAlign = TextAlign.Center) },
        text = title?.takeIf { it.isNotEmpty() }?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}
