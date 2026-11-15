package dev.lelonio.square.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** The outer sheet handles its own collapse only after these inner levels are gone. */
@Composable
internal fun I13PlayerBackHandler(
    panel: PlayerPanel,
    immersive: Boolean,
    enabled: Boolean = true,
    onClosePanel: () -> Unit,
    onExitImmersive: () -> Unit,
) {
    // Keep this registration in place, below the sheet and before any modal host.
    // Enabling it must not move it above an already open AirPods overlay or menu.
    BackHandler(enabled = enabled && (panel != PlayerPanel.NONE || immersive)) {
        if (panel != PlayerPanel.NONE) onClosePanel() else onExitImmersive()
    }
}
