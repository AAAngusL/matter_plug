package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class GuardPolicyTest {
    private val s = GuardSettings(low = 20, high = 85)
    private fun d(level: Int, plugged: Boolean, settings: GuardSettings = s) = GuardPolicy.decide(level, plugged, settings)

    @Test fun at20OnBatteryTurnsOn() = assertEquals(GuardAction.TurnOn, d(20, false))
    @Test fun at19OnBatteryTurnsOn() = assertEquals(GuardAction.TurnOn, d(19, false))
    @Test fun at21OnBatteryDoesNothing() = assertEquals(GuardAction.Nothing, d(21, false))
    @Test fun at20PluggedDoesNothing() = assertEquals(GuardAction.Nothing, d(20, true))
    @Test fun at0OnBatteryTurnsOn() = assertEquals(GuardAction.TurnOn, d(0, false))
    @Test fun at85PluggedTurnsOff() = assertEquals(GuardAction.TurnOff, d(85, true))
    @Test fun at86PluggedTurnsOff() = assertEquals(GuardAction.TurnOff, d(86, true))
    @Test fun at84PluggedDoesNothing() = assertEquals(GuardAction.Nothing, d(84, true))
    @Test fun at85OnBatteryDoesNothing() = assertEquals(GuardAction.Nothing, d(85, false))

    @Test fun at50EitherDoesNothing() {
        assertEquals(GuardAction.Nothing, d(50, true))
        assertEquals(GuardAction.Nothing, d(50, false))
    }

    @Test fun highOf100NeverTurnsOff() {
        val h = s.copy(high = 100)
        assertEquals(GuardAction.Nothing, d(100, true, h))
        assertEquals(GuardAction.Nothing, d(99, true, h))
    }
}
