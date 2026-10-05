package dev.pampa.fluidify.wear.system

/** What the app remembers of its first runs: the names of the preferences, shared by whoever asks. */
object FirstRunPrefs {
    const val NAME = "first_run"

    /** Whether the notification permission has been asked for once; after that the switch in Altro explains. */
    const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
}
