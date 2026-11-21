package app.matterplug.heartbeat.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTest {
    private val fail = Observation.Failed
    private val ok = Observation.Delivered

    private fun run(limit: Int, vararg obs: Observation): Pair<HeartbeatState, List<HeartbeatSms?>> {
        var s = HeartbeatState()
        val sent = mutableListOf<HeartbeatSms?>()
        for (o in obs) { val (n, sms) = Heartbeat.step(s, o, limit); s = n; sent += sms }
        return s to sent
    }

    @Test fun oneMissThenASuccessResets() {
        val (s, sent) = run(2, fail, ok)
        assertEquals(HeartbeatState(), s)
        assertEquals(listOf(null, null), sent)
    }

    @Test fun theLimitSendsOnceAndNothingRepeats() {
        val (s, sent) = run(2, fail, fail, fail, fail)
        assertEquals(listOf(null, HeartbeatSms.Start, null, null), sent)
        assertTrue(s.down)
    }

    @Test fun theLimitFollowsTheSetting() {
        for (limit in HeartbeatSettings.LIMITS) {
            val sent = run(limit, *Array(limit) { fail }).second
            assertEquals(HeartbeatSms.Start, sent.last())
            assertEquals(List(limit - 1) { null }, sent.dropLast(1))
        }
    }

    @Test fun aSuccessWhileDownSendsOneRecoveryAndClears() {
        val (s, sent) = run(2, fail, fail, ok, ok)
        assertEquals(listOf(null, HeartbeatSms.Start, HeartbeatSms.Recovery, null), sent)
        assertEquals(HeartbeatState(), s)
    }

    @Test fun aSuccessWithNoAlertSendsNothing() = assertEquals(null, Heartbeat.step(HeartbeatState(1, false), ok, 2).second)

    @Test fun aCheckThatLearnedNothingChangesNothing() {
        for (s in listOf(HeartbeatState(), HeartbeatState(1, false), HeartbeatState(3, true))) {
            assertEquals(s to null, Heartbeat.step(s, Observation.None, 2))
        }
    }

    @Test fun failuresSeparatedByNoPushStillAddUp() = assertEquals(listOf(null, null, HeartbeatSms.Start),
        run(2, fail, Observation.None, fail).second.let { listOf(it[0], it[1], it[2]) })

    @Test fun loweringTheLimitBelowTheCountStartsOnTheNextMiss() =
        assertEquals(HeartbeatSms.Start, Heartbeat.step(HeartbeatState(3, false), fail, 2).second)

    @Test fun aFailedStartSmsClosesTheAlertSoTheNextMissedCheckSendsItAgain() {
        val (open, first) = Heartbeat.step(HeartbeatState(1, false), fail, 2)
        assertEquals(HeartbeatSms.Start, first)
        val reopened = Heartbeat.startNotSent(open)
        assertEquals(HeartbeatState(2, false), reopened)
        assertEquals(HeartbeatSms.Start, Heartbeat.step(reopened, fail, 2).second)
    }

    @Test fun aRetriedStartStillSendsOnlyOnce() {
        val reopened = Heartbeat.startNotSent(HeartbeatState(2, true))
        val (s, sms) = Heartbeat.step(reopened, fail, 2)
        assertEquals(HeartbeatSms.Start, sms)
        assertEquals(null, Heartbeat.step(s, fail, 2).second)
    }

    @Test fun theCountIsCapped() = assertEquals(Heartbeat.MAX_COUNT, Heartbeat.step(HeartbeatState(Heartbeat.MAX_COUNT, true), fail, 2).first.missed)

    @Test fun observeWhenNothingWasPushed() = assertEquals(Observation.None, Heartbeat.observe(null, false))
    @Test fun observeDelivered() = assertEquals(Observation.Delivered, Heartbeat.observe(true, false))
    @Test fun observeFailureCountsWhenThePhoneIsOnline() = assertEquals(Observation.Failed, Heartbeat.observe(false, false))
    @Test fun observeFailureDoesNotCountWhenThePhoneIsOffline() = assertEquals(Observation.None, Heartbeat.observe(false, true))
    @Test fun aDeliveredPushProvesTheConsoleEvenIfTheWifiLooksOdd() = assertEquals(Observation.Delivered, Heartbeat.observe(true, true))

    @Test fun textsFitInOneSmsAndSayAutodialer() {
        for (limit in HeartbeatSettings.LIMITS) assertTrue(Heartbeat.text(HeartbeatSms.Start, limit).length <= 100)
        assertEquals("Matter Plug: the autodialer has not answered 2 checks. Check it.", Heartbeat.text(HeartbeatSms.Start, 2))
        assertTrue(Heartbeat.text(HeartbeatSms.Recovery, 2).length <= 100)
    }

    @Test fun stateRoundTrips() {
        for (s in listOf(HeartbeatState(), HeartbeatState(2, true), HeartbeatState(7, false))) assertEquals(s, Heartbeat.decode(Heartbeat.encode(s)))
    }

    @Test fun damagedStateReadsAsClean() {
        for (bad in listOf(null, "", "1", "a,1", "1,2", "-1,0", "1001,0", "1,0,0")) assertEquals(HeartbeatState(), Heartbeat.decode(bad))
    }

    @Test fun limitOutsideTheChoicesFallsBackToTheDefault() {
        assertEquals(2, HeartbeatSettings.validLimit(null))
        assertEquals(2, HeartbeatSettings.validLimit(4))
        assertEquals(5, HeartbeatSettings.validLimit(5))
    }
}
