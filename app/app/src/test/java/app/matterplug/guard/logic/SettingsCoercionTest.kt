package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCoercionTest {
    private val d = GuardSettings()

    @Test fun validValuesAreKept() {
        val s = GuardSettings(low = 5, high = 100, intervalMin = 60, alertAddress = "x", alertToken = "t")
        assertEquals(s, s.coerced())
    }
    @Test fun lowOutOfRangeFallsBackToDefault() {
        assertEquals(d.low, d.copy(low = 4).coerced().low)
        assertEquals(d.low, d.copy(low = 51).coerced().low)
    }
    @Test fun highOutOfRangeFallsBackToDefault() {
        assertEquals(d.high, d.copy(high = 49).coerced().high)
        assertEquals(d.high, d.copy(high = 101).coerced().high)
    }
    @Test fun gapBelowTenResetsBothToDefaults() {
        val c = d.copy(low = 45, high = 54).coerced()
        assertEquals(d.low, c.low); assertEquals(d.high, c.high)
        val ok = d.copy(low = 45, high = 55).coerced()
        assertEquals(45, ok.low); assertEquals(55, ok.high)
    }
    @Test fun badIntervalFallsBackTo15() {
        val c = d.copy(intervalMin = 7).coerced()
        assertEquals(15, c.intervalMin)
    }
    @Test fun otherFieldsUntouched() {
        val s = d.copy(enabled = true, alertsEnabled = true, statusEnabled = true, alertAddress = "http://a.b")
        assertEquals(s, s.coerced())
    }
}
