package dev.pampa.fluidify.wear.ui.more

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.ui.common.WatchList

/**
 * The mobile-data question, asked once, the first time a watch with its own
 * radio plays by itself. Answering either way records that it was asked; the
 * switch in Altro changes it later.
 */
@Composable
fun CellularScreen(onAnswer: (Boolean) -> Unit) {
    WatchList(title = stringResource(R.string.cellular_question)) {
        item {
            Text(
                text = stringResource(R.string.cellular_question_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        item { FluidWearListRow(title = stringResource(R.string.use_mobile_data), onClick = { onAnswer(true) }) }
        item { FluidWearListRow(title = stringResource(R.string.not_now), onClick = { onAnswer(false) }) }
    }
}
