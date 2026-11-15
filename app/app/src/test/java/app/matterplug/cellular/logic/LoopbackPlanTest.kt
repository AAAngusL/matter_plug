package app.matterplug.cellular.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Drives the plan the way the alarm will: one run every 15 minutes, passive layers first, then a loopback when the
 * plan asks for one. Checks what the user sees: how many test SMS were sent and whether the alert is on.
 */
class LoopbackPlanTest {
    private val zone = ZoneOffset.UTC
    private val testTime = LocalTime.of(10, 0)
    private val stepMs = 15 * 60_000L

    private class Day(var state: CellularState = CellularState(), var sms: Int = 0, val triggers: MutableList<LoopbackTrigger> = mutableListOf())

    /** Runs from [from] for [runs] runs. [passive] gives the first failed passive layer at a time; [loopbackPasses] the loopback result. */
    private fun simulate(
        from: LocalDateTime, runs: Int, d: Day = Day(),
        passive: (LocalDateTime) -> Layer? = { null },
        loopbackPasses: (LocalDateTime) -> Boolean = { true },
    ): Day {
        var t = from
        repeat(runs) {
            val now = t.toInstant(zone).toEpochMilli()
            val failed = passive(t)
            d.state = LoopbackPlan.afterPassive(d.state, failed)
            val trigger = LoopbackPlan.next(d.state, failed, now, zone, testTime)
            if (trigger != null) {
                d.sms++
                d.triggers += trigger
                d.state = LoopbackPlan.started(d.state, trigger, failed, now)
                d.state = LoopbackPlan.finished(d.state, trigger, loopbackPasses(t), now)
            }
            t = t.plusMinutes(15)
        }
        return d
    }

    private val morning = LocalDateTime.of(2026, 11, 14, 8, 0)

    @Test fun normalDaySendsExactlyOneSmsAtTheTestTime() {
        val d = simulate(morning, runs = 96)                       // 24 hours
        assertEquals(1, d.sms)
        assertEquals(listOf(LoopbackTrigger.Daily), d.triggers)
        assertFalse(d.state.down)
    }

    @Test fun noDailySmsBeforeTheTestTime() = assertEquals(0, simulate(morning, runs = 7).sms)      // 08:00 to 09:30

    @Test fun missingSimIsConfirmedTwiceThenTheAlertStarts() {
        val d = simulate(morning, runs = 2, passive = { Layer.Sim }, loopbackPasses = { false })
        assertEquals(2, d.sms)
        assertEquals(listOf(LoopbackTrigger.Confirmation, LoopbackTrigger.Confirmation), d.triggers)
        assertTrue(d.state.down)
    }

    @Test fun oneFailedLoopbackIsNotEnoughForTheAlert() =
        assertFalse(simulate(morning, runs = 1, passive = { Layer.Sim }, loopbackPasses = { false }).state.down)

    @Test fun fourHourOutageSendsThreeSmsAndRecovers() {
        val end = morning.plusHours(4)
        val d = simulate(morning, runs = 24,                                                 // 6 hours
            passive = { t -> if (t.isBefore(end)) Layer.Registration else null },
            loopbackPasses = { t -> !t.isBefore(end) })
        assertEquals(3, d.sms)
        assertEquals(listOf(LoopbackTrigger.Confirmation, LoopbackTrigger.Confirmation, LoopbackTrigger.Recovery), d.triggers)
        assertFalse(d.state.down)
    }

    @Test fun aPassiveDipThatSmsSurvivesIsConfirmedOnceAndNoAlert() {
        val d = simulate(morning, runs = 8, passive = { Layer.Signal }, loopbackPasses = { true })   // 0 bars for 2 hours, SMS fine
        assertEquals(1, d.sms)
        assertFalse(d.state.down)
    }

    @Test fun anotherPassiveFaultLaterGetsItsOwnConfirmation() {
        val d = simulate(morning, runs = 4, passive = { t -> if (t.hour == 8 && t.minute < 30) Layer.Signal else if (t.hour == 8) Layer.Sim else null })
        assertEquals(2, d.sms)
    }

    @Test fun wrongOwnNumberFailsTheDailyTestAndTheRetryStartsTheAlert() {
        val d = simulate(morning, runs = 10, loopbackPasses = { false })                   // 08:00 to 10:15
        assertEquals(listOf(LoopbackTrigger.Daily, LoopbackTrigger.Daily), d.triggers)
        assertTrue(d.state.down)
    }

    @Test fun smsOnlyFaultIsRetestedOnceADayUntilItPasses() {
        val fixed = LocalDateTime.of(2026, 11, 16, 9, 0)
        val d = simulate(morning, runs = 96 * 3, loopbackPasses = { t -> t.isAfter(fixed) })   // 3 days
        // day 1: daily + retry (alert starts); day 2: one retest, fails; day 3: one retest, passes
        assertEquals(4, d.sms)
        assertFalse(d.state.down)
    }

    @Test fun noSecondLoopbackWhileOneIsWaiting() {
        val waiting = LoopbackPlan.started(CellularState(), LoopbackTrigger.Daily, null, at = 0L)
        assertEquals(null, LoopbackPlan.next(waiting, Layer.Sim, 0L, zone, testTime))        // a new fault would ask for one
        val done = LoopbackPlan.finished(waiting, LoopbackTrigger.Daily, passed = true, at = 0L)
        assertEquals(LoopbackTrigger.Confirmation, LoopbackPlan.next(done, Layer.Sim, 0L, zone, testTime))
    }

    @Test fun runNowSendsWhateverThePlanSays() {
        val down = CellularState(confirmedFault = Layer.Registration, failedInARow = 2)      // the plan alone would wait
        assertEquals(null, LoopbackPlan.next(down, Layer.Registration, 0L, zone, testTime))
        assertEquals(LoopbackTrigger.Manual, LoopbackPlan.next(down, Layer.Registration, 0L, zone, testTime, manualRequested = true))
    }

    @Test fun runNowWhileATestSmsIsWaitingIsKeptForLater() {
        val waiting = LoopbackPlan.started(CellularState(), LoopbackTrigger.Daily, null, at = 0L)
        assertEquals(null, LoopbackPlan.next(waiting, null, 0L, zone, testTime, manualRequested = true))   // not now…
        val done = LoopbackPlan.finished(waiting, LoopbackTrigger.Daily, passed = true, at = 0L)
        assertEquals(LoopbackTrigger.Manual, LoopbackPlan.next(done, null, 0L, zone, testTime, manualRequested = true))  // …next run
    }

    @Test fun aManualRunCountsLikeAnyOtherLoopback() {
        var s = CellularState(failedInARow = 2, lastTrigger = LoopbackTrigger.Daily)
        s = LoopbackPlan.finished(LoopbackPlan.started(s, LoopbackTrigger.Manual, null, at = 0L), LoopbackTrigger.Manual, passed = true, at = 0L)
        assertFalse(s.down)
    }
}
