package dev.pampa.fluidify.wear.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/**
 * A watch list screen: a header, rows, the bezel scrolling it, the scroll
 * indicator on the right edge and the clock that hides as it scrolls — the
 * Wear pattern Spotify's library uses, in one call.
 *
 * Every item is shaped by the round screen on its way out: narrower, smaller
 * and fainter as it nears the top or bottom of the circle, so a row is never
 * cut by the bezel. Callers write plain `item {}`s; the scope adds that.
 */
@Composable
fun WatchList(
    title: String?,
    modifier: Modifier = Modifier,
    content: WatchListScope.() -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    // Opaque: a screen opened on top must cover the one beneath it while it slides in, and show it
    // only where Wear's swipe-to-dismiss peels it back — not let a list and a cover show through
    // each other for the length of the transition.
    ScreenScaffold(scrollState = state, modifier = modifier.background(MaterialTheme.colorScheme.background)) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            val scope = WatchListScope(this, spec)
            if (title != null) scope.item { ListHeader { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) } }
            scope.content()
        }
    }
}

/**
 * The column's scope with every item wrapped in the round-screen
 * transformation: the container part (width and position) outside, the
 * content part (scale and fade) inside, the way Wear's own buttons nest them.
 */
class WatchListScope internal constructor(
    private val inner: TransformingLazyColumnScope,
    private val spec: TransformationSpec,
) {
    fun item(key: Any? = null, content: @Composable TransformingLazyColumnItemScope.() -> Unit) {
        inner.item(key) { Transformed(spec) { content() } }
    }

    fun <T> items(
        items: List<T>,
        key: ((item: T) -> Any)? = null,
        itemContent: @Composable TransformingLazyColumnItemScope.(item: T) -> Unit,
    ) {
        inner.items(
            count = items.size,
            key = key?.let { k -> { index: Int -> k(items[index]) } },
        ) { index -> Transformed(spec) { itemContent(items[index]) } }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.Transformed(
    spec: TransformationSpec,
    content: @Composable TransformingLazyColumnItemScope.() -> Unit,
) {
    Box(
        Modifier
            .transformedHeight(this, spec)
            .graphicsLayer { with(spec) { applyContainerTransformation(scrollProgress) } },
    ) {
        Box(Modifier.graphicsLayer { with(spec) { applyContentTransformation(scrollProgress) } }) {
            content()
        }
    }
}

/** A line of quiet text in a list: loading, empty, or why something is missing. */
fun WatchListScope.noticeItem(text: String) {
    item {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
