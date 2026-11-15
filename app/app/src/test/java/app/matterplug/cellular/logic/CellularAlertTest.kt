package app.matterplug.cellular.logic

import app.matterplug.guard.logic.AlertEvent
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
        AlertEvent(AlertKind.CellularDown, phase, 64, true, null, 0L, cellular = s)

    @Test fun aPassiveFaultNamesTheFirstFailedLayerAndTheSmsResult() {
        val s = CellularReport.summary(airplane, LoopbackTrigger.Confirmation)
        assertEquals(Layer.Registration, s.failedLayer)
        assertEquals("airplane mode", s.reason)
        assertEquals("not sent: radio off", s.smsReason)
        assertEquals("Matter Plug: SMS service unavailable: not registered (airplane mode); the test SMS was not sent: radio off. Battery 64%.",
            AlertMessages.text(event(AlertPhase.Started, s)))
    }

    @Test fun anSmsOnlyFaultSaysTheSignalIsFine() {
        val s = CellularReport.summary(smsOnly, LoopbackTrigger.Daily)
        assertEquals(Layer.Sms, s.failedLayer)
        assertEquals("Matter Plug: SMS is not working although the signal is fine; the test SMS was sent, no delivery report, not received back. Battery 64%.",
            AlertMessages.text(event(AlertPhase.Started, s)))
    }

    @Test fun reminderAndRecoveredTexts() {
        val s = CellularReport.summary(smsOnly, LoopbackTrigger.Daily)
        assertTrue(AlertMessages.text(event(AlertPhase.Reminder, s)).startsWith("Matter Plug (reminder): SMS is not working"))
        assertEquals("Matter Plug: SMS works again. Battery 64%.", AlertMessages.text(event(AlertPhase.Recovered, s)))
    }

    @Test fun theAlertBodyCarriesTheCellularObject() = assertEquals(
        """{"alert":"cellular_down","state":"started","message":"m","battery":64,"charging":true,"time":"t",""" +
            """"cellular":{"trigger":"confirmation","failed_layer":"registration","reason":"airplane mode",""" +
            """"sim":"pass","registration":"fail","signal":"skipped","sms":"fail","sms_reason":"not sent: radio off","signal_detail":"none"}}""",
        AlertJson.encode(event(AlertPhase.Started, CellularReport.summary(airplane, LoopbackTrigger.Confirmation)), "m", "t"),
    )

    @Test fun aHealthyStatusReportHasNoFailure() = assertEquals(
        """{"trigger":"daily","sim":"pass","registration":"pass","signal":"pass","sms":"pass","signal_detail":"LTE, RSRP -97 dBm"}""",
        CellularReport.json(CellularReport.summary(smsOnly + (Layer.Sms to LayerResult.Pass("daily: sent, delivered, received back")), LoopbackTrigger.Daily)),
    )
}
