package app.matterplug.alerts.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import app.matterplug.battery.BatteryStatus
import app.matterplug.battery.Power

class AlertJsonTest {
    private val sampleEvent = AlertEvent(AlertKind.PlugUnreachable, AlertPhase.Started, 18, Power(false, BatteryStatus.Discharging), true, 0L)

    @Test fun sampleFromTheDesign() {
        val msg = "Matter Plug: battery 18%, not charging. The charger plug cannot be reached."
        val json = AlertJson.encode(sampleEvent, msg, "2026-11-11T09:47:12-05:00")
        assertEquals(
            """{"alert":"plug_unreachable","state":"started","message":"Matter Plug: battery 18%, not charging. The charger plug cannot be reached.","battery":18,"charging":false,"plugged_in":false,"battery_status":"discharging","time":"2026-11-11T09:47:12-05:00"}""",
            json)
    }

    @Test fun chargingTrueIsUnquoted() {
        val e = sampleEvent.copy(power = Power(true, BatteryStatus.Charging), battery = 7)
        val json = AlertJson.encode(e, "m", "t")
        assertTrue(json, json.contains(""""battery":7,"charging":true,"plugged_in":true,"battery_status":"charging","time":"t"}"""))
    }

    // Plugged in but held at a charge limit: on power, not charging (what dumpsys battery shows as status 4).
    @Test fun pluggedInButNotCharging() {
        val e = sampleEvent.copy(power = Power(true, BatteryStatus.NotCharging), battery = 84)
        val json = AlertJson.encode(e, "m", "t")
        assertTrue(json, json.contains(""""battery":84,"charging":false,"plugged_in":true,"battery_status":"not_charging","time":"t"}"""))
    }

    @Test fun statusEndsWithNextCheck() {
        val e = AlertEvent(AlertKind.Status, AlertPhase.Status, 64, Power(true, BatteryStatus.Full), null, 0L, nextCheckInMin = 15)
        val json = AlertJson.encode(e, "Matter Plug: status. Battery 64%, plugged in, full.", "2026-11-11T09:47:12-05:00")
        assertEquals(
            """{"alert":"status","state":"status","message":"Matter Plug: status. Battery 64%, plugged in, full.","battery":64,"charging":false,"plugged_in":true,"battery_status":"full","time":"2026-11-11T09:47:12-05:00","next_check_in_min":15}""",
            json)
    }

    @Test fun otherEventsHaveNoNextCheckKey() {
        for (kind in AlertKind.values()) for (phase in AlertPhase.values()) {
            val json = AlertJson.encode(AlertEvent(kind, phase, 1, Power(false, BatteryStatus.Unknown), null, 0L), "m", "t")
            assertFalse(json, json.contains("next_check_in_min"))
        }
    }

    @Test fun cancelledCarriesCancelledByAfterTime() {
        val e = sampleEvent.copy(phase = AlertPhase.Cancelled, cancelledBy = CancelledBy.GuardOff)
        assertEquals(
            """{"alert":"plug_unreachable","state":"cancelled","message":"m","battery":18,"charging":false,"plugged_in":false,"battery_status":"discharging","time":"t","cancelled_by":"guard_off"}""",
            AlertJson.encode(e, "m", "t"))
    }

    @Test fun onlyCancelledHasCancelledBy() =
        assertFalse(AlertJson.encode(sampleEvent, "m", "t").contains("cancelled_by"))

    @Test fun messageIsEscaped() {
        val json = AlertJson.encode(sampleEvent, "a\"b\\c\nd\te", "t")
        assertTrue(json, json.contains(""""message":"a\"b\\c\nd\te","""))
    }

    @Test fun controlCharactersAndCarriageReturn() {
        assertEquals("\"\\r\\u0001\\u001f\"", AlertJson.q("\r\u0001\u001f"))
        assertEquals("\"plain é\"", AlertJson.q("plain é"))
    }

    @Test fun isoTimeWithOffset() {
        val ms = Instant.parse("2026-11-11T14:47:12Z").toEpochMilli()
        assertEquals("2026-11-11T09:47:12-05:00", AlertJson.isoTime(ms, ZoneOffset.ofHours(-5)))
    }

    @Test fun isoTimeDropsMillisAndKeepsZeroSeconds() {
        val ms = Instant.parse("2026-11-11T14:47:00Z").toEpochMilli() + 789
        assertEquals("2026-11-11T09:47:00-05:00", AlertJson.isoTime(ms, ZoneOffset.ofHours(-5)))
    }

    @Test fun isoTimeInUtcUsesZ() {
        val ms = Instant.parse("2026-11-11T14:47:12Z").toEpochMilli()
        assertEquals("2026-11-11T14:47:12Z", AlertJson.isoTime(ms, ZoneOffset.UTC))
    }
}
