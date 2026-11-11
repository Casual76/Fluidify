// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics

/** Exposes the spinner as indeterminate progress to accessibility services. */
internal fun Modifier.loadingSemantics(): Modifier = semantics {
    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
}
