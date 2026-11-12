package app.matterplug.guard.logic

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckLogTest {
    // 2026-11-12T14:05:03Z
    private val at = 1_794_492_303_000L

    @Test fun readableAndAllowedHasNoFault() = assertNull(CheckLog.degradationReason(true, true))
    @Test fun unreadableComesFirst() = assertEquals("battery unreadable", CheckLog.degradationReason(false, false))
    @Test fun exactAlarmsNotAllowed() = assertEquals("exact alarms not allowed", CheckLog.degradationReason(true, false))

    @Test fun lineWithEverything() = assertEquals(
        "level=57 plugged=false action=Nothing fault=none interval=5min next=14:05:03",
        CheckLog.line(57, false, GuardAction.Nothing, null, 5, at, ZoneOffset.UTC),
    )

    @Test fun lineWithFaultAndAction() = assertEquals(
        "level=18 plugged=false action=TurnOn fault=exact alarms not allowed interval=15min next=14:05:03",
        CheckLog.line(18, false, GuardAction.TurnOn, "exact alarms not allowed", 15, at, ZoneOffset.UTC),
    )

    @Test fun unreadableBattery() = assertEquals(
        "level=unknown plugged=unknown action=Nothing fault=battery unreadable interval=15min next=none",
        CheckLog.line(null, null, GuardAction.Nothing, "battery unreadable", 15, null, ZoneOffset.UTC),
    )

    @Test fun timeUsesTheGivenZone() = assertEquals(
        "level=50 plugged=true action=Nothing fault=none interval=30min next=16:05:03",
        CheckLog.line(50, true, GuardAction.Nothing, null, 30, at, ZoneOffset.ofHours(2)),
    )
}
