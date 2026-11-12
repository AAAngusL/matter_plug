package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class AlertJsonTest {
    private val sampleEvent = AlertEvent(AlertKind.PlugUnreachable, AlertPhase.Started, 18, false, true, 0L)

    @Test fun sampleFromTheDesign() {
        val msg = "Matter Plug: battery 18%, not charging. The charger plug cannot be reached."
        val json = AlertJson.encode(sampleEvent, msg, "2026-11-11T09:47:12-05:00")
        assertEquals(
            """{"alert":"plug_unreachable","state":"started","message":"Matter Plug: battery 18%, not charging. The charger plug cannot be reached.","battery":18,"charging":false,"time":"2026-11-11T09:47:12-05:00"}""",
            json)
    }

    @Test fun chargingTrueIsUnquoted() {
        val e = sampleEvent.copy(pluggedIn = true, battery = 7)
        val json = AlertJson.encode(e, "m", "t")
        assertTrue(json, json.contains(""""battery":7,"charging":true,"time":"t"}"""))
    }

    @Test fun statusEndsWithNextCheck() {
        val e = AlertEvent(AlertKind.Status, AlertPhase.Status, 64, true, null, 0L, nextCheckInMin = 15)
        val json = AlertJson.encode(e, "Matter Plug: status. Battery 64%, plugged in.", "2026-11-11T09:47:12-05:00")
        assertEquals(
            """{"alert":"status","state":"status","message":"Matter Plug: status. Battery 64%, plugged in.","battery":64,"charging":true,"time":"2026-11-11T09:47:12-05:00","next_check_in_min":15}""",
            json)
    }

    @Test fun otherEventsHaveNoNextCheckKey() {
        for (kind in AlertKind.values()) for (phase in AlertPhase.values()) {
            val json = AlertJson.encode(AlertEvent(kind, phase, 1, false, null, 0L), "m", "t")
            assertFalse(json, json.contains("next_check_in_min"))
        }
    }

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
