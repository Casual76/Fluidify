// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.lelonio.square.BuildConfig

/** Debug snapshot marker only. Does not inspect or change account/native/session state. */
internal fun Modifier.transientPhaseSemantics(state: MainViewModel.UiState): Modifier {
    if (!BuildConfig.DEBUG) return this
    val marker = when (state) {
        MainViewModel.UiState.Connecting -> "fluidify-phase-connecting"
        MainViewModel.UiState.Loading -> "fluidify-phase-loading"
        else -> return this
    }
    return semantics { contentDescription = marker }
}
