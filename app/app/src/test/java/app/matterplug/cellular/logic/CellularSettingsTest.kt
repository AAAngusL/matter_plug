package app.matterplug.cellular.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class CellularSettingsTest {
    @Test fun defaults() {
        val s = CellularSettings()
        assertFalse(s.enabled)
        assertEquals("", s.ownNumber)
        assertEquals(LocalTime.of(10, 0), s.smsTestTime)
        assertFalse(s.alertsEnabled)          // Cellular Check's own switches, off until switched on
        assertFalse(s.statusEnabled)
    }

    @Test fun ownNumberKeepsOnlyDigitsAndALeadingPlus() =
        assertEquals("+15551234567", CellularSettings(ownNumber = " +1 (555) 123-4567 ").coerced().ownNumber)

    @Test fun anOwnNumberNeedsAtLeastSevenDigits() {
        assertFalse(CellularSettings(ownNumber = "+").coerced().ownNumberValid)
        assertFalse(CellularSettings(ownNumber = "12345").coerced().ownNumberValid)
        assertFalse(CellularSettings(ownNumber = "").coerced().ownNumberValid)
        assertTrue(CellularSettings(ownNumber = "5551234").coerced().ownNumberValid)
        assertTrue(CellularSettings(ownNumber = "+1 555 123 4567").coerced().ownNumberValid)
    }

    @Test fun aTestTimeOutsideTheDayFallsBackToTen() {
        assertEquals(600, CellularSettings(smsTestMinutes = -1).coerced().smsTestMinutes)
        assertEquals(600, CellularSettings(smsTestMinutes = 1440).coerced().smsTestMinutes)
        assertEquals(1439, CellularSettings(smsTestMinutes = 1439).coerced().smsTestMinutes)
    }
}
