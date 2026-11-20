package app.matterplug.fixedip.logic

import app.matterplug.guard.logic.AlertEvent
import app.matterplug.guard.logic.AlertJson
import app.matterplug.guard.logic.AlertKind
import app.matterplug.guard.logic.AlertMessages
import app.matterplug.guard.logic.AlertPhase
import app.matterplug.guard.logic.BatteryStatus
import app.matterplug.guard.logic.CancelledBy
import app.matterplug.guard.logic.Power
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IpReportTest {
    private val drift = IpSummary("192.168.1.37", "192.168.1.52", "Home", DriftReason.AddressChanged, SmsOutcome.Sent)
    private val power = Power(true, BatteryStatus.Charging)

    private fun event(phase: AlertPhase, s: IpSummary? = drift, by: CancelledBy? = null) =
        AlertEvent(AlertKind.IpDrift, phase, 80, power, null, 0, cancelledBy = by, ip = s)

    @Test fun jsonHasEveryKnownField() = assertEquals(
        """{"pinned":"192.168.1.37","current":"192.168.1.52","ssid":"Home","reason":"address_changed","sms":"sent"}""", IpReport.json(drift))

    @Test fun jsonLeavesOutWhatIsUnknown() = assertEquals(
        """{"pinned":"192.168.1.37","reason":"no_wifi","sms":"failed"}""",
        IpReport.json(IpSummary("192.168.1.37", null, null, DriftReason.NoWifi, SmsOutcome.Failed)))

    @Test fun driftText() = assertEquals(
        "Matter Plug: the phone's Wi-Fi address changed from 192.168.1.37 to 192.168.1.52. The dialer may not reach it.",
        IpReport.text(AlertPhase.Started, drift))

    @Test fun noWifiText() = assertEquals("Matter Plug: the phone has had no Wi-Fi for 1 minute. The dialer cannot reach it.",
        IpReport.text(AlertPhase.Started, drift.copy(reason = DriftReason.NoWifi, current = null)))

    @Test fun recoveredText() = assertEquals("Matter Plug: the phone's Wi-Fi address is back at 192.168.1.37.", IpReport.text(AlertPhase.Recovered, drift))

    @Test fun requestCarriesTheIpObjectAfterTheCommonKeys() {
        val json = AlertJson.encode(event(AlertPhase.Started), AlertMessages.text(event(AlertPhase.Started)), "2026-11-19T21:00:00-05:00")
        assertTrue(json, json.startsWith("""{"alert":"ip_drift","state":"started","message":"Matter Plug: the phone's Wi-Fi address changed"""))
        assertTrue(json, json.endsWith(""","ip":{"pinned":"192.168.1.37","current":"192.168.1.52","ssid":"Home","reason":"address_changed","sms":"sent"}}"""))
    }

    @Test fun reminderMessageSaysSo() =
        assertTrue(AlertMessages.text(event(AlertPhase.Reminder)).startsWith("Matter Plug (reminder): the phone's Wi-Fi address changed"))

    @Test fun recoveredRequestUsesTheRecoveryText() =
        assertEquals("Matter Plug: the phone's Wi-Fi address is back at 192.168.1.37.", AlertMessages.text(event(AlertPhase.Recovered)))

    @Test fun cancelledNamesTheAlertAndTheSwitch() {
        val e = event(AlertPhase.Cancelled, by = CancelledBy.IpOff)
        assertEquals("Matter Plug: Fixed IP was turned off. The Wi-Fi address alert is closed without a recovery. Battery 80%.", AlertMessages.text(e))
        assertTrue(AlertJson.encode(e, AlertMessages.text(e), "t").contains(""""cancelled_by":"ip_off""""))
    }
}
