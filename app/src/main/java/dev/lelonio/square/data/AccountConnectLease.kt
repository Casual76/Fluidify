package dev.lelonio.square.data

/** Called before any suspension, never recaptures a login on the remote worker. */
internal fun accountConnectLease(
    accounts: AccountLocalDataCoordinator,
    access: AccountLocalDataCoordinator.Access,
    permit: SpotifyNetworkPolicy.Permit = SpotifyNetworkPolicy.capture(),
    current: () -> Boolean = { true },
): ConnectCommandLease = ConnectCommandLease(permit,
    current = { current() && accounts.isOnlineCurrent(access) },
    admission = { enter -> accounts.use(access) { accounts.requireOnline(access); enter() } },
)
