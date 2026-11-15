package dev.lelonio.square.wear

import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.CommandEnvelope

/** Older remotes can still operate transport, like Android media buttons do. */
internal object WearCommandAccess {
    fun capture(accounts: AccountLocalDataCoordinator, envelope: CommandEnvelope): AccountLocalDataCoordinator.Access? {
        // An explicit stale session must never be rebound to a different account.
        if (envelope.accountSession != null) return accounts.captureRequest(envelope.accountSession)
        return when (val command = envelope.command) {
            Command.Play, Command.Pause, Command.TogglePlay, Command.Next, Command.Previous,
            is Command.SeekTo, is Command.SetShuffle, is Command.SetRepeat,
            is Command.SleepTimer -> accounts.capture()
            is Command.SetVolume -> if (command.deviceId == null ||
                command.deviceId == PhoneWearBridge.PHONE_DEVICE_ID) accounts.capture() else null
            // Library writes, track/context selection and transfers retain account correlation.
            else -> null
        }
    }
}
