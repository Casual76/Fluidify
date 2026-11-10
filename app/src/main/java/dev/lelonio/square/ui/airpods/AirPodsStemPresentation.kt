// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*

/** Observation and last command are independent. New construction clears outcomes; close retains the terminal failure. */
internal data class StemPresentation(val observation: Int, val command: Int, val blockReason: Int?) {
    val editable get() = blockReason == null
}
internal fun AirPodsUiState.stemPresentation(): StemPresentation {
    val property = CommandProperty.STEM_CONFIG
    val mask = (domain.propertyObserved(property)?.value as? PropertyValue.Number)?.value
    val pending = domain.pending[property]?.generation == domain.generation
    val spec = AirPodsProperties.spec(property)
    val block = when {
        !controlsReady -> R.string.airpods_reason_session
        identity?.let { spec?.candidate(it) } != true -> R.string.airpods_reason_capability
        pending -> R.string.airpods_stem_pending
        mask == null -> R.string.airpods_stem_need_observation
        else -> null
    }
    val command = when {
        pending -> R.string.airpods_stem_pending
        domain.outcomes[property] == CommandOutcome.SEND_FAILED -> R.string.airpods_stem_failed
        domain.stage == AacpStage.CLOSED || domain.stage == AacpStage.ERROR -> R.string.airpods_stem_closed
        else -> when (domain.outcomes[property]) {
            CommandOutcome.OBSERVED_MATCH -> R.string.airpods_stem_match
            CommandOutcome.OBSERVED_OTHER -> R.string.airpods_stem_other
            CommandOutcome.NOT_CONFIRMED -> R.string.airpods_stem_timeout
            CommandOutcome.SEND_FAILED -> R.string.airpods_stem_failed
            CommandOutcome.CLOSED -> R.string.airpods_stem_closed
            null -> R.string.airpods_stem_no_command
        }
    }
    return StemPresentation(when {
        mask == null -> R.string.airpods_stem_unknown
        mask and 8 != 0 -> R.string.airpods_stem_intercepted
        else -> R.string.airpods_stem_hardware
    }, command, block)
}

/** One summary for the shared mask, immediately after the two independent local editors. */
@Composable internal fun StemStatusSummary(state: AirPodsUiState) {
    val view = state.stemPresentation()
    Text(stringResource(R.string.airpods_stem_shared), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(view.observation), style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("airpods-stem-observation"))
    Text(stringResource(view.command), style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("airpods-stem-command"))
    val reason = state.availability["gesture.LEFT"]?.reason ?: view.blockReason?.let { stringResource(it) }
    reason?.let { Text(it, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("airpods-stem-reason")) }
}
