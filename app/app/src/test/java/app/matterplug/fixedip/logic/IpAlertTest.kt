package app.matterplug.fixedip.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class IpAlertTest {
    private val drift = IpState.Drift(DriftReason.AddressChanged)
    private val waiting = IpState.NoWifi(dueAtMs = 100, due = false)
    private val due = IpState.NoWifi(dueAtMs = 100, due = true)

    @Test fun driftStartsAtOnce() = assertEquals(IpAlertAction.Start(DriftReason.AddressChanged), IpAlert.next(false, drift))
    @Test fun gatewayDriftStartsWithItsReason() =
        assertEquals(IpAlertAction.Start(DriftReason.GatewayChanged), IpAlert.next(false, IpState.Drift(DriftReason.GatewayChanged)))
    @Test fun noWifiStartsOnlyWhenDue() {
        assertEquals(IpAlertAction.None, IpAlert.next(false, waiting))
        assertEquals(IpAlertAction.Start(DriftReason.NoWifi), IpAlert.next(false, due))
    }
    @Test fun matchWithNothingOpenDoesNothing() = assertEquals(IpAlertAction.None, IpAlert.next(false, IpState.Match))
    @Test fun otherNetworkAndNotPinnedStartNothing() {
        assertEquals(IpAlertAction.None, IpAlert.next(false, IpState.OtherNetwork))
        assertEquals(IpAlertAction.None, IpAlert.next(false, IpState.NotPinned))
    }

    @Test fun openAlertEndsOnlyOnMatch() {
        assertEquals(IpAlertAction.End, IpAlert.next(true, IpState.Match))
        assertEquals(IpAlertAction.None, IpAlert.next(true, drift))
        assertEquals(IpAlertAction.None, IpAlert.next(true, due))
        assertEquals(IpAlertAction.None, IpAlert.next(true, waiting))
        assertEquals(IpAlertAction.None, IpAlert.next(true, IpState.OtherNetwork))
        assertEquals(IpAlertAction.None, IpAlert.next(true, IpState.NotPinned))
    }

    @Test fun aDriftThatBecomesNoWifiIsNotASecondStart() = assertEquals(IpAlertAction.None, IpAlert.next(true, due))
}

class IpAlertPolicyTest {
    private val drift = IpState.Drift(DriftReason.AddressChanged)
    private val h = IpAlertPolicy.REMINDER_MS

    @Test fun driftStartsAnAlertAndRemembersIt() {
        val (memo, phase) = IpAlertPolicy.step(null, drift, 1_000)
        assertEquals(IpAlertMemo(1_000, 1_000, DriftReason.AddressChanged), memo)
        assertEquals(app.matterplug.guard.logic.AlertPhase.Started, phase)
    }

    @Test fun nothingDueBeforeAnHour() {
        val m = IpAlertMemo(0, 0, DriftReason.AddressChanged)
        assertEquals(m to null, IpAlertPolicy.step(m, drift, h - 1))
    }

    @Test fun reminderAfterAnHourMovesTheLastSentTime() {
        val m = IpAlertMemo(0, 0, DriftReason.NoWifi)
        val (memo, phase) = IpAlertPolicy.step(m, IpState.NoWifi(0, true), h)
        assertEquals(m.copy(lastSentAtMs = h), memo)
        assertEquals(app.matterplug.guard.logic.AlertPhase.Reminder, phase)
    }

    @Test fun matchEndsTheAlert() {
        val (memo, phase) = IpAlertPolicy.step(IpAlertMemo(0, 0, DriftReason.AddressChanged), IpState.Match, 5)
        assertEquals(null, memo)
        assertEquals(app.matterplug.guard.logic.AlertPhase.Recovered, phase)
    }

    @Test fun matchWithNoAlertDoesNothing() = assertEquals(null to null, IpAlertPolicy.step(null, IpState.Match, 5))

    @Test fun memoRoundTrips() {
        val m = IpAlertMemo(12, 34, DriftReason.GatewayChanged)
        assertEquals(m, IpAlertPolicy.decode(IpAlertPolicy.encode(m)))
        assertEquals("", IpAlertPolicy.encode(null))
    }

    @Test fun damagedMemoReadsAsNoAlert() {
        for (bad in listOf(null, "", "1,2", "a,2,no_wifi", "1,2,nope", "1,2,no_wifi,x")) assertEquals(null, IpAlertPolicy.decode(bad))
    }
}
