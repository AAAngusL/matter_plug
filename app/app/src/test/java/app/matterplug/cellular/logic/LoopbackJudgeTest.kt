package app.matterplug.cellular.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class LoopbackJudgeTest {
    private val daily = LoopbackTrigger.Daily

    @Test fun sentAndReceivedBackPasses() {
        val o = LoopbackOutcome(SentStatus.Ok, DeliveryStatus.Delivered, received = true)
        assertTrue(LoopbackJudge.passed(o))
        assertEquals(LayerResult.Pass("daily: sent, delivered, received back"), LoopbackJudge.result(daily, o))
    }

    @Test fun aMissingDeliveryReportIsOnlyAWarning() {
        val o = LoopbackOutcome(SentStatus.Ok, DeliveryStatus.NoReport, received = true)
        assertTrue(LoopbackJudge.passed(o))
        assertEquals(LayerResult.Pass("daily: sent, no delivery report, received back"), LoopbackJudge.result(daily, o))
    }

    @Test fun sentButNotReceivedBackFails() {
        val o = LoopbackOutcome(SentStatus.Ok, DeliveryStatus.Delivered, received = false)
        assertFalse(LoopbackJudge.passed(o))
        assertEquals(LayerResult.Fail("daily: sent, delivered, not received back"), LoopbackJudge.result(daily, o))
    }

    @Test fun notSentNamesTheReason() {
        assertEquals(LayerResult.Fail("confirmation: not sent: no service"),
            LoopbackJudge.result(LoopbackTrigger.Confirmation, LoopbackOutcome(SentStatus.Error(4), DeliveryStatus.NoReport, false)))
        assertEquals(LayerResult.Fail("confirmation: not sent: radio off"),
            LoopbackJudge.result(LoopbackTrigger.Confirmation, LoopbackOutcome(SentStatus.Error(2), DeliveryStatus.NoReport, false)))
        assertEquals(LayerResult.Fail("daily: not sent: error code 42"),
            LoopbackJudge.result(daily, LoopbackOutcome(SentStatus.Error(42), DeliveryStatus.NoReport, false)))
        assertEquals(LayerResult.Fail("daily: not sent: no answer"),
            LoopbackJudge.result(daily, LoopbackOutcome(SentStatus.NoAnswer, DeliveryStatus.NoReport, false)))
    }

    @Test fun anUnlistedCodeUsesAndroidsOwnName() {
        assertEquals(LayerResult.Fail("confirmation: not sent: no default sms app (code 32)"),
            LoopbackJudge.result(LoopbackTrigger.Confirmation,
                LoopbackOutcome(SentStatus.Error(32, "RESULT_NO_DEFAULT_SMS_APP"), DeliveryStatus.NoReport, false)))
        assertEquals(LayerResult.Fail("daily: not sent: radio not available (code 9)"),
            LoopbackJudge.result(daily, LoopbackOutcome(SentStatus.Error(9, "RESULT_ERROR_RADIO_NOT_AVAILABLE"), DeliveryStatus.NoReport, false)))
    }

    @Test fun aReceivedMessageWithoutASentResultDoesNotPass() =
        assertFalse(LoopbackJudge.passed(LoopbackOutcome(SentStatus.NoAnswer, DeliveryStatus.NoReport, received = true)))

    @Test fun deliveryReportStatusFollowsTheSmsStandard() {
        assertEquals(DeliveryStatus.Delivered, LoopbackJudge.deliveryFromStatus(0x00))
        assertEquals(null, LoopbackJudge.deliveryFromStatus(0x20))          // still trying: keep waiting
        assertEquals(DeliveryStatus.Failed, LoopbackJudge.deliveryFromStatus(0x40))
    }

    @Test fun theTestMessageCarriesItsToken() {
        val body = LoopbackJudge.body("a1b2c3")
        assertEquals("MPCHK a1b2c3", body)
        assertTrue(LoopbackJudge.isOurs(body, "a1b2c3"))
        assertFalse(LoopbackJudge.isOurs("MPCHK zzzzzz", "a1b2c3"))
    }
}

class CellularStateCodecTest {
    @Test fun stateSurvivesARoundTrip() {
        val s = CellularState(Layer.Registration, 2, LoopbackTrigger.Confirmation, 111L, 99L, inProgress = true, startedAt = 100L,
            startedFault = Layer.Signal)
        assertEquals(s, CellularStateCodec.decode(CellularStateCodec.encode(s)))
        assertEquals(CellularState(), CellularStateCodec.decode(CellularStateCodec.encode(CellularState())))
    }

    @Test fun nothingOrGarbageIsTheDefault() {
        assertEquals(CellularState(), CellularStateCodec.decode(null))
        assertEquals(CellularState(), CellularStateCodec.decode("nonsense;;=x"))
    }
}

class ReleasedLoopbackTest {
    private val zone = ZoneOffset.UTC
    private val ten = java.time.LocalTime.of(10, 0)

    @Test fun aConfirmationCutOffByAKilledProcessIsTriedAgain() {
        val started = LoopbackPlan.started(CellularState(), LoopbackTrigger.Confirmation, Layer.Sim, at = 0L)
        val cleared = LoopbackPlan.clearStale(started, now = 11 * 60_000L)
        assertEquals(LoopbackTrigger.Confirmation, LoopbackPlan.next(cleared, Layer.Sim, 11 * 60_000L, zone, ten))
    }

    @Test fun aConfirmationThatCouldNotRunIsTriedAgainAndNotCounted() {
        val before = CellularState()
        val released = LoopbackPlan.released(LoopbackPlan.started(before, LoopbackTrigger.Confirmation, Layer.Sim, at = 0L))
        assertEquals(before, released)
        assertEquals(LoopbackTrigger.Confirmation, LoopbackPlan.next(released, Layer.Sim, 0L, zone, ten))
    }

    @Test fun aFinishedConfirmationRemembersItsFault() {
        val done = LoopbackPlan.finished(LoopbackPlan.started(CellularState(), LoopbackTrigger.Confirmation, Layer.Sim, at = 0L),
            LoopbackTrigger.Confirmation, passed = true, at = 1L)
        assertEquals(Layer.Sim, done.confirmedFault)
        assertEquals(null, LoopbackPlan.next(done, Layer.Sim, 2L, zone, ten))      // SMS was fine: no second SMS for this fault
    }
}

class StaleLoopbackTest {
    @Test fun aLoopbackLeftRunningByAKilledProcessIsReleasedAndNotCounted() {
        val s = LoopbackPlan.started(CellularState(failedInARow = 1), LoopbackTrigger.Daily, null, at = 0L)
        val cleared = LoopbackPlan.clearStale(s, now = 11 * 60_000L)
        assertFalse(cleared.inProgress)
        assertEquals(1, cleared.failedInARow)
    }

    @Test fun aLoopbackStillWithinItsTimeIsLeftAlone() =
        assertTrue(LoopbackPlan.clearStale(LoopbackPlan.started(CellularState(), LoopbackTrigger.Daily, null, at = 0L), now = 6 * 60_000L).inProgress)
}

class SentLogTest {
    private val zone = ZoneOffset.UTC
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 11, day, hour, 0).toInstant(zone).toEpochMilli()

    @Test fun countsToday() {
        val log = listOf(at(13, 10), at(14, 3), at(14, 10))
        assertEquals(2 to 3, SentLog.counts(log, now = at(14, 12), zone = zone))
    }

    @Test fun keepsOnlySevenDays() {
        val log = SentLog.add(listOf(at(1, 10), at(8, 9), at(10, 10)), at = at(14, 10))
        assertEquals(listOf(at(8, 9), at(10, 10), at(14, 10)), log)
    }
}
