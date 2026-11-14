package app.matterplug.cellular.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalTime

class CellularSettingsTest {
    @Test fun defaults() {
        val s = CellularSettings()
        assertFalse(s.enabled)
        assertEquals("", s.ownNumber)
        assertEquals(LocalTime.of(10, 0), s.smsTestTime)
    }

    @Test fun ownNumberKeepsOnlyDigitsAndALeadingPlus() =
        assertEquals("+15551234567", CellularSettings(ownNumber = " +1 (555) 123-4567 ").coerced().ownNumber)

    @Test fun aTestTimeOutsideTheDayFallsBackToTen() {
        assertEquals(600, CellularSettings(smsTestMinutes = -1).coerced().smsTestMinutes)
        assertEquals(600, CellularSettings(smsTestMinutes = 1440).coerced().smsTestMinutes)
        assertEquals(1439, CellularSettings(smsTestMinutes = 1439).coerced().smsTestMinutes)
    }
}
