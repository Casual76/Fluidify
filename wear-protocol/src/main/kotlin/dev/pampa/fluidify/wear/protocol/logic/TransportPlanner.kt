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
    /** The watch's own Wi-Fi when there is some, Bluetooth otherwise. An explicit preference. */
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
    val preference: TransferPreference = TransferPreference.BLUETOOTH_FIRST,
)

/**
 * Bluetooth is the default, including staging on the phone when its download has a different
 * quality. Existing Wi-Fi remains a fallback, or an explicit preference. Without the phone,
 * only the watch's own internet can fetch new files.
 */
object TransportPlanner {

    fun plan(facts: TransportFacts): List<Transport> = when {
        !facts.phoneReachable -> listOf(Transport.WATCH_ALONE)
        facts.phoneHasFile && facts.sameQuality -> when (facts.preference) {
            TransferPreference.WIFI_FIRST -> listOf(Transport.WATCH_WITH_PHONE_KEY, Transport.PHONE_FILE)
            TransferPreference.BLUETOOTH_FIRST -> listOf(Transport.PHONE_FILE, Transport.WATCH_WITH_PHONE_KEY)
        }
        else -> if (facts.preference == TransferPreference.BLUETOOTH_FIRST)
            listOf(Transport.PHONE_STAGED, Transport.WATCH_ALONE)
        else listOf(Transport.WATCH_ALONE, Transport.PHONE_STAGED)
    }

    /** Whether [transport] needs the watch's own network (as opposed to Bluetooth to the phone). */
    fun needsWatchNetwork(transport: Transport): Boolean =
        transport == Transport.WATCH_WITH_PHONE_KEY || transport == Transport.WATCH_ALONE
}
