// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.LocalGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuHost
import dev.antigravity.fluidengine.ui.fluidphysics.LocalFluidMorphMenuState
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidMorphMenuState
import dev.lelonio.square.ui.theme.pageFloor

/** AirPods pages above the expanded player, with alerts above their own opaque page host. */
@Composable
internal fun AirPodsPlayerSettingsOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    ground: GlassBackdropState,
    settings: @Composable (close: () -> Unit, backdrop: GlassBackdropState) -> Unit,
) {
    if (!visible) return
    val modal = rememberFluidGlassModalHostState()
    val menu = rememberFluidMorphMenuState()
    val pageBackdrop = rememberGlassBackdrop()
    CompositionLocalProvider(
        LocalFluidGlassModalHostState provides modal,
        LocalFluidMorphMenuState provides menu,
        LocalGlassBackdrop provides ground,
    ) {
        Box(Modifier.fillMaxSize().background(pageFloor).testTag("airpods-player-settings")) {
            // Registered first: a settings subpage or its modal handles Back before this layer.
            BackHandler(onBack = onDismiss)
            Box(Modifier.fillMaxSize().glassBackdropSource(pageBackdrop)) {
                settings(onDismiss, ground)
            }
            FluidGlassModalHost(modal, pageBackdrop)
            FluidMorphMenuHost(menu, pageBackdrop)
        }
    }
}
