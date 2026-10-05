package dev.lelonio.square.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

/**
 * Says that a newer build exists, and offers to fetch it.
 *
 * An install that no store can reach only learns about a release if something
 * says so, and the settings row that used to be the only way to find out is a
 * place nobody visits to check. Shown once per release: dismissing it records
 * the version, so the same news is never delivered twice.
 *
 * Says what is in the release when the manifest does: a person asked to install something wants
 * to know what it changes, and the notes were already in the file the check read. They scroll in a
 * box of their own, a few lines high, so a long release never pushes the buttons off the screen.
 */
@Composable
fun UpdateDialog(
    version: String,
    /** Download size, already formatted, or null when the release did not say. */
    size: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    /** The release notes, or blank for none. */
    changelog: String = "",
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .menuSkin(RoundedCornerShape(24.dp))
                .padding(22.dp),
        ) {
            Text(
                stringResource(R.string.update_available, version),
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )
            Text(
                if (size != null) {
                    stringResource(R.string.update_prompt_size, size)
                } else {
                    stringResource(R.string.update_prompt)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (changelog.isNotBlank()) {
                Text(
                    stringResource(R.string.update_whats_new),
                    style = MaterialTheme.typography.labelLarge,
                    color = InkDim,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    changelog.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }

            Row(
                Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                DialogAction(stringResource(R.string.update_later), InkDim, onDismiss)
                DialogAction(stringResource(R.string.update_install), Ink, onInstall)
            }
        }
    }
}

