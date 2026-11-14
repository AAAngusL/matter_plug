package app.matterplug.matter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plug advertises its full 12-bit discriminator. The scan filter built from the setup code must match it,
 * whether the code was scanned (QR: full value) or typed (manual code: short 4-bit value only).
 */
class BleFilterTest {
    /** The Matter test device: passcode 20202021, discriminator 3840 (0xF00), short discriminator 15. */
    private val advertised = BleFilter.serviceData(3840)

    private fun matches(code: String): Boolean {
        val setup = (parseSetupCode(code) as SetupCodeResult.Ok).code
        val data = BleFilter.serviceData(setup.discriminator, setup.isShortDiscriminator)
        val mask = BleFilter.serviceDataMask(setup.isShortDiscriminator)
        return data.indices.all { i -> (data[i].toInt() and mask[i].toInt()) == (advertised[i].toInt() and mask[i].toInt()) }
    }

    @Test fun typedManualCodeFindsThePlug() = assertTrue(matches("34970112332"))

    @Test fun scannedQrCodeFindsThePlug() = assertTrue(matches("MT:Y.K9042C00KA0648G00"))

    /** The bytes a KP125M in pairing mode actually advertised (read with bluetoothctl): discriminator 3840. */
    @Test fun readsTheDiscriminatorAPlugAdvertises() =
        assertEquals(3840, BleFilter.advertisedDiscriminator(byteArrayOf(0x00, 0x00, 0x0f, 0x91.toByte(), 0x13, 0x01, 0x01, 0x01)))

    @Test fun readsTheFullTwelveBits() = assertEquals(2309, BleFilter.advertisedDiscriminator(BleFilter.serviceData(2309)))

    @Test fun ignoresDataThatIsNotACommissioningAdvertisement() {
        assertNull(BleFilter.advertisedDiscriminator(byteArrayOf(0x00, 0x05)))                 // too short
        assertNull(BleFilter.advertisedDiscriminator(byteArrayOf(0x01, 0x05, 0x09)))           // opcode is not 0
        assertNull(BleFilter.advertisedDiscriminator(null))
    }
}
