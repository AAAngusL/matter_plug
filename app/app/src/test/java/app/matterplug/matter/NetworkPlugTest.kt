package app.matterplug.matter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPlugTest {
    private val qrCode = SetupCode(passcode = 20202021, discriminator = 3821, isShortDiscriminator = false)
    // A manual code carries the top 4 bits of the 12-bit discriminator: 3821 = 0xEED, so 0xE = 14.
    private val manualCode = SetupCode(passcode = 20202021, discriminator = 14, isShortDiscriminator = true)

    @Test fun longDiscriminatorMustBeEqual() {
        assertTrue(qrCode.matches(3821))
        assertFalse(qrCode.matches(3820))
    }

    @Test fun shortDiscriminatorMatchesTheTopFourBits() {
        assertTrue(manualCode.matches(3821))
        assertTrue(manualCode.matches(0xE00))
        assertFalse(manualCode.matches(0xD00 + 0xED))
    }

    @Test fun picksTheDeviceWithTheCodesDiscriminator() {
        val found = listOf(
            NetworkPlug("192.168.1.50", 5540, 1234),
            NetworkPlug("192.168.1.198", 5540, 3821)
        )
        assertEquals(NetworkPlug("192.168.1.198", 5540, 3821), pickNetworkPlug(qrCode, found))
    }

    @Test fun noMatchingDeviceGivesNull() {
        assertNull(pickNetworkPlug(qrCode, listOf(NetworkPlug("192.168.1.50", 5540, 1234))))
        assertNull(pickNetworkPlug(qrCode, emptyList()))
    }

    @Test fun deviceWithoutAnAddressIsSkipped() {
        val found = listOf(NetworkPlug("", 5540, 3821), NetworkPlug("192.168.1.198", 0, 3821), NetworkPlug("fe80::1", 5540, 3821))
        assertEquals(NetworkPlug("fe80::1", 5540, 3821), pickNetworkPlug(qrCode, found))
    }
}
