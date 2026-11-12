package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GuardSettingsTest {
    @Test fun defaults() {
        val s = GuardSettings()
        assertFalse(s.enabled)
        assertEquals(20, s.low)
        assertEquals(85, s.high)
        assertEquals(15, s.chargingIntervalMin)
        assertEquals(15, s.batteryIntervalMin)
        assertFalse(s.alertsEnabled)
        assertFalse(s.statusEnabled)
        assertEquals("", s.alertAddress)
        assertEquals("", s.alertToken)
    }

    @Test fun lowRanges() {
        assertEquals(5..50, GuardSettings.lowRange(85))
        assertEquals(5..45, GuardSettings.lowRange(55))
    }

    @Test fun highRanges() {
        assertEquals(50..100, GuardSettings.highRange(20))
        assertEquals(55..100, GuardSettings.highRange(45))
    }

    @Test fun intervalsAreExact() = assertEquals(listOf(5, 10, 15, 30, 60), GuardSettings.INTERVALS)

    @Test fun intervalFor() {
        val s = GuardSettings(chargingIntervalMin = 30, batteryIntervalMin = 5)
        assertEquals(30, s.intervalFor(true))
        assertEquals(5, s.intervalFor(false))
    }
}
