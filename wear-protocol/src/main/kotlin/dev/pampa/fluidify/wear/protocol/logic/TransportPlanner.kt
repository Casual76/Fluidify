package dev.pampa.fluidify.wear.protocol.logic

/** One way a track can reach the watch. */
enum class Transport {
    /**
     * The watch fetches the file from Spotify's CDN over its own network, with
     * the key the phone already holds for it: no key request from the watch,
     * so none of the refusals Spotify hands out to a device asking too fast.
     */
    WATCH_WITH_PHONE_KEY,

    /** The watch fetches the file and its key itself, over its own network. */
    WATCH_ALONE,

    /** The phone sends the file it already has, over Bluetooth. */
    PHONE_FILE,

    /**
     * The phone downloads the file at the watch's quality into a scratch area
     * (never into its own library) and sends it over Bluetooth.
     */
    PHONE_STAGED,
}

/** Which way the listener prefers when the phone already has the very same file. */
enum class TransferPreference {
    /** The watch's own Wi-Fi when there is some, Bluetooth otherwise. The default. */
    WIFI_FIRST,

    /** Bluetooth from the phone, the watch's Wi-Fi only if that fails. */
    BLUETOOTH_FIRST,
}

/** What is known about one track when it is time to fetch it. */
data class TransportFacts(
    val phoneReachable: Boolean,
    /** The phone has a complete download of the track. */
    val phoneHasFile: Boolean,
    /** ...in the format the watch wants. A different one would have to be fetched again. */
    val sameQuality: Boolean,
    val preference: TransferPreference = TransferPreference.WIFI_FIRST,
)

/**
 * Decides how a track should reach the watch, in order of preference.
 *
 * The user's rules, as they gave them:
 *
 * - the phone has the file **and** at the same quality: the setting decides,
 *   the watch's own Wi-Fi first (the default, it is faster) or Bluetooth
 *   first, each falling back to the other;
 * - the phone does not have it, or has it at another quality: the watch
 *   fetches it over its own Wi-Fi (faster than anything the phone could relay),
 *   and only without Wi-Fi does the phone fetch it at the watch's quality and
 *   relay it over Bluetooth;
 * - the phone is out of reach: the watch's own network, or nothing for now.
 *
 * Whether the watch's network actually comes up (a Galaxy Watch switches its
 * Wi-Fi on only when asked, and takes a few seconds) is found out when an
 * attempt runs, not here: an attempt that cannot get a network falls through
 * to the next. An empty plan means "not now"; the queue tries again later.
 */
object TransportPlanner {

    fun plan(facts: TransportFacts): List<Transport> = when {
        !facts.phoneReachable -> listOf(Transport.WATCH_ALONE)
        facts.phoneHasFile && facts.sameQuality -> when (facts.preference) {
            TransferPreference.WIFI_FIRST -> listOf(Transport.WATCH_WITH_PHONE_KEY, Transport.PHONE_FILE)
            TransferPreference.BLUETOOTH_FIRST -> listOf(Transport.PHONE_FILE, Transport.WATCH_WITH_PHONE_KEY)
        }
        else -> listOf(Transport.WATCH_ALONE, Transport.PHONE_STAGED)
    }

    /** Whether [transport] needs the watch's own network (as opposed to Bluetooth to the phone). */
    fun needsWatchNetwork(transport: Transport): Boolean =
        transport == Transport.WATCH_WITH_PHONE_KEY || transport == Transport.WATCH_ALONE
}
