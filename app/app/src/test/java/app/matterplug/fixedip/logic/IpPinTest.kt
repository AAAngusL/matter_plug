package app.matterplug.fixedip.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class IpPinTest {
    private val good = LinkReading(wifi = true, address = "192.168.1.37", prefix = 24, gateway = "192.168.1.1", ssid = "Home", mac = "a4:12:34:56:78:9a")

    @Test fun readingWithEverythingIsPinned() =
        assertEquals(PinPlan.Ready(PinnedNetwork("192.168.1.37", 24, "192.168.1.1", "Home", "a4:12:34:56:78:9a")), PinPlan.fromReading(good))

    @Test fun unreadableMacIsPinnedAsEmpty() =
        assertEquals("", (PinPlan.fromReading(good.copy(mac = null)) as PinPlan.Ready).network.mac)

    @Test fun noWifiIsNotPinned() = assertEquals(PinPlan.NoWifi, PinPlan.fromReading(LinkReading(wifi = false)))

    @Test fun missingAddressPrefixOrGatewayIsNotPinned() {
        assertEquals(PinPlan.NoAddress, PinPlan.fromReading(good.copy(address = null)))
        assertEquals(PinPlan.NoAddress, PinPlan.fromReading(good.copy(prefix = null)))
        assertEquals(PinPlan.NoAddress, PinPlan.fromReading(good.copy(gateway = null)))
    }

    @Test fun unreadableNetworkNameIsNotPinned() = assertEquals(PinPlan.NoSsid, PinPlan.fromReading(good.copy(ssid = null)))
}
