package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.matterplug.alerts.logic.AlertKind
import app.matterplug.alerts.logic.QueueCodec

class CodecsTest {
    private fun roundTrip(s: AlertState) = AlertStateCodec.decode(AlertStateCodec.encode(s))

    @Test fun roundTripNoAlerts() {
        val s = AlertState(0, 0, emptyList())
        assertEquals(s, roundTrip(s))
        val counts = AlertState(3, 5, emptyList())
        assertEquals(counts, roundTrip(counts))
    }

    @Test fun roundTripOneAlert() {
        val s = AlertState(2, 0, listOf(ActiveAlert(AlertKind.PlugUnreachable, 1000L, 2000L, true)))
        assertEquals(s, roundTrip(s))
    }

    @Test fun roundTripTwoAlertsAllWantedOnValues() {
        val s = AlertState(1, 2, listOf(
            ActiveAlert(AlertKind.PlugUnreachable, 1L, 2L, false),
            ActiveAlert(AlertKind.NotCharging, 3L, 4L, null),
        ))
        assertEquals(s, roundTrip(s))
        val s2 = AlertState(0, 0, listOf(ActiveAlert(AlertKind.PlugUnreachable, 1L, 2L, true)))
        assertEquals(s2, roundTrip(s2))
    }

    @Test fun roundTripDetailWithSpecialCharacters() {
        val detail = "a,b \"q\"\nline2 é"
        val s = AlertState(0, 2, listOf(ActiveAlert(AlertKind.GuardDegraded, 5L, 6L, null, detail)))
        assertEquals(s, roundTrip(s))
    }

    @Test fun detailThatLooksLikeNullMarker() {
        val s = AlertState(0, 0, listOf(ActiveAlert(AlertKind.GuardDegraded, 5L, 6L, null, "-")))
        assertEquals(s, roundTrip(s))
    }

    @Test fun oldFormatFLineWithOneCount() {
        assertEquals(AlertState(2, 0, emptyList()), AlertStateCodec.decode("F,2"))
    }

    @Test fun oldFormatALineWithoutDetail() {
        val st = AlertStateCodec.decode("F,1,0\nA,not_charging,10,20,-")
        assertEquals(AlertState(1, 0, listOf(ActiveAlert(AlertKind.NotCharging, 10L, 20L, null, null))), st)
    }

    @Test fun nullEmptyAndGarbageGiveDefault() {
        assertEquals(AlertState(), AlertStateCodec.decode(null))
        assertEquals(AlertState(), AlertStateCodec.decode(""))
        assertEquals(AlertState(), AlertStateCodec.decode("garbage"))
    }

    @Test fun damagedLinesAreSkipped() {
        val text = "F,1,2\nA,bogus_kind,1,2,-,-\nA,not_charging,xx,2,-,-\nA,not_charging,1,2\nA,guard_degraded,7,8,-,@@@\nnonsense\nA,plug_unreachable,3,4,1,-"
        val st = AlertStateCodec.decode(text)
        assertEquals(1, st.failedChecks)
        assertEquals(2, st.degradedChecks)
        assertEquals(listOf(ActiveAlert(AlertKind.PlugUnreachable, 3L, 4L, true, null)), st.active)
    }

    @Test fun queueRoundTrip() {
        val bodies = listOf("""{"a":"x\"y"}""", "line1\nline2", """{"b":1}""")
        val enc = QueueCodec.encode(bodies)
        assertTrue(enc, enc.lines().size == 3)
        assertEquals(bodies, QueueCodec.decode(enc))
    }

    @Test fun queueEmptyAndNull() {
        assertEquals("", QueueCodec.encode(emptyList()))
        assertEquals(emptyList<String>(), QueueCodec.decode(""))
        assertEquals(emptyList<String>(), QueueCodec.decode(null))
    }

    @Test fun queueSkipsDamagedEntry() {
        val good = QueueCodec.encode(listOf("one", "two"))
        val text = good.lines()[0] + "\n@@@\n" + good.lines()[1]
        assertEquals(listOf("one", "two"), QueueCodec.decode(text))
    }
}
