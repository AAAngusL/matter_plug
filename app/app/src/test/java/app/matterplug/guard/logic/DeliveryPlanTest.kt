package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class DeliveryPlanTest {
    private fun ev(kind: AlertKind, phase: AlertPhase, next: Int? = null) =
        AlertEvent(kind, phase, 18, false, null, 1_000L, null, next)

    private val started = ev(AlertKind.PlugUnreachable, AlertPhase.Started)
    private val reminder = ev(AlertKind.NotCharging, AlertPhase.Reminder)
    private val recovered = ev(AlertKind.PlugUnreachable, AlertPhase.Recovered)
    private val status = ev(AlertKind.Status, AlertPhase.Status, 15)

    // ---- PostResult
    @Test fun delivered2xxOnly() {
        assertTrue(PostResult(200, null).delivered)
        assertTrue(PostResult(204, null).delivered)
        assertTrue(PostResult(299, null).delivered)
        assertFalse(PostResult(199, null).delivered)
        assertFalse(PostResult(300, null).delivered)
        assertFalse(PostResult(302, null).delivered)
        assertFalse(PostResult(404, null).delivered)
        assertFalse(PostResult(500, null).delivered)
        assertFalse(PostResult(null, DeliveryError.TIMEOUT).delivered)
    }

    // ---- budget
    @Test fun timeoutIsTenSecondsAtMost() {
        assertEquals(10_000, DeliveryPlan.timeoutFor(60_000))
        assertEquals(10_000, DeliveryPlan.timeoutFor(10_000))
        assertEquals(4_999, DeliveryPlan.timeoutFor(4_999))
        assertEquals(200, DeliveryPlan.timeoutFor(200))
    }

    @Test fun noRequestWhenBudgetIsUsed() {
        assertNull(DeliveryPlan.timeoutFor(199))
        assertNull(DeliveryPlan.timeoutFor(0))
        assertNull(DeliveryPlan.timeoutFor(-5))
    }

    // ---- nothing to send
    @Test fun nothingToSendCases() {
        assertTrue(DeliveryPlan.nothingToSend(addressValid = false, alertsOn = true, statusOn = true))
        assertTrue(DeliveryPlan.nothingToSend(addressValid = true, alertsOn = false, statusOn = false))
        assertFalse(DeliveryPlan.nothingToSend(addressValid = true, alertsOn = true, statusOn = false))
        // status reports do not need alerts (BATTERY-GUARD.md section 7)
        assertFalse(DeliveryPlan.nothingToSend(addressValid = true, alertsOn = false, statusOn = true))
    }

    // ---- queue
    @Test fun onlyStartedAndRecoveredAreQueued() {
        val l = listOf(started, reminder, recovered, status)
        assertEquals(listOf(started, recovered), DeliveryPlan.toEnqueue(l, true))
    }

    @Test fun nothingQueuedWhenAlertsAreOff() {
        assertEquals(emptyList<AlertEvent>(), DeliveryPlan.toEnqueue(listOf(started, recovered), false))
    }

    @Test fun queueKeepsOrderAndIsCapped() {
        assertEquals(listOf("a", "b", "c"), DeliveryPlan.appendCapped(listOf("a"), listOf("b", "c")))
        val big = (1..DeliveryPlan.MAX_QUEUE).map { "q$it" }
        val r = DeliveryPlan.appendCapped(big, listOf("new"))
        assertEquals(DeliveryPlan.MAX_QUEUE, r.size)
        assertEquals("q2", r.first())
        assertEquals("new", r.last())
    }

    @Test fun headIsRemovedOnlyWhenItIsTheSentOne() {
        assertEquals(listOf("b", "c"), DeliveryPlan.withoutHead(listOf("a", "b", "c"), "a"))
        assertEquals(listOf("b", "c"), DeliveryPlan.withoutHead(listOf("b", "c"), "a"))
        assertEquals(emptyList<String>(), DeliveryPlan.withoutHead(emptyList(), "a"))
        assertEquals(emptyList<String>(), DeliveryPlan.withoutHead(listOf("a"), "a"))
    }

    // ---- extras (reminders, then status; never queued)
    @Test fun remindersThenStatus() {
        val r2 = ev(AlertKind.PlugUnreachable, AlertPhase.Reminder)
        assertEquals(listOf(reminder, r2, status),
            DeliveryPlan.extras(listOf(started, reminder, r2, recovered), status, alertsOn = true, statusOn = true))
    }

    @Test fun noRemindersWhenAlertsOff() {
        assertEquals(listOf(status), DeliveryPlan.extras(listOf(reminder), status, alertsOn = false, statusOn = true))
    }

    @Test fun noStatusWhenStatusOff() {
        assertEquals(listOf(reminder), DeliveryPlan.extras(listOf(reminder), status, alertsOn = true, statusOn = false))
        assertEquals(emptyList<AlertEvent>(), DeliveryPlan.extras(listOf(reminder), null, alertsOn = false, statusOn = true))
    }

    @Test fun startedAndRecoveredAreNotExtras() {
        assertEquals(emptyList<AlertEvent>(), DeliveryPlan.extras(listOf(started, recovered), null, true, true))
    }

    // ---- error tokens
    @Test fun exceptionsMapToTokens() {
        assertEquals(DeliveryError.TIMEOUT, DeliveryError.of(SocketTimeoutException("connect timed out")))
        assertEquals(DeliveryError.NO_CONNECTION, DeliveryError.of(UnknownHostException("x")))
        assertEquals(DeliveryError.NO_CONNECTION, DeliveryError.of(ConnectException("refused")))
        assertEquals(DeliveryError.NO_CONNECTION, DeliveryError.of(java.net.SocketException("Network is unreachable")))
        assertEquals(DeliveryError.BAD_ADDRESS, DeliveryError.of(MalformedURLException("no protocol")))
        assertEquals(DeliveryError.BAD_ADDRESS, DeliveryError.of(IllegalArgumentException("x")))
        assertEquals(DeliveryError.TLS, DeliveryError.of(SSLHandshakeException("x")))
        assertEquals("other:EOFException", DeliveryError.of(java.io.EOFException()))
        assertEquals("other:IllegalStateException", DeliveryError.of(IllegalStateException("x")))
    }

    @Test fun failureTokens() {
        assertEquals("http:404", DeliveryError.forFailure(PostResult(404, null)))
        assertEquals("http:302", DeliveryError.forFailure(PostResult(302, null)))
        assertEquals(DeliveryError.TIMEOUT, DeliveryError.forFailure(PostResult(null, DeliveryError.TIMEOUT)))
    }

    @Test fun tokensParse() {
        assertEquals(DeliveryError.Parsed.NoConnection, DeliveryError.parse("no_connection"))
        assertEquals(DeliveryError.Parsed.Timeout, DeliveryError.parse("timeout"))
        assertEquals(DeliveryError.Parsed.BadAddress, DeliveryError.parse("bad_address"))
        assertEquals(DeliveryError.Parsed.Tls, DeliveryError.parse("tls"))
        assertEquals(DeliveryError.Parsed.Http(503), DeliveryError.parse("http:503"))
        assertEquals(DeliveryError.Parsed.Other("EOFException"), DeliveryError.parse("other:EOFException"))
        assertEquals(DeliveryError.Parsed.Other("http:abc"), DeliveryError.parse("http:abc"))   // damaged: shown as is
        assertEquals(DeliveryError.Parsed.Other("whatever"), DeliveryError.parse("whatever"))
    }

    // ---- test button
    @Test fun testOutcomes() {
        assertEquals(TestOutcome.Ok, DeliveryPlan.describeTest(PostResult(200, null)))
        assertEquals(TestOutcome.Http(204), DeliveryPlan.describeTest(PostResult(204, null)))
        assertEquals(TestOutcome.Http(404), DeliveryPlan.describeTest(PostResult(404, null)))
        assertEquals(TestOutcome.Failed(DeliveryError.Parsed.NoConnection), DeliveryPlan.describeTest(PostResult(null, "no_connection")))
        assertEquals(TestOutcome.Failed(DeliveryError.Parsed.BadAddress), DeliveryPlan.describeTest(PostResult(null, "bad_address")))
    }

    // ---- host for logs
    @Test fun hostOnlyForLogs() {
        assertEquals("192.168.1.20", AlertAddress.hostOf("http://192.168.1.20:8080/battery?token=secret"))
        assertEquals("example.com", AlertAddress.hostOf(" https://user:pw@example.com/x "))
        assertNull(AlertAddress.hostOf("http://"))
        assertNull(AlertAddress.hostOf("not an address"))
    }

    // ---- the wire bodies of every kind (what HttpAlerter posts)
    @Test fun bodiesMatchTheDocumentedShape() {
        val zone = java.time.ZoneId.of("UTC")
        fun body(e: AlertEvent) = AlertJson.encode(e, AlertMessages.text(e), AlertJson.isoTime(e.atMs, zone))
        assertTrue(body(status).endsWith(""","next_check_in_min":15}"""))
        assertFalse(body(started).contains("next_check_in_min"))
        assertTrue(body(ev(AlertKind.Test, AlertPhase.Test)).startsWith("""{"alert":"test","state":"test","message":"Matter Plug: test alert.""""))
    }
}
