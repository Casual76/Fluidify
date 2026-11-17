// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidToggleRow

/** At accessibility sizes the label gets the entire row width, above the Engine switch. */
@Composable internal fun AirPodsReadableToggle(title: String, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null, leadingTile: Boolean = true) {
    if (LocalDensity.current.fontScale < 1.5f) {
        FluidToggleRow(title, checked, onCheckedChange, modifier = modifier, enabled = enabled,
            leading = leading, leadingTile = leadingTile)
    } else {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val available = with(LocalDensity.current) { (maxWidth - 32.dp).toPx() }
            val style = MaterialTheme.typography.titleMedium
            val measurer = rememberTextMeasurer()
            val wordFits = title.split(' ').all { measurer.measure(it, style).size.width <= available }
            Column(Modifier.fillMaxWidth()) {
            Text(title, style = style,
                softWrap = wordFits, maxLines = if (wordFits) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clearAndSetSemantics { })
            FluidToggleRow("", checked, onCheckedChange, enabled = enabled,
                modifier = modifier.semantics { text = AnnotatedString(title) })
            }
        }
    }
}
