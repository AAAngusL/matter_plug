package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckDecisionTest {
    private val off = GuardSettings(enabled = true)
    private val idle = AlertState()
    private val noSwitch = listOf<CheckEvent>(CheckEvent.NoSwitchNeeded)
    private val active = ActiveAlert(AlertKind.NotCharging, 0, 0, null)

    @Test fun nothingToDoInTheCommonCase() = assertTrue(CheckDecision.nothingToDo(off, idle, true, noSwitch))
    @Test fun statusOnMeansWork() = assertFalse(CheckDecision.nothingToDo(off.copy(statusEnabled = true), idle, true, noSwitch))
    @Test fun failedChecksMeanWork() = assertFalse(CheckDecision.nothingToDo(off, AlertState(failedChecks = 1), true, noSwitch))
    @Test fun degradedChecksMeanWork() = assertFalse(CheckDecision.nothingToDo(off, AlertState(degradedChecks = 1), true, noSwitch))
    @Test fun activeAlertMeansWork() = assertFalse(CheckDecision.nothingToDo(off, AlertState(active = listOf(active)), true, noSwitch))
    @Test fun waitingListMeansWork() = assertFalse(CheckDecision.nothingToDo(off, idle, false, noSwitch))
    @Test fun emptyEventsMeanWork() = assertFalse(CheckDecision.nothingToDo(off, idle, true, emptyList()))
    @Test fun degradedEventMeansWork() =
        assertFalse(CheckDecision.nothingToDo(off, idle, true, noSwitch + CheckEvent.Degraded("x")))
    @Test fun alertsSettingDoesNotMatter() = assertTrue(CheckDecision.nothingToDo(off.copy(alertsEnabled = true), idle, true, noSwitch))

    @Test fun alarmEventsNothingAndHealthy() = assertEquals(noSwitch, CheckDecision.alarmEvents(GuardAction.Nothing, true, null, false))
    @Test fun alarmEventsNothingUnreadable() =
        assertEquals(listOf<CheckEvent>(CheckEvent.Degraded("battery unreadable")),
            CheckDecision.alarmEvents(GuardAction.Nothing, false, "battery unreadable", false))
    @Test fun alarmEventsNothingWithFault() =
        assertEquals(noSwitch + CheckEvent.Degraded("exact alarms not allowed"),
            CheckDecision.alarmEvents(GuardAction.Nothing, true, "exact alarms not allowed", false))
    @Test fun alarmEventsServiceRefusedIsOneDegraded() =
        assertEquals(listOf<CheckEvent>(CheckEvent.Degraded("service could not start")),
            CheckDecision.alarmEvents(GuardAction.TurnOn, true, "exact alarms not allowed", true))
    @Test fun alarmEventsServiceRefusedAlone() =
        assertEquals(listOf<CheckEvent>(CheckEvent.Degraded("service could not start")),
            CheckDecision.alarmEvents(GuardAction.TurnOff, true, null, true))

    // PLUG-WATCH.md section 4: the plug read is passed on as its own event
    private val reached = CheckEvent.PlugRead(true)
    private val missed = CheckEvent.PlugRead(false)
    @Test fun alarmEventsWithAReachedRead() =
        assertEquals(noSwitch + reached, CheckDecision.alarmEvents(GuardAction.Nothing, true, null, false, plugReached = true))
    @Test fun alarmEventsWithAMissedRead() =
        assertEquals(noSwitch + missed, CheckDecision.alarmEvents(GuardAction.Nothing, true, null, false, plugReached = false))
    @Test fun alarmEventsUnreadableBatteryStillCarriesTheRead() =
        assertEquals(listOf(missed, CheckEvent.Degraded("battery unreadable")),
            CheckDecision.alarmEvents(GuardAction.Nothing, false, "battery unreadable", false, plugReached = false))
    @Test fun alarmEventsNoRead() = assertEquals(noSwitch, CheckDecision.alarmEvents(GuardAction.Nothing, true, null, false, plugReached = null))
    @Test fun aReachedReadIsStillNothingToDo() = assertTrue(CheckDecision.nothingToDo(off, idle, true, noSwitch + reached))
    @Test fun aMissedReadMeansWork() = assertFalse(CheckDecision.nothingToDo(off, idle, true, noSwitch + missed))
    @Test fun aReachedReadAloneMeansWork() = assertFalse(CheckDecision.nothingToDo(off, idle, true, listOf(reached)))

    @Test fun notificationIdsAreDistinct() =
        assertEquals(AlertKind.values().size, AlertKind.values().map { CheckDecision.notificationId(it) }.toSet().size)

    @Test fun noPlug_addsEventAndStillEndsEarly() {
        val ev = CheckDecision.alarmEvents(GuardAction.Nothing, true, null, false, null, noPlug = true)
        assertEquals(listOf(CheckEvent.NoSwitchNeeded, CheckEvent.NoPlug), ev)
        assertTrue(CheckDecision.nothingToDo(off, idle, true, ev))
    }
}
