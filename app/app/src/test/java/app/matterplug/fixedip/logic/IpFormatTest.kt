package app.matterplug.fixedip.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpFormatTest {
    @Test fun ssidLosesItsQuotes() = assertEquals("Home", IpFormat.ssid("\"Home\""))
    @Test fun ssidKeepsInnerSpacesAndQuotesOnlyAtTheEnds() = assertEquals("My \"Wi-Fi\"", IpFormat.ssid("\"My \"Wi-Fi\"\""))
    @Test fun unknownOrEmptySsidIsNull() {
        assertNull(IpFormat.ssid("<unknown ssid>"))
        assertNull(IpFormat.ssid("\"<unknown ssid>\"".removeSurrounding("\"")))
        assertNull(IpFormat.ssid(""))
        assertNull(IpFormat.ssid("\"\""))
        assertNull(IpFormat.ssid(null))
    }

    @Test fun macFromBytesIsLowerCaseWithColons() =
        assertEquals("a4:12:34:56:78:9a", IpFormat.mac(byteArrayOf(0xa4.toByte(), 0x12, 0x34, 0x56, 0x78, 0x9a.toByte())))
    @Test fun macPlaceholderOrBadLengthIsNull() {
        assertNull(IpFormat.mac(byteArrayOf(2, 0, 0, 0, 0, 0)))
        assertNull(IpFormat.mac(byteArrayOf(1, 2, 3)))
        assertNull(IpFormat.mac(null as ByteArray?))
    }
    @Test fun macFromTextIsCheckedAndLowered() {
        assertEquals("a4:12:34:56:78:9a", IpFormat.mac("A4:12:34:56:78:9A"))
        assertNull(IpFormat.mac("02:00:00:00:00:00"))
        assertNull(IpFormat.mac("not a mac"))
        assertNull(IpFormat.mac(null as String?))
    }

    @Test fun addressWithPrefix() {
        assertEquals("192.168.1.37/24", IpFormat.addressWithPrefix("192.168.1.37", 24))
        assertEquals("192.168.1.37", IpFormat.addressWithPrefix("192.168.1.37", null))
    }
}
