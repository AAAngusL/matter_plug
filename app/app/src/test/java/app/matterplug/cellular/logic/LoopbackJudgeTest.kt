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
        val s = CellularState(Layer.Registration, 2, LoopbackTrigger.Confirmation, 111L, 99L, inProgress = true, startedAt = 100L)
        assertEquals(s, CellularStateCodec.decode(CellularStateCodec.encode(s)))
        assertEquals(CellularState(), CellularStateCodec.decode(CellularStateCodec.encode(CellularState())))
    }

    @Test fun nothingOrGarbageIsTheDefault() {
        assertEquals(CellularState(), CellularStateCodec.decode(null))
        assertEquals(CellularState(), CellularStateCodec.decode("nonsense;;=x"))
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
