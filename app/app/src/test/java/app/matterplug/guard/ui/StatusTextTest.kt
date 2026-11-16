package app.matterplug.guard.ui

import app.matterplug.guard.logic.ActiveAlert
import app.matterplug.guard.logic.AlertKind
import app.matterplug.guard.logic.AlertState

import app.matterplug.guard.GuardStatus
import app.matterplug.guard.logic.PlugSighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTextTest {
    private fun fmt(ms: Long) = "t$ms"
    private fun lines(s: GuardStatus, enabled: Boolean = true, exact: Boolean = true) = StatusText.lines(s, enabled, exact, ::fmt)

    @Test fun emptyStatus() {
        assertEquals(listOf(StatusLine.NoCheckYet, StatusLine.NoAlerts, StatusLine.DeliveryNone(0)), lines(GuardStatus()))
    }

    @Test fun lastCheckAndSwitch() {
        val s = GuardStatus(lastCheckAt = 1, lastLevel = 64, lastPlugged = true, nextCheckAt = 2,
            lastSwitchAt = 3, lastSwitchOn = false, lastSwitchLevel = 87)
        val l = lines(s)
        assertEquals(StatusLine.LastCheck("t1", 64, true, "t2"), l[0])
        assertEquals(StatusLine.LastSwitch(false, 87, "t3"), l[1])
    }

    @Test fun nextCheckHiddenWhenDisabled() {
        val l = lines(GuardStatus(lastCheckAt = 1, lastLevel = 50, lastPlugged = false, nextCheckAt = 2), enabled = false)
        assertEquals(StatusLine.LastCheck("t1", 50, false, null), l[0])
    }

    @Test fun unreadableBatteryKeepsNulls() {
        val l = lines(GuardStatus(lastCheckAt = 1))
        assertEquals(StatusLine.LastCheck("t1", null, null, null), l[0])
    }

    @Test fun oneLinePerActiveAlertWithReason() {
        val st = AlertState(active = listOf(
            ActiveAlert(AlertKind.PlugUnreachable, 10, 20, true),
            ActiveAlert(AlertKind.GuardDegraded, 11, 21, null, "exact alarms not allowed")))
        val l = lines(GuardStatus(alertState = st))
        assertFalse(l.contains(StatusLine.NoAlerts))
        assertTrue(l.contains(StatusLine.Alert(AlertKind.PlugUnreachable, "t10", "t20", true, null)))
        assertTrue(l.contains(StatusLine.Alert(AlertKind.GuardDegraded, "t11", "t21", null, "exact alarms not allowed")))
    }

    @Test fun deliveryVariants() {
        assertEquals(StatusLine.DeliveryOk("t5", 0), lines(GuardStatus(lastDeliveryOkAt = 5)).last())
        assertEquals(StatusLine.DeliveryFailed("t6", "no connection", 2),
            lines(GuardStatus(lastDeliveryOkAt = 5, lastDeliveryFailAt = 6, lastDeliveryError = "no connection", queueSize = 2)).last())
        // a later success wins over an earlier failure
        assertEquals(StatusLine.DeliveryOk("t8", 0), lines(GuardStatus(lastDeliveryOkAt = 8, lastDeliveryFailAt = 6)).last())
        assertEquals(StatusLine.DeliveryNone(3), lines(GuardStatus(queueSize = 3)).last())
    }

    // PLUG-WATCH.md section 7
    @Test fun plugReachedLine() =
        assertTrue(lines(GuardStatus(plug = PlugSighting(reachedAt = 7, on = false))).contains(StatusLine.PlugReached("t7", false)))
    @Test fun plugMissedLineWinsOverTheLastReach() {
        val l = lines(GuardStatus(plug = PlugSighting(reachedAt = 7, on = true, missedSince = 9)))
        assertTrue(l.contains(StatusLine.PlugMissed("t9")))
        assertFalse(l.any { it is StatusLine.PlugReached })
    }
    @Test fun noPlugLineBeforeTheFirstRead() =
        assertFalse(lines(GuardStatus()).any { it is StatusLine.PlugReached || it is StatusLine.PlugMissed })
    @Test fun noPlugLineWhenDisabled() =
        assertFalse(lines(GuardStatus(plug = PlugSighting(missedSince = 9)), enabled = false).any { it is StatusLine.PlugMissed })

    @Test fun runErrorAndExactAlarmWarning() {
        val l = lines(GuardStatus(lastRunError = "service could not start"), exact = false)
        assertTrue(l.contains(StatusLine.RunError("service could not start")))
        assertEquals(StatusLine.ExactAlarmWarning, l.last())
        assertFalse(lines(GuardStatus(), enabled = false, exact = false).contains(StatusLine.ExactAlarmWarning))
        assertFalse(lines(GuardStatus(), exact = true).contains(StatusLine.ExactAlarmWarning))
    }
}
