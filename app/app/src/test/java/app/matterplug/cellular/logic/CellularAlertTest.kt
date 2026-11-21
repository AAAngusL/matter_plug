package app.matterplug.cellular.logic

import app.matterplug.guard.logic.AlertEvent
import app.matterplug.guard.logic.BatteryStatus
import app.matterplug.guard.logic.Power
import app.matterplug.guard.logic.AlertJson
import app.matterplug.guard.logic.AlertKind
import app.matterplug.guard.logic.AlertMessages
import app.matterplug.guard.logic.AlertPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellularAlertPolicyTest {
    private val hour = 60 * 60_000L

    @Test fun startsWhenTheCheckGoesDown() {
        val (memo, phase) = CellularAlertPolicy.step(null, down = true, now = 0L)
        assertEquals(AlertPhase.Started, phase)
        assertEquals(CellularAlert(0L, 0L), memo)
    }

    @Test fun nothingWhileHealthy() = assertEquals(null to null, CellularAlertPolicy.step(null, down = false, now = 0L))

    @Test fun noSecondStartAndARemindEveryHour() {
        val started = CellularAlert(0L, 0L)
        assertEquals(started to null, CellularAlertPolicy.step(started, down = true, now = hour - 1))
        val (memo, phase) = CellularAlertPolicy.step(started, down = true, now = hour)
        assertEquals(AlertPhase.Reminder, phase)
        assertEquals(CellularAlert(0L, hour), memo)
    }

    @Test fun recoversWhenNoLongerDown() =
        assertEquals(null to AlertPhase.Recovered, CellularAlertPolicy.step(CellularAlert(0L, 0L), down = false, now = 5L))

    @Test fun storedAlertSurvivesARoundTrip() {
        assertEquals(CellularAlert(5L, 9L), CellularAlertCodec.decode(CellularAlertCodec.encode(CellularAlert(5L, 9L))))
        assertNull(CellularAlertCodec.decode(CellularAlertCodec.encode(null)))
        assertNull(CellularAlertCodec.decode("garbage"))
    }
}

class CellularReportTest {
    private val airplane = mapOf(
        Layer.Sim to LayerResult.Pass("ready"),
        Layer.Registration to LayerResult.Fail("airplane mode"),
        Layer.Signal to LayerResult.Skipped(Layer.Registration),
        Layer.Sms to LayerResult.Fail("confirmation: not sent: radio off"),
    )
    private val smsOnly = mapOf(
        Layer.Sim to LayerResult.Pass("ready"),
        Layer.Registration to LayerResult.Pass("LTE, home"),
        Layer.Signal to LayerResult.Pass("LTE, RSRP -97 dBm"),
        Layer.Sms to LayerResult.Fail("daily: sent, no delivery report, not received back"),
    )

    private fun event(phase: AlertPhase, s: CellularSummary) =
        AlertEvent(AlertKind.CellularDown, phase, 64, Power(true, BatteryStatus.Charging), null, 0L, extra = s)

    @Test fun aPassiveFaultNamesTheFirstFailedLayerAndTheSmsResult() {
        val s = CellularReport.summary(airplane, LoopbackTrigger.Confirmation)
        assertEquals(Layer.Registration, s.failedLayer)
        assertEquals("airplane mode", s.reason)
        assertEquals("not sent: radio off", s.smsReason)
        assertEquals("Matter Plug: SMS unavailable: not registered (airplane mode). Battery 64%.",
            AlertMessages.text(event(AlertPhase.Started, s)))
    }

    @Test fun anSmsOnlyFaultSaysTheSignalIsFine() {
        val s = CellularReport.summary(smsOnly, LoopbackTrigger.Daily)
        assertEquals(Layer.Sms, s.failedLayer)
        assertEquals("Matter Plug: SMS not working, signal fine: sent, not received back. Battery 64%.",
            AlertMessages.text(event(AlertPhase.Started, s)))
    }

    @Test fun reminderAndRecoveredTexts() {
        val s = CellularReport.summary(smsOnly, LoopbackTrigger.Daily)
        assertTrue(AlertMessages.text(event(AlertPhase.Reminder, s)).startsWith("Reminder: SMS not working"))
        assertEquals("Matter Plug: SMS works again. Battery 64%.", AlertMessages.text(event(AlertPhase.Recovered, s)))
    }

    @Test fun theAlertBodyCarriesTheCellularObject() = assertEquals(
        """{"alert":"cellular_down","state":"started","message":"m","battery":64,"charging":true,"plugged_in":true,"battery_status":"charging","time":"t",""" +
            """"cellular":{"trigger":"confirmation","failed_layer":"registration","reason":"airplane mode",""" +
            """"sim":"pass","registration":"fail","signal":"skipped","sms":"fail","sms_reason":"not sent: radio off","signal_detail":"none"}}""",
        AlertJson.encode(event(AlertPhase.Started, CellularReport.summary(airplane, LoopbackTrigger.Confirmation)), "m", "t"),
    )

    private val utc = java.time.ZoneOffset.ofHours(-6)
    private val at = java.time.OffsetDateTime.of(2026, 11, 14, 20, 10, 50, 0, utc).toInstant().toEpochMilli()

    @Test fun theReadingGoesInAsNumbersWithItsOwnTime() = assertTrue(
        CellularReport.json(CellularReport.summary(smsOnly, LoopbackTrigger.Daily, SignalReading(3, -97, -11, 8, at)), utc).endsWith(
            ""","signal_detail":"LTE, RSRP -97 dBm","signal_reading":{"rat":"lte","level":3,"rsrp_dbm":-97,"rsrq_db":-11,"sinr_db":8,"read_at":"2026-11-14T20:10:50-06:00"}}"""),
    )

    @Test fun aMissingValueIsLeftOutNotSentAsZero() = assertTrue(
        CellularReport.json(CellularReport.summary(smsOnly, LoopbackTrigger.Daily, SignalReading(null, -101, null, null, at)), utc).endsWith(
            ""","signal_reading":{"rat":"lte","rsrp_dbm":-101,"read_at":"2026-11-14T20:10:50-06:00"}}"""),
    )

    @Test fun noReadingNoSignalReadingKey() = assertTrue(
        !CellularReport.json(CellularReport.summary(airplane, LoopbackTrigger.Confirmation, null), utc).contains("signal_reading"),
    )

    @Test fun aHealthyStatusReportHasNoFailure() = assertEquals(
        """{"trigger":"daily","sim":"pass","registration":"pass","signal":"pass","sms":"pass","signal_detail":"LTE, RSRP -97 dBm"}""",
        CellularReport.json(CellularReport.summary(smsOnly + (Layer.Sms to LayerResult.Pass("daily: sent, delivered, received back")), LoopbackTrigger.Daily)),
    )
}

/** Cellular Check's own status report (SIGNAL-STRENGTH.md draft 5, section 6). */
class CellularStatusReportTest {
    private val healthy = mapOf(
        Layer.Sim to LayerResult.Pass("ready"), Layer.Registration to LayerResult.Pass("LTE, home"),
        Layer.Signal to LayerResult.Pass("LTE, RSRP -97 dBm"), Layer.Sms to LayerResult.Pass("daily: sent, delivered, received back"),
    )
    private fun report(results: Map<Layer, LayerResult>) =
        AlertEvent(AlertKind.CellularStatus, AlertPhase.Status, 84, Power(true, BatteryStatus.Charging), null, 0L, nextCheckInMin = 15,
            extra = CellularReport.summary(results, LoopbackTrigger.Daily))

    @Test fun itsTypeIsCellularStatus() = assertTrue(
        AlertJson.encode(report(healthy), "m", "t").startsWith("""{"alert":"cellular_status","state":"status","message":"m","battery":84,"charging":true,"plugged_in":true,"battery_status":"charging","time":"t","next_check_in_min":15,"cellular":{"""),
    )

    @Test fun itsMessageNamesEveryLayer() = assertEquals(
        "Matter Plug: cellular: SIM pass, registered pass, signal pass, SMS pass. Battery 84%.",
        AlertMessages.text(report(healthy)),
    )

    @Test fun skippedAndUntestedLayersSaySo() = assertEquals(
        "Matter Plug: cellular: SIM fail, registered skipped, signal skipped, SMS not tested. Battery 84%.",
        AlertMessages.text(report(mapOf(Layer.Sim to LayerResult.Fail("SIM absent"),
            Layer.Registration to LayerResult.Skipped(Layer.Sim), Layer.Signal to LayerResult.Skipped(Layer.Sim)))),
    )
}
