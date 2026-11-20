package app.matterplug.fixedip.logic

/** What the user pinned (docs/FIXED-IP.md section 2): one network, as it looked when the user tapped Lock. */
data class PinnedNetwork(
    val address: String,
    val prefix: Int,
    val gateway: String,
    val ssid: String,
    val mac: String,
)

/** The Fixed IP settings (section 11). One switch: it covers the watch and every alert (section 7). */
data class FixedIpSettings(
    val enabled: Boolean = false,
    val pinned: PinnedNetwork? = null,
    val smsNumber: String = "",
    val backupNumber: String = "",             // optional; empty means none
) {
    /** At least 7 digits, as for Cellular Check's own number: shorter is not a phone number. */
    val smsNumberValid: Boolean get() = isValidNumber(smsNumber)

    /** The numbers every SMS goes to: the main number, then the backup unless it is empty, invalid or the same (section 6). */
    val recipients: List<String>
        get() = buildList {
            if (smsNumberValid) add(smsNumber)
            if (backupNumber.isNotEmpty() && isValidNumber(backupNumber) && backupNumber != smsNumber) add(backupNumber)
        }

    /** The switch may be turned on only when a network is pinned and the main number is valid (the SMS permission is checked by the caller). */
    val canEnable: Boolean get() = pinned != null && smsNumberValid

    /** Both numbers: digits and one leading plus only. A backup equal to the main number becomes empty. */
    fun coerced(): FixedIpSettings {
        val main = cleanNumber(smsNumber)
        val backup = cleanNumber(backupNumber).let { if (it == main) "" else it }
        return copy(smsNumber = main, backupNumber = backup)
    }

    companion object {
        const val MIN_DIGITS = 7

        fun cleanNumber(n: String): String = n.trim().let { t -> (if (t.startsWith("+")) "+" else "") + t.filter { it.isDigit() } }

        fun isValidNumber(n: String): Boolean = n.count { it.isDigit() } >= MIN_DIGITS
    }
}

/**
 * One read of the phone's Wi-Fi (section 4). A null [ssid] means Android did not allow it (section 9); a null [address] means
 * there is no IPv4 address yet, for example while the phone is still asking for a lease.
 */
data class LinkReading(
    val wifi: Boolean,
    val address: String? = null,
    val gateway: String? = null,
    val ssid: String? = null,
    val mac: String? = null,
    val prefix: Int? = null,
    val dns: List<String> = emptyList(),
)
