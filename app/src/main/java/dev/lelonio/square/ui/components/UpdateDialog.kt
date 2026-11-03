package dev.lelonio.square.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.lelonio.square.R

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
 *
 * The engine's alert since 3.0: in the modal host, so real glass over the page,
 * the buttons with the menus' drop and haptics, and an exit that is still seen
 * once the caller has stopped asking.
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
    val whatsNew = stringResource(R.string.update_whats_new)
    FluidAlert(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.update_available, version),
        message = if (size != null) {
            stringResource(R.string.update_prompt_size, size)
        } else {
            stringResource(R.string.update_prompt)
        },
        actions = listOf(
            FluidAlertAction(stringResource(R.string.update_later), onDismiss),
            FluidAlertAction(
                stringResource(R.string.update_install),
                onInstall,
                FluidAlertAction.Emphasis.Preferred,
            ),
        ),
        content = if (changelog.isBlank()) {
            null
        } else {
            {
                Text(
                    whatsNew,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    changelog.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
    )
}
