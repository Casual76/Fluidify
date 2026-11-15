package dev.lelonio.square.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidSearchField
import dev.antigravity.fluidengine.ui.fluid.FluidSearchLabels
import dev.lelonio.square.R

/** Always mounted while the results change, and never opens the keyboard on entry. */
@Composable
internal fun I09CollectionSearch(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    FluidSearchField(
        query = query, onQueryChange = onQueryChange, modifier = modifier,
        autoFocus = false, placeholder = stringResource(R.string.wf_i09_search),
        labels = FluidSearchLabels(
            search = stringResource(R.string.wf_i09_search),
            clear = stringResource(R.string.wf_i09_clear),
            close = stringResource(R.string.wf_i09_close),
        ),
    )
}
