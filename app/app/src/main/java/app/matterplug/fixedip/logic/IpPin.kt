package app.matterplug.fixedip.logic

/** The result of tapping Lock this address (docs/FIXED-IP.md section 5): what to pin, or why nothing is pinned. */
sealed interface PinPlan {
    data class Ready(val network: PinnedNetwork) : PinPlan
    /** The phone is not on Wi-Fi. */
    data object NoWifi : PinPlan
    /** On Wi-Fi, but without an IPv4 address, prefix or gateway yet. */
    data object NoAddress : PinPlan
    /** On Wi-Fi, but the network name cannot be read: the permission is missing or Android's location is off (section 9). */
    data object NoSsid : PinPlan

    companion object {
        /** Pinning needs the network name, so a phone on another network can never look like a drift (section 9). */
        fun fromReading(r: LinkReading): PinPlan = when {
            !r.wifi -> NoWifi
            r.address == null || r.gateway == null || r.prefix == null -> NoAddress
            r.ssid == null -> NoSsid
            else -> Ready(PinnedNetwork(r.address, r.prefix, r.gateway, r.ssid, r.mac.orEmpty()))
        }
    }
}
