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
        "Matter Plug: battery 18%, not charging. The plug is on. Check the charger and cable.",
        AlertMessages.text(ev(AlertKind.NotCharging, AlertPhase.Started)))

    @Test fun unreachableLow() = assertEquals(
        "Matter Plug: battery 18%, not charging. The charger plug cannot be reached.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Started, wantedOn = true)))

    @Test fun unreachableHigh() = assertEquals(
        "Matter Plug: battery 87%. The charger plug cannot be reached to turn it off.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Started, battery = 87, plugged = true, wantedOn = false)))

    // PLUG-WATCH.md section 4: started from a plug read, no switch wanted; the phone may be charging from another source
    @Test fun unreachableNoSwitchWanted() = assertEquals(
        "Matter Plug: battery 72%. The charger plug cannot be reached.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Started, battery = 72, plugged = true, wantedOn = null)))

    @Test fun unreachableNoSwitchWantedReminder() = assertEquals(
        "Matter Plug (reminder): battery 72%. The charger plug cannot be reached.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Reminder, battery = 72, wantedOn = null)))

    @Test fun guardNotWorkingExactAlarms() = assertEquals(
        "Matter Plug: Battery Guard is not working: exact alarms not allowed. Battery 18%.",
        AlertMessages.text(ev(AlertKind.GuardDegraded, AlertPhase.Started, detail = "exact alarms not allowed")))

    @Test fun guardNotWorkingBatteryUnreadable() = assertEquals(
        "Matter Plug: Battery Guard is not working: battery unreadable. Battery 18%.",
        AlertMessages.text(ev(AlertKind.GuardDegraded, AlertPhase.Started, detail = "battery unreadable")))

    @Test fun guardNotWorkingServiceCouldNotStart() = assertEquals(
        "Matter Plug: Battery Guard is not working: service could not start. Battery 18%.",
        AlertMessages.text(ev(AlertKind.GuardDegraded, AlertPhase.Started, detail = "service could not start")))

    @Test fun reminderStartsWithReminderPrefix() {
        val t = AlertMessages.text(ev(AlertKind.NotCharging, AlertPhase.Reminder))
        assertTrue(t, t.startsWith("Matter Plug (reminder): "))
        assertEquals("Matter Plug (reminder): battery 18%, not charging. The plug is on. Check the charger and cable.", t)
    }

    @Test fun reminderOfGuardCarriesReason() = assertEquals(
        "Matter Plug (reminder): Battery Guard is not working: battery unreadable. Battery 18%.",
        AlertMessages.text(ev(AlertKind.GuardDegraded, AlertPhase.Reminder, detail = "battery unreadable")))

    @Test fun recoveredPlugged() = assertEquals(
        "Matter Plug: recovered. Battery 24%, charging.",
        AlertMessages.text(ev(AlertKind.NotCharging, AlertPhase.Recovered, battery = 24, plugged = true)))

    @Test fun recoveredNotPlugged() = assertEquals(
        "Matter Plug: recovered. Battery 24%, on battery.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Recovered, battery = 24, plugged = false)))

    @Test fun recoveredPluggedInHeldAtALimit() = assertEquals(
        "Matter Plug: recovered. Battery 84%, plugged in, not charging.",
        AlertMessages.text(ev(AlertKind.PlugUnreachable, AlertPhase.Recovered, battery = 84, plugged = true, status = BatteryStatus.NotCharging)))

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

    @Test fun test() = assertEquals(
        "Matter Plug: test alert.",
        AlertMessages.text(ev(AlertKind.Test, AlertPhase.Test)))
}
