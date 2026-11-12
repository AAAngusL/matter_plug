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

    @Test fun notificationIdsAreDistinct() =
        assertEquals(AlertKind.values().size, AlertKind.values().map { CheckDecision.notificationId(it) }.toSet().size)
}
