package dev.lelonio.square.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.ui.page.FluidBackSwipe
import dev.antigravity.fluidengine.ui.page.FluidPageHost
import dev.antigravity.fluidengine.ui.page.FluidPageHostState
import dev.antigravity.fluidengine.ui.page.FluidPageScope
import dev.antigravity.fluidengine.ui.page.FluidPageTransition
import dev.antigravity.fluidengine.ui.page.FluidPeerMove
import dev.antigravity.fluidengine.ui.page.rememberFluidPageHostState
import dev.lelonio.square.ui.theme.pageFloor

/** The app's ambient artwork only. Never put pages, chrome or portals in this recording. */
@Stable
internal class FluidifyPageAmbient {
    // Published only after the source's first non-empty draw. Subsequent frames update the SAME
    // layer, whose display-list references remain live; there is no frame counter or redraw loop.
    internal var sample: GraphicsLayer? by mutableStateOf(null)
}

internal val LocalFluidifyPageAmbient = staticCompositionLocalOf<FluidifyPageAmbient?> { null }

@Composable
internal fun rememberFluidifyPageAmbient(): FluidifyPageAmbient = remember { FluidifyPageAmbient() }

/**
 * Mount once on the real AppBackdrop, before any pages, independently of player visibility.
 * This is an ordinary Compose recording, not a second glass renderer. Offscreen asks the backend
 * to composite the ambient once and reuse it; artwork decode, blur, drift and fade stay at source.
 * [modifier] may keep the existing ground-glass recording around this node.
 */
@Composable
internal fun FluidifyAmbientSource(
    ambient: FluidifyPageAmbient,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    DisposableEffect(ambient, layer) {
        onDispose {
            // Unpublish before consumers can replay a forgotten source. rememberGraphicsLayer owns
            // release; consumers also guard isReleased, irrespective of forget/dispose ordering.
            if (ambient.sample === layer) ambient.sample = null
        }
    }
    Box(modifier.drawWithContent {
        if (size.width > 0f && size.height > 0f && !layer.isReleased) {
            layer.compositingStrategy = CompositingStrategy.Offscreen
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            if (ambient.sample !== layer) ambient.sample = layer
        } else {
            if (ambient.sample === layer) ambient.sample = null
            drawContent()
        }
    }) { content() }
}

/**
 * Opaque paper carrying the very same ambient as the root, then the original page content.
 * Coordinates are page-local: full-size pages match the source at rest and carry that wash while
 * moving. Smaller hosts crop its top-left region; no stretching, window-position tracking or new
 * artwork composition. The opaque floor also covers missing, not-yet-drawn and disposed sources.
 * Only drawing is added: no pointer, semantics, owner, save-state or back-dispatch interception.
 */
@Composable
internal fun FluidifyPageSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val ambient = LocalFluidifyPageAmbient.current
    val floor = pageFloor
    Box(modifier.fillMaxSize().drawWithContent {
        drawRect(floor)
        val sample = ambient?.sample
        if (sample != null && !sample.isReleased && sample.size.width > 0 && sample.size.height > 0) {
            clipRect { drawLayer(sample) }
        }
        drawContent()
    }) { content() }
}

/** The real Engine destination and its scope/owners, with app paper applied to every route. */
internal fun NavGraphBuilder.fluidifyComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    transition: FluidPageTransition = FluidPageTransition.Slide,
    backSwipe: FluidBackSwipe = FluidBackSwipe.EdgeOnly,
    content: @Composable FluidPageScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments, deepLinks, transition, backSwipe) { entry ->
        val pageScope = this
        FluidifyPageSurface { pageScope.content(entry) }
    }
}

/** Same Engine host, lifecycle, gesture and portal order; app paper for nested Settings pages. */
@Composable
internal fun <K : Any> FluidifyPageHost(
    stack: List<K>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    transition: (K) -> FluidPageTransition = { FluidPageTransition.Slide },
    backSwipe: (K) -> FluidBackSwipe = { FluidBackSwipe.EdgeOnly },
    peer: FluidPeerMove<K>? = null,
    onTransitionStart: (List<K>) -> Unit = {},
    onSettled: () -> Unit = {},
    leaving: Set<K>? = null,
    state: FluidPageHostState = rememberFluidPageHostState(),
    content: @Composable FluidPageScope.(K) -> Unit,
) {
    FluidPageHost(
        stack = stack,
        onBack = onBack,
        modifier = modifier,
        transition = transition,
        backSwipe = backSwipe,
        peer = peer,
        onTransitionStart = onTransitionStart,
        onSettled = onSettled,
        leaving = leaving,
        state = state,
        pageColor = pageFloor,
    ) { key ->
        val pageScope = this
        FluidifyPageSurface { pageScope.content(key) }
    }
}
