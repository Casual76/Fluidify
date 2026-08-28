package dev.lelonio.square.ui.player

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * The finger that closes the player, and the same one that brings it back.
 *
 * The contract this replaced said the screen must *not* follow the finger, and
 * it was right for what it had: the player was a sheet animating itself into the
 * bar, so a drag that also displaced the content was a second movement of the
 * same thing on the same axis. There is one movement now — the window's own —
 * and the finger writes the number that shapes it, so following the finger is no
 * longer a competing displacement. It is the only one.
 *
 * Wired through [NestedScrollConnection] and not a plain detector because the
 * content scrolls, and a detector on the parent fights a scrolling child over
 * the same axis. Nested scroll gives a defined order: the list takes the drag
 * first, and only what it cannot use reaches here. So dragging down over the
 * queue scrolls the queue until it is at its top, and from there the same
 * unbroken movement closes the player.
 *
 * The one thing that is new in the ordering: while the player is **not** whole,
 * an upward drag is taken *before* the list sees it. That is what makes the
 * gesture reversible — half-way down, pulling up rebuilds the player instead of
 * scrolling a list that is on its way out. Whole, the connection is transparent
 * and lists behave exactly as they always did.
 */
@Composable
fun PlayerCollapseDrag(
    /** Pixels of this event, positive downwards. */
    onDrag: (Float) -> Unit,
    /** Pixels per second at the release, positive downwards. */
    onRelease: (Float) -> Unit,
    /** Whether the player is at its full size right now. */
    open: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentDrag by rememberUpdatedState(onDrag)
    val currentRelease by rememberUpdatedState(onRelease)
    val currentOpen by rememberUpdatedState(open)

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Coming back up while the player is part-way down. Taken before
                // the list, because a list inside a window that is closing is not
                // what the finger is talking about.
                if (!currentOpen() && available.y < 0f) {
                    currentDrag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Only what the content could not use: at the top of the scroll
                // this is the whole drag, anywhere else it is nothing.
                if (available.y > 0f) {
                    currentDrag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // A fling that began as a close belongs to the window, not to the
                // list underneath it.
                if (!currentOpen()) {
                    currentRelease(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .nestedScroll(connection)
            // `draggable` and not `detectVerticalDragGestures`: the release has to
            // carry a velocity, and the detector does not measure one. It keeps
            // the same precedence towards scrolling children.
            .draggable(
                state = rememberDraggableState { delta -> currentDrag(delta) },
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> currentRelease(velocity) },
            ),
    ) {
        content()
    }
}
