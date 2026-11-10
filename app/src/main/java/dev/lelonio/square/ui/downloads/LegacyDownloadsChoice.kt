package dev.lelonio.square.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.lelonio.square.R
import dev.lelonio.square.data.DownloadStore

/** Standalone portal for the existing app modal host; no Settings or AirPods changes. */
@Composable
fun LegacyDownloadsChoice(
    choice: DownloadStore.LegacyAssignment,
    error: String? = null,
    onConfirm: (DownloadStore.LegacyAssignment) -> Unit,
    onLater: () -> Unit,
) = LegacyDownloadsChoiceContent(choice.recipient, { onConfirm(choice) }, onLater, error)

@Composable
internal fun LegacyDownloadsChoiceContent(recipient: String, confirm: () -> Unit, later: () -> Unit, error: String? = null) {
    FluidAlert(
        title = stringResource(R.string.legacy_downloads_title),
        message = stringResource(R.string.legacy_downloads_message, recipient) + error?.let { "\n\n$it" }.orEmpty(),
        onDismissRequest = later,
        actions = listOf(
            FluidAlertAction(stringResource(R.string.legacy_downloads_later), later),
            FluidAlertAction(stringResource(R.string.legacy_downloads_assign), confirm),
        ),
    )
}

