package dev.lelonio.square.ui.player

import dev.lelonio.square.ui.MainViewModel

/** A fallback failure says nothing new about devices already received from Connect. */
internal fun runConnectRefreshFailure(state: MainViewModel.DevicesState, message: String) =
    state.copy(loading = false, error = message)
