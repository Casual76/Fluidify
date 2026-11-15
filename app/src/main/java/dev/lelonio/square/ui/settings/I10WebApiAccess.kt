package dev.lelonio.square.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidSheetScaffold
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.ui.MainViewModel

internal data class I10WebApiRequest(val artist: CatalogPlaylist? = null)

/** Prerequisite on the actual navigation path. Success still requires an explicit open tap. */
@Composable
internal fun I10WebApiAccess(
    request: I10WebApiRequest?,
    state: MainViewModel.WebApiState,
    onClientIdChange: (String) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpenArtist: (CatalogPlaylist) -> Unit,
) {
    FluidGlassModalPortal(
        item = request, onDismissRequest = onDismiss,
        presentation = FluidGlassModalPresentation.Sheet,
        paneTitle = stringResource(R.string.wf_i10_web_api_title),
    ) { kept ->
        FluidSheetScaffold(
            title = kept.artist?.name ?: stringResource(R.string.wf_i10_web_api_title),
            message = if (kept.artist != null && !state.connected) stringResource(R.string.wf_i10_artist_requirement) else null,
            actions = {
                if (state.connected && kept.artist != null) {
                    FluidButton(text = stringResource(R.string.wf_i10_open_artist),
                        onClick = { onOpenArtist(kept.artist) })
                }
                FluidButton(text = stringResource(R.string.close), onClick = onDismiss,
                    style = FluidButtonStyle.Plain)
            },
        ) {
            if (!state.connected) WebApiSetup(state, onClientIdChange, onConnect, onCancel)
            else Text(stringResource(R.string.connected), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
