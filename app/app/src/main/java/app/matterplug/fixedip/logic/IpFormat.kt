package app.matterplug.fixedip.logic

/** Turns what Android returns into what the screen and the router need (docs/FIXED-IP.md section 4). Pure, so it is unit-tested. */
object IpFormat {
    /** `"Home"` becomes `Home`. Android returns `<unknown ssid>` (or an empty name) when the app is not allowed to see it: that is null. */
    fun ssid(raw: String?): String? {
        val t = raw?.trim()?.removeSurrounding("\"") ?: return null
        return if (t.isEmpty() || t == "<unknown ssid>") null else t
    }

    /** `a4:12:34:56:78:9a`, lower case. Null for no bytes, a wrong length, or Android's placeholder `02:00:00:00:00:00`. */
    fun mac(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size != 6) return null
        val text = bytes.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
        return if (text == PLACEHOLDER_MAC) null else text
    }

    /** The same rule for a MAC that arrives as text, as `WifiInfo.macAddress` gives it. */
    fun mac(text: String?): String? {
        val t = text?.trim()?.lowercase() ?: return null
        return if (Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(t) && t != PLACEHOLDER_MAC) t else null
    }

    /** `192.168.1.37/24`, or just the address when the prefix is unknown. */
    fun addressWithPrefix(address: String, prefix: Int?): String = if (prefix == null) address else "$address/$prefix"

    private const val PLACEHOLDER_MAC = "02:00:00:00:00:00"
}
