package app.matterplug.fixedip.logic

import app.matterplug.sms.SmsOutcome
import app.matterplug.alerts.logic.AlertEvent
import app.matterplug.alerts.logic.AlertJson
import app.matterplug.alerts.logic.AlertKind
import app.matterplug.alerts.logic.AlertMessages
import app.matterplug.alerts.logic.AlertPhase
import app.matterplug.battery.BatteryStatus
import app.matterplug.alerts.logic.CancelledBy
import app.matterplug.battery.Power
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IpReportTest {
    private val drift = IpSummary("192.168.1.37", "192.168.1.52", "Home", DriftReason.AddressChanged, SmsOutcome.Sent)
    private val power = Power(true, BatteryStatus.Charging)

    private fun event(phase: AlertPhase, s: IpSummary? = drift, by: CancelledBy? = null) =
        AlertEvent(AlertKind.IpDrift, phase, 80, power, null, 0, cancelledBy = by, extra = s)

    @Test fun jsonHasEveryKnownField() = assertEquals(
        """{"pinned":"192.168.1.37","current":"192.168.1.52","ssid":"Home","reason":"address_changed","sms":"sent"}""", IpReport.json(drift))

    @Test fun jsonLeavesOutWhatIsUnknown() = assertEquals(
        """{"pinned":"192.168.1.37","reason":"no_wifi","sms":"failed"}""",
        IpReport.json(IpSummary("192.168.1.37", null, null, DriftReason.NoWifi, SmsOutcome.Failed)))

    @Test fun driftText() = assertEquals(
        "Matter Plug: IP changed from 192.168.1.37 to 192.168.1.52. Dialer may lose it. Battery 61%.",
        IpReport.text(AlertPhase.Started, drift, 61))

    @Test fun gatewayText() = assertEquals(
        "Matter Plug: IP gateway changed. IP is 192.168.1.52. Dialer may not reach it. Battery 61%.",
        IpReport.text(AlertPhase.Started, drift.copy(reason = DriftReason.GatewayChanged), 61))

    @Test fun anUnreadBatteryLeavesTheSentenceOut() = assertEquals(
        "Matter Plug: IP changed from 192.168.1.37 to 192.168.1.52. Dialer may lose it.", IpReport.text(AlertPhase.Started, drift, 0))

    // The SMS limit is 160 and the design limit 100: the longest case is two 15-character addresses and a full battery.
    @Test fun everyTextFitsWithTheLongestAddresses() {
        val long = IpSummary("192.168.100.100", "192.168.100.100", "Home", DriftReason.AddressChanged, SmsOutcome.Sent)
        for (r in DriftReason.entries) for (p in listOf(AlertPhase.Started, AlertPhase.Recovered)) {
            val t = IpReport.text(p, long.copy(reason = r), 100)
            assertTrue("${t.length} $t", t.length <= 100)
            val rem = AlertMessages.text(event(AlertPhase.Reminder, long.copy(reason = r)).copy(battery = 100))
            assertTrue("${rem.length} $rem", rem.length <= 100)
        }
    }

    @Test fun noWifiText() = assertEquals("Matter Plug: no Wi-Fi for 1 minute. Dialer cannot reach it. Battery 61%.",
        IpReport.text(AlertPhase.Started, drift.copy(reason = DriftReason.NoWifi, current = null), 61))

    @Test fun recoveredText() = assertEquals("Matter Plug: IP is back at 192.168.1.37. Battery 61%.", IpReport.text(AlertPhase.Recovered, drift, 61))

    @Test fun requestCarriesTheIpObjectAfterTheCommonKeys() {
        val json = AlertJson.encode(event(AlertPhase.Started), AlertMessages.text(event(AlertPhase.Started)), "2026-11-19T21:00:00-05:00")
        assertTrue(json, json.startsWith("""{"alert":"ip_drift","state":"started","message":"Matter Plug: IP changed from"""))
        assertTrue(json, json.endsWith(""","ip":{"pinned":"192.168.1.37","current":"192.168.1.52","ssid":"Home","reason":"address_changed","sms":"sent"}}"""))
    }

    @Test fun reminderMessageSaysSo() =
        assertTrue(AlertMessages.text(event(AlertPhase.Reminder)).startsWith("Reminder: IP changed from"))

    @Test fun recoveredRequestUsesTheRecoveryText() =
        assertEquals("Matter Plug: IP is back at 192.168.1.37. Battery 80%.", AlertMessages.text(event(AlertPhase.Recovered)))

    @Test fun cancelledSaysTheCauseOnce() {
        val e = event(AlertPhase.Cancelled, by = CancelledBy.IpOff)
        assertEquals("Matter Plug: Fixed IP turned off. Open alert closed; problem may remain. Battery 80%.", AlertMessages.text(e))
        assertTrue(AlertJson.encode(e, AlertMessages.text(e), "t").contains(""""cancelled_by":"ip_off""""))
    }

    @Test fun unpinningHasItsOwnCause() {
        val e = event(AlertPhase.Cancelled, by = CancelledBy.IpUnpinned)
        assertEquals("Matter Plug: Fixed IP network unpinned. Open alert closed; problem may remain. Battery 80%.", AlertMessages.text(e))
        assertTrue(AlertJson.encode(e, AlertMessages.text(e), "t").contains(""""cancelled_by":"ip_unpinned""""))
    }
}
