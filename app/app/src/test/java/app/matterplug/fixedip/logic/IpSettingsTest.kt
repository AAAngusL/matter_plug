package app.matterplug.fixedip.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpSettingsTest {
    private val pin = PinnedNetwork("192.168.1.37", 24, "192.168.1.1", "Home", "a4:12:34:56:78:9a")

    @Test fun shortNumberIsInvalid() {
        assertFalse(FixedIpSettings(smsNumber = "123456").smsNumberValid)
        assertTrue(FixedIpSettings(smsNumber = "1234567").smsNumberValid)
        assertFalse(FixedIpSettings(smsNumber = "").smsNumberValid)
    }

    @Test fun coercionKeepsDigitsAndOneLeadingPlus() {
        val c = FixedIpSettings(smsNumber = " +65 9123-4567 ", backupNumber = "(65) 97654321").coerced()
        assertEquals("+6591234567", c.smsNumber)
        assertEquals("6597654321", c.backupNumber)
    }

    @Test fun backupEqualToMainBecomesEmpty() =
        assertEquals("", FixedIpSettings(smsNumber = "+6591234567", backupNumber = "+65 9123 4567").coerced().backupNumber)

    @Test fun recipientsAreMainThenBackup() =
        assertEquals(listOf("+6591234567", "+6597654321"), FixedIpSettings(smsNumber = "+6591234567", backupNumber = "+6597654321").recipients)

    @Test fun emptyOrInvalidOrSameBackupIsLeftOut() {
        assertEquals(listOf("+6591234567"), FixedIpSettings(smsNumber = "+6591234567").recipients)
        assertEquals(listOf("+6591234567"), FixedIpSettings(smsNumber = "+6591234567", backupNumber = "123").recipients)
        assertEquals(listOf("+6591234567"), FixedIpSettings(smsNumber = "+6591234567", backupNumber = "+6591234567").recipients)
    }

    @Test fun noRecipientsWhenTheMainNumberIsInvalid() =
        assertEquals(emptyList<String>(), FixedIpSettings(smsNumber = "12", backupNumber = "+6597654321").recipients.filter { it == "12" })

    @Test fun switchNeedsAPinAndAValidNumber() {
        assertFalse(FixedIpSettings(smsNumber = "+6591234567").canEnable)
        assertFalse(FixedIpSettings(pinned = pin).canEnable)
        assertTrue(FixedIpSettings(pinned = pin, smsNumber = "+6591234567").canEnable)
    }
}
