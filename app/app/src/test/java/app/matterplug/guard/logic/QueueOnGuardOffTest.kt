package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/** Switching Battery Guard off clears its own waiting alerts, but Cellular Check's stay: it runs with Battery Guard off. */
class QueueOnGuardOffTest {
    private val guard = """{"alert":"plug_unreachable","state":"started","message":"m","battery":1,"charging":false,"time":"t"}"""
    private val cellular = """{"alert":"cellular_down","state":"started","message":"m","battery":1,"charging":false,"time":"t"}"""

    @Test fun onlyCellularAlertsAreKept() = assertEquals(listOf(cellular), DeliveryPlan.keptWhenGuardTurnsOff(listOf(guard, cellular, guard)))

    @Test fun switchingCellularCheckOffDropsOnlyItsAlerts() =
        assertEquals(listOf(guard, guard), DeliveryPlan.withoutCellularAlerts(listOf(guard, cellular, guard)))

    @Test fun nothingWaitingStaysEmpty() = assertEquals(emptyList<String>(), DeliveryPlan.keptWhenGuardTurnsOff(emptyList()))
}
