package dev.lelonio.square.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * One of the words at the foot of a dialog: "Cancel", "Delete", "Install".
 *
 * Plain text in a pill that shows where it can be pressed, not a filled button: a dialog here is a
 * small sheet of glass with a question on it, and two solid buttons would be the heaviest things
 * on it. The colour carries the meaning (dim for the way out, the error colour for the one that
 * costs something), which is why the caller chooses it.
 *
 * Shared by [ConfirmDialog], [NameDialog] and [UpdateDialog], which each had a copy.
 */
@Composable
internal fun DialogAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
