package app.matterplug.fixedip.logic

enum class DriftReason(val wire: String) { AddressChanged("address_changed"), GatewayChanged("gateway_changed"), NoWifi("no_wifi") }

/** The state of section 7. [NoWifi] carries when the 1-minute wait ends; [due] says it has ended. */
sealed interface IpState {
    data object NotPinned : IpState
    data object Match : IpState
    data class Drift(val reason: DriftReason) : IpState
    data object OtherNetwork : IpState
    data class NoWifi(val dueAtMs: Long, val due: Boolean) : IpState
}

/** Pure judgement of one reading against the pinned network (docs/FIXED-IP.md sections 5 and 7). No Android types. */
object IpVerdict {
    const val NO_WIFI_WAIT_MS = 60_000L
    const val BOOT_GRACE_MS = 3 * 60_000L

    /**
     * @param noWifiSinceMs when Wi-Fi was first seen missing; null when it is not missing
     * @param bootAtMs when the phone booted (wall-clock milliseconds)
     */
    fun judge(pinned: PinnedNetwork?, reading: LinkReading, noWifiSinceMs: Long?, bootAtMs: Long, nowMs: Long): IpState {
        if (pinned == null) return IpState.NotPinned
        // No Wi-Fi, or Wi-Fi without an IPv4 address (still asking for a lease), is "no usable Wi-Fi".
        if (!reading.wifi || reading.address == null) return noWifi(noWifiSinceMs ?: nowMs, bootAtMs, nowMs)
        // A known SSID that differs is another network. An unknown SSID (permission revoked) falls through to address and gateway.
        if (reading.ssid != null && reading.ssid != pinned.ssid) return IpState.OtherNetwork
        return when {
            reading.address != pinned.address -> IpState.Drift(DriftReason.AddressChanged)
            reading.gateway != pinned.gateway -> IpState.Drift(DriftReason.GatewayChanged)
            else -> IpState.Match
        }
    }

    /** The wait is 1 minute, counted from the loss or from the end of the boot grace, whichever is later (section 7). */
    private fun noWifi(sinceMs: Long, bootAtMs: Long, nowMs: Long): IpState.NoWifi {
        val dueAt = maxOf(sinceMs, bootAtMs + BOOT_GRACE_MS) + NO_WIFI_WAIT_MS
        return IpState.NoWifi(dueAt, due = nowMs >= dueAt)
    }

    /** True when both MACs are known and differ (section 4's screen warning); never an alert. */
    fun macChanged(pinned: PinnedNetwork?, reading: LinkReading): Boolean =
        pinned != null && reading.mac != null && pinned.mac.isNotEmpty() && !pinned.mac.equals(reading.mac, ignoreCase = true)
}
