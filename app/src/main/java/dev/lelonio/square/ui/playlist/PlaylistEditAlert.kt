package dev.lelonio.square.ui.playlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.lelonio.square.R

/** Same modal feedback as the existing playlist confirmation. Dismissal never replays a write. */
@Composable
internal fun PlaylistEditAlert(feedback: PlaylistEditFeedback?, onDismiss: (Long) -> Unit) {
    feedback ?: return
    val title = when (feedback.operation) {
        PlaylistEditFeedback.Operation.CREATE -> R.string.playlist_edit_create_unconfirmed
        PlaylistEditFeedback.Operation.RENAME -> R.string.playlist_edit_rename_unconfirmed
        PlaylistEditFeedback.Operation.DELETE -> R.string.playlist_edit_delete_unconfirmed
        PlaylistEditFeedback.Operation.REMOVE -> R.string.playlist_edit_remove_unconfirmed
    }
    val message = when (feedback.reason) {
        PlaylistEditFeedback.Reason.UNCONFIRMED -> R.string.playlist_edit_check_before_retry
        PlaylistEditFeedback.Reason.UNAVAILABLE -> R.string.playlist_edit_unavailable
        PlaylistEditFeedback.Reason.SELECTION_CHANGED -> R.string.playlist_edit_selection_changed
    }
    FluidAlert(
        title = stringResource(title, feedback.name.ifBlank { stringResource(R.string.playlist) }),
        message = stringResource(message),
        onDismissRequest = { onDismiss(feedback.id) },
        actions = listOf(FluidAlertAction(stringResource(R.string.close), { onDismiss(feedback.id) })),
    )
}
