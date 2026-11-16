package app.matterplug.matter

/**
 * A plug waiting to be paired on the Wi-Fi network, as Matter discovery reports it. This is how a plug shared
 * from another Matter app (such as Tapo) is found: it is already on Wi-Fi and opens its pairing window there,
 * not over Bluetooth.
 */
data class NetworkPlug(val ipAddress: String, val port: Int, val discriminator: Int)

/**
 * True when a plug announcing the 12-bit discriminator [found] is the one this code is for.
 * A manual (typed) code carries only the top 4 bits of the discriminator.
 */
fun SetupCode.matches(found: Int): Boolean =
    if (isShortDiscriminator) (found shr 8) == discriminator else found == discriminator

/** The first discovered plug that matches [code] and has an address to connect to, or null. */
fun pickNetworkPlug(code: SetupCode, found: List<NetworkPlug>): NetworkPlug? =
    found.firstOrNull { it.ipAddress.isNotEmpty() && it.port > 0 && code.matches(it.discriminator) }
