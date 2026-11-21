package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMessagesTest {
    private fun ev(
        kind: AlertKind, phase: AlertPhase, battery: Int = 18, plugged: Boolean = false,
        wantedOn: Boolean? = null, detail: String? = null,
        status: BatteryStatus = if (plugged) BatteryStatus.Charging else BatteryStatus.Discharging,
    ) = AlertEvent(kind, phase, battery, Power(plugged, status), wantedOn, 0L, detail)

    @Test fun notCharging() = assertEquals(
        "Matter Plug: not charging though plug is on. Check charger and cable. Battery 18%.",
        AlertMessages.text(ev(AlertKind.NotCharging, AlertPhase.Started)))

    // RECOVERY-SIGNALS.md 2.1: the alert stays on while plugged in but not taking charge, and says so.
    @Test fun notChargingWhilePluggedIn() = assertEquals(
        "Reminder: not charging though plug is on. Check charger and cable. Battery 18%.",
        AlertMessages.text(ev(AlertKind.NotCharging, AlertPhase.Reminder, plugged = true, status = BatteryStatus.NotCharging)))

    @Test fun unreachableLow() = assertEquals(
        "Matter Plug: cannot reach charger plug to turn it on. Battery 18%.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Started, wantedOn = true)))

    @Test fun unreachableHigh() = assertEquals(
        "Matter Plug: cannot reach charger plug to turn it off. Battery 87%.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Started, battery = 87, plugged = true, wantedOn = false)))

    // PLUG-WATCH.md section 4: started from a plug read, no switch wanted; the phone may be charging from another source
    @Test fun unreachableNoSwitchWanted() = assertEquals(
        "Matter Plug: cannot reach charger plug. Battery 72%.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Started, battery = 72, plugged = true, wantedOn = null)))

    @Test fun unreachableNoSwitchWantedReminder() = assertEquals(
        "Reminder: cannot reach charger plug. Battery 72%.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Reminder, battery = 72, wantedOn = null)))

    @Test fun guardNotWorkingExactAlarms() = assertEquals(
        "Matter Plug: Battery Guard stopped: exact alarms not allowed. Battery 18%.",
        AlertMessages.text(ev(AlertKind.GuardDegraded, AlertPhase.Started, detail = "exact alarms not allowed")))

    @Test fun guardNotWorkingBatteryUnreadable() = assertEquals(
        "Matter Plug: Battery Guard stopped: battery unreadable. Battery 18%.",
        AlertMessages.text(ev(AlertKind.GuardDegraded, AlertPhase.Started, detail = "battery unreadable")))

    @Test fun guardNotWorkingServiceCouldNotStart() = assertEquals(
        "Matter Plug: Battery Guard stopped: service could not start. Battery 18%.",
        AlertMessages.text(ev(AlertKind.GuardDegraded, AlertPhase.Started, detail = "service could not start")))

    @Test fun reminderStartsWithReminderPrefix() {
        val t = AlertMessages.text(ev(AlertKind.NotCharging, AlertPhase.Reminder))
        assertTrue(t, t.startsWith("Reminder: "))
        assertEquals("Reminder: not charging though plug is on. Check charger and cable. Battery 18%.", t)
    }

    @Test fun reminderOfGuardCarriesReason() = assertEquals(
        "Reminder: Battery Guard stopped: battery unreadable. Battery 18%.",
        AlertMessages.text(ev(AlertKind.GuardDegraded, AlertPhase.Reminder, detail = "battery unreadable")))

    @Test fun recoveredPlugged() = assertEquals(
        "Matter Plug: charging again. Battery 24%.",
        AlertMessages.text(ev(AlertKind.NotCharging, AlertPhase.Recovered, battery = 24, plugged = true)))

    @Test fun recoveredNotPlugged() = assertEquals(
        "Matter Plug: charger plug reachable again. Battery 24%.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Recovered, battery = 24, plugged = false)))

    @Test fun recoveredPluggedInHeldAtALimit() = assertEquals(
        "Matter Plug: charger plug reachable again. Battery 84%.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Recovered, battery = 84, plugged = true, status = BatteryStatus.NotCharging)))

    @Test fun recoveredGuard() = assertEquals(
        "Matter Plug: Battery Guard working again. Battery 24%.",
        AlertMessages.text(ev(AlertKind.GuardDegraded, AlertPhase.Recovered, battery = 24)))

    @Test fun statusCharging() = assertEquals(
        "Matter Plug: status. Battery 64%, charging.",
        AlertMessages.text(ev(AlertKind.Status, AlertPhase.Status, battery = 64, plugged = true)))

    @Test fun statusPluggedInNotCharging() = assertEquals(
        "Matter Plug: status. Battery 84%, plugged in, not charging.",
        AlertMessages.text(ev(AlertKind.Status, AlertPhase.Status, battery = 84, plugged = true, status = BatteryStatus.NotCharging)))

    @Test fun statusPluggedInStatusUnknown() = assertEquals(
        "Matter Plug: status. Battery 64%, plugged in.",
        AlertMessages.text(ev(AlertKind.Status, AlertPhase.Status, battery = 64, plugged = true, status = BatteryStatus.Unknown)))

    @Test fun statusOnBattery() = assertEquals(
        "Matter Plug: status. Battery 64%, on battery.",
        AlertMessages.text(ev(AlertKind.Status, AlertPhase.Status, battery = 64, plugged = false)))

    // RECOVERY-SIGNALS.md 2.4: one text per switch and alert.
    @Test fun cancelledTexts() {
        fun c(kind: AlertKind, by: CancelledBy) =
            AlertMessages.text(ev(kind, AlertPhase.Cancelled, battery = 64).copy(cancelledBy = by))
        val tail = "Open alert closed; problem may remain. Battery 64%."
        assertEquals("Matter Plug: Battery Guard turned off. $tail", c(AlertKind.PlugUnreachable, CancelledBy.GuardOff))
        assertEquals("Matter Plug: Battery Guard alerts turned off. $tail", c(AlertKind.NotCharging, CancelledBy.AlertsOff))
        assertEquals("Matter Plug: Battery Guard turned off. $tail", c(AlertKind.GuardDegraded, CancelledBy.GuardOff))
        assertEquals("Matter Plug: Cellular Check turned off. $tail", c(AlertKind.CellularDown, CancelledBy.CellularOff))
        assertEquals("Matter Plug: Cellular alerts turned off. $tail", c(AlertKind.CellularDown, CancelledBy.CellularAlertsOff))
        assertEquals("Matter Plug: Fixed IP turned off. $tail", c(AlertKind.IpDrift, CancelledBy.IpOff))
        assertEquals("Matter Plug: Fixed IP network unpinned. $tail", c(AlertKind.IpDrift, CancelledBy.IpUnpinned))
    }

    @Test fun cancelledNamesNoAlertAndSaysTheCauseOnce() {
        val t = AlertMessages.text(ev(AlertKind.IpDrift, AlertPhase.Cancelled).copy(cancelledBy = CancelledBy.IpOff))
        assertEquals(1, Regex("Fixed IP").findAll(t).count())
    }

    @Test fun fitCutsOnlyTheVariablePartToTheLimit() {
        val t = AlertMessages.fit("Matter Plug: Battery Guard stopped: ", "x".repeat(200), ". Battery 18%.")
        assertEquals(AlertMessages.MAX, t.length)
        assertTrue(t, t.startsWith("Matter Plug: Battery Guard stopped: x") && t.endsWith("…. Battery 18%."))
        assertEquals("a-b-c", AlertMessages.fit("a-", "b", "-c"))
    }

    @Test fun aLongFaultDetailStaysWithinTheLimit() =
        assertEquals(AlertMessages.MAX, AlertMessages.text(ev(AlertKind.GuardDegraded, AlertPhase.Started, detail = "y".repeat(300))).length)

    // Every message, at its worst case, fits 100 characters (docs/MESSAGES.md).
    @Test fun everyTextIsAtMost100Characters() {
        for (kind in AlertKind.entries) for (phase in AlertPhase.entries) for (by in listOf<CancelledBy?>(null) + CancelledBy.entries)
            for (wanted in listOf<Boolean?>(null, true, false)) {
                val e = ev(kind, phase, battery = 100, wantedOn = wanted, detail = "exact alarms not allowed").copy(cancelledBy = by)
                for (m in listOf(AlertMessages.text(e), AlertMessages.text(e.copy(phase = AlertPhase.Reminder))))
                    assertTrue("${kind.wire}/${phase.wire}/${by?.wire}: ${m.length} $m", m.length <= AlertMessages.MAX)
            }
    }

    @Test fun test() = assertEquals(
        "Matter Plug: test alert.",
        AlertMessages.text(ev(AlertKind.Test, AlertPhase.Test)))
}
