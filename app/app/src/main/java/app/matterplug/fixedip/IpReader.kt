package app.matterplug.fixedip

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import app.matterplug.fixedip.logic.IpFormat
import app.matterplug.fixedip.logic.LinkReading
import java.net.Inet4Address
import java.net.NetworkInterface

/** Reads the phone's Wi-Fi into a [LinkReading] (docs/FIXED-IP.md section 4). Needs only ACCESS_NETWORK_STATE and ACCESS_WIFI_STATE. */
object IpReader {
    @Suppress("DEPRECATION")      // allNetworks and connectionInfo: deprecated but still the way to see a Wi-Fi that is not the default network
    fun read(context: Context): LinkReading {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return LinkReading(wifi = false)
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?: return LinkReading(wifi = false)
        val lp = cm.getLinkProperties(wifi)
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val gateway = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val dns = lp?.dnsServers?.filterIsInstance<Inet4Address>()?.mapNotNull { it.hostAddress }.orEmpty()
        return LinkReading(
            wifi = true,
            address = v4?.address?.hostAddress,
            prefix = v4?.prefixLength,
            gateway = gateway,
            dns = dns,
            ssid = ssid(context),
            mac = mac(),
        )
    }

    /** Null when Android does not allow the app to see the name (docs/FIXED-IP.md section 9). */
    @Suppress("DEPRECATION")
    private fun ssid(context: Context): String? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        IpFormat.ssid(wm?.connectionInfo?.ssid)
    } catch (e: SecurityException) { null }

    /**
     * The phone's Wi-Fi MAC. **Verify on the phone:** from Android 11 an ordinary app is often given nothing (or a placeholder) here;
     * then null, and the screen tells the user to copy it from Android's Wi-Fi settings.
     */
    private fun mac(): String? = try {
        IpFormat.mac(NetworkInterface.getByName("wlan0")?.hardwareAddress)
    } catch (e: Exception) { null }
}
