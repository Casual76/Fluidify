package dev.lelonio.square.ui.library

import dev.lelonio.square.ui.MainViewModel.AddToPlaylistState

/** Invoked by the account observer through StateFlow.update, never while holding a picker monitor. */
internal fun revokeCollectionPicker(state: AddToPlaylistState, session: String?, unconfirmed: String): AddToPlaylistState = when {
    state.accountSession == null -> state
    session != state.accountSession -> AddToPlaylistState(revision = state.revision + 1)
    state.busy != null -> state.copy(revision = state.revision + 1, busy = null,
        done = null, removed = null, error = unconfirmed)
    else -> state
}

/** An uncertain/cancelled old write cannot complete a reopened panel or a newer attempt. */
internal fun finishCollectionPickerAttempt(
    state: AddToPlaylistState, revision: Long, destination: String, unconfirmed: String,
): AddToPlaylistState = if (state.revision == revision && state.busy == destination) {
    state.copy(busy = null, done = null, removed = null, error = state.error ?: unconfirmed)
} else state
