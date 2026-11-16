package app.matterplug.guard.logic

import app.matterplug.cellular.logic.CellularSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RECOVERY-SIGNALS.md 2.4: an alert closed by a switch ends with cancelled. */
class SwitchOffTest {
    private val on = GuardSettings(enabled = true, alertsEnabled = true)

    @Test fun batteryGuardOffCancels() {
        val c = SwitchOff.guard(on, on.copy(enabled = false))
        assertEquals(CancelledBy.GuardOff, c.cancelledBy)
        assertTrue(c.reset)
    }

    @Test fun sendAlertsOffCancelsAndResets() {
        val c = SwitchOff.guard(on, on.copy(alertsEnabled = false))
        assertEquals(CancelledBy.AlertsOff, c.cancelledBy)
        assertTrue(c.reset)
    }

    @Test fun bothOffAtOnceIsBatteryGuardOff() =
        assertEquals(CancelledBy.GuardOff, SwitchOff.guard(on, on.copy(enabled = false, alertsEnabled = false)).cancelledBy)

    // Alerts were off: the server was told nothing, so nothing is cancelled, but what was tracked silently is dropped.
    @Test fun sendAlertsOnResetsWithoutCancel() {
        val c = SwitchOff.guard(on.copy(alertsEnabled = false), on)
        assertNull(c.cancelledBy)
        assertTrue(c.reset)
    }

    @Test fun batteryGuardOffWithAlertsOffSendsNothing() =
        assertNull(SwitchOff.guard(on.copy(alertsEnabled = false), GuardSettings(enabled = false)).cancelledBy)

    @Test fun otherChangesDoNothing() {
        val c = SwitchOff.guard(on, on.copy(low = 30, statusEnabled = true))
        assertNull(c.cancelledBy)
        assertFalse(c.reset)
        assertFalse(SwitchOff.guard(on.copy(enabled = false), on).reset)          // turning on: the state is already empty
    }

    private val cOn = CellularSettings(enabled = true, alertsEnabled = true)

    @Test fun cellularSwitches() {
        assertEquals(CancelledBy.CellularOff, SwitchOff.cellular(cOn, cOn.copy(enabled = false)).cancelledBy)
        assertEquals(CancelledBy.CellularAlertsOff, SwitchOff.cellular(cOn, cOn.copy(alertsEnabled = false)).cancelledBy)
        assertEquals(CancelledBy.CellularOff, SwitchOff.cellular(cOn, cOn.copy(enabled = false, alertsEnabled = false)).cancelledBy)
        val back = SwitchOff.cellular(cOn.copy(alertsEnabled = false), cOn)
        assertNull(back.cancelledBy)
        assertTrue(back.reset)
        assertNull(SwitchOff.cellular(cOn.copy(alertsEnabled = false), CellularSettings()).cancelledBy)
        assertFalse(SwitchOff.cellular(cOn, cOn.copy(ownNumber = "1")).reset)
    }

    @Test fun oneCancelledPerOpenAlert() {
        val active = listOf(
            ActiveAlert(AlertKind.PlugUnreachable, 1L, 2L, wantedOn = true),
            ActiveAlert(AlertKind.GuardDegraded, 1L, 2L, wantedOn = null, detail = "battery unreadable"),
        )
        val power = Power(true, BatteryStatus.Charging)
        val out = SwitchOff.cancelled(active, CancelledBy.GuardOff, 64, power, 9L)
        assertEquals(listOf(AlertKind.PlugUnreachable, AlertKind.GuardDegraded), out.map { it.kind })
        assertTrue(out.all { it.phase == AlertPhase.Cancelled && it.cancelledBy == CancelledBy.GuardOff && it.atMs == 9L })
        assertEquals(true, out[0].wantedOn)
        assertEquals("battery unreadable", out[1].detail)
        assertEquals(64, out[1].battery)
        assertEquals(power, out[1].power)
    }

    @Test fun noOpenAlertNoRequest() = assertTrue(SwitchOff.cancelled(emptyList(), CancelledBy.GuardOff, 64, Power(false, BatteryStatus.Unknown), 0L).isEmpty())

    @Test fun wireNames() {
        assertEquals("cancelled", AlertPhase.Cancelled.wire)
        assertEquals(listOf("guard_off", "alerts_off", "cellular_off", "cellular_alerts_off"),
            listOf(CancelledBy.GuardOff, CancelledBy.AlertsOff, CancelledBy.CellularOff, CancelledBy.CellularAlertsOff).map { it.wire })
    }
}
