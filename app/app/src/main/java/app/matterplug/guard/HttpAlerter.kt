package app.matterplug.guard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.matterplug.guard.logic.AlertAddress
import app.matterplug.guard.logic.AlertEvent
import app.matterplug.guard.logic.AlertJson
import app.matterplug.guard.logic.AlertKind
import app.matterplug.guard.logic.AlertMessages
import app.matterplug.guard.logic.AlertPhase
import app.matterplug.guard.logic.DeliveryError
import app.matterplug.guard.logic.DeliveryPlan
import app.matterplug.guard.logic.PostResult
import app.matterplug.guard.logic.Power
import app.matterplug.guard.logic.authorizationHeader
import app.matterplug.guard.logic.resultForCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends the alert requests (BATTERY-GUARD-IMPLEMENTATION.md 5.6). The decisions (what is queued, what is sent once, the
 * timeouts) are in the pure `DeliveryPlan`; this object only does the I/O. All network calls run on Dispatchers.IO.
 * The address is never logged in full: the host only.
 */
object HttpAlerter {
    private const val TAG = "Guard"

    /** One flush at a time (the check's own flush and the flush job). A second caller skips its flush. */
    private val flushLock = Mutex()

    /** The finished JSON body of an event, as it goes on the wire and into the waiting list. */
    fun body(e: AlertEvent): String =
        AlertJson.encode(e, AlertMessages.text(e), AlertJson.isoTime(e.atMs, ZoneId.systemDefault()))

    /**
     * POST [body] to [address], Content-Type application/json; charset=utf-8, no redirects (so a token is never forwarded).
     * If [token] is not blank, the header `Authorization: Bearer <token trimmed>` is set, otherwise there is no Authorization
     * header. A response of 401 or 403 gives error "rejected". The token is never logged and never put in an error.
     * [timeoutMs] is the connect timeout, the read timeout and, by a watchdog that closes the connection, the cap on the
     * whole request. The response body is read and discarded. Never throws except for coroutine cancellation.
     */
    suspend fun post(address: String, token: String, body: String, timeoutMs: Int = 10_000): PostResult = withContext(Dispatchers.IO) {
        val t = timeoutMs.coerceAtLeast(1)
        val connRef = AtomicReference<HttpURLConnection?>(null)
        val timedOut = AtomicBoolean(false)
        val watchdog = launch {
            delay(t.toLong())
            timedOut.set(true)
            connRef.get()?.disconnect()      // aborts a blocked connect, write or read
        }
        try {
            val conn = URI(address.trim()).toURL().openConnection() as HttpURLConnection
            connRef.set(conn)
            conn.requestMethod = "POST"
            conn.connectTimeout = t
            conn.readTimeout = t
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            authorizationHeader(token)?.let { conn.setRequestProperty("Authorization", it) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            try {
                val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
                stream?.use { drain(it) }
            } catch (e: java.io.IOException) {
                // the status code is what counts; a broken body does not undo it
            }
            resultForCode(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PostResult(null, if (timedOut.get()) DeliveryError.TIMEOUT else DeliveryError.of(e))
        } finally {
            watchdog.cancel()
            connRef.get()?.disconnect()
        }
    }

    private fun drain(s: InputStream) {
        val buf = ByteArray(2048)
        var total = 0
        while (total < 65_536) {
            val n = s.read(buf)
            if (n < 0) return
            total += n
        }
    }

    /**
     * The test button: posts an AlertEvent(Test, Test, ...). Works whether or not alerts are on; needs a valid address only.
     * Does not touch the waiting list, the alert state or the delivery line.
     */
    suspend fun postTest(outbox: AlertOutbox, level: Int, power: Power): PostResult {
        val settings = outbox.readSettings()
        val address = settings.address
        if (!AlertAddress.isValid(address)) return PostResult(null, DeliveryError.BAD_ADDRESS)
        val e = AlertEvent(AlertKind.Test, AlertPhase.Test, level, power, null, System.currentTimeMillis())
        val r = post(address.trim(), settings.token, body(e), DeliveryPlan.MAX_TIMEOUT_MS.toInt())
        Log.i(TAG, "test alert host=${AlertAddress.hostOf(address)} code=${r.code} error=${r.error}")
        return r
    }

    /**
     * The 5 steps of 5.6, within [budgetMs] in total. Never throws except for cancellation.
     * 1. nothing to send -> return (alerts still go on the waiting list when the address is invalid)
     * 2. alerts on: Started and Recovered go on the waiting list
     * 3. alerts on: flush the list, oldest first
     * 4. list empty: reminders once, then the status report once (never queued)
     * 5. list not empty: schedule the flush job
     */

    /** Returns the first push this call made, or null when it made none: what the console heartbeat (docs/CONSOLE-HEARTBEAT.md) learns from a check. */
    suspend fun deliver(
        ctx: Context, outbox: AlertOutbox, events: List<AlertEvent>, status: AlertEvent?, budgetMs: Long,
        sender: Sender,
    ): PostResult? {
        val first = AtomicReference<PostResult?>(null)
        try {
            deliverInner(ctx, outbox, events, status, SystemClock.elapsedRealtime() + budgetMs, sender, first)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "delivery failed", e)
        }
        return first.get()
    }

    private suspend fun deliverInner(
        ctx: Context, outbox: AlertOutbox, events: List<AlertEvent>, status: AlertEvent?, deadline: Long, sender: Sender,
        first: AtomicReference<PostResult?>,
    ) {
        val settings = outbox.readSettings()
        val alertsOn = sender.alertsOn(settings)
        val statusOn = sender.statusOn(settings) && status != null
        val valid = AlertAddress.isValid(settings.address)

        if (alertsOn) {                                                                  // step 2
            val bodies = DeliveryPlan.toEnqueue(events, true).map { body(it) }
            if (bodies.isNotEmpty()) outbox.enqueue(bodies, sender)
        }
        val waiting = outbox.readQueue().isNotEmpty()
        if (DeliveryPlan.nothingToSend(valid, alertsOn || waiting, statusOn)) {          // step 1
            Log.i(TAG, "deliver: nothing to send (address valid=$valid alerts=$alertsOn status=$statusOn)")
            return
        }
        val address = settings.address.trim()
        val host = AlertAddress.hostOf(address)

        // Step 3: every waiting request was queued under its own sender's switch, so the list is sent whoever asks.
        if (waiting || alertsOn) flushQueue(outbox, address, settings.token, host, deadline, first)

        if (outbox.readQueue().isEmpty()) {                                               // step 4
            for (e in DeliveryPlan.extras(events, status, alertsOn, statusOn)) {
                val t = DeliveryPlan.timeoutFor(deadline - SystemClock.elapsedRealtime())
                if (t == null) { Log.i(TAG, "deliver: budget used, ${e.phase.wire} skipped"); break }
                val r = post(address, settings.token, body(e), t)
                Log.i(TAG, "deliver: ${e.kind.wire}/${e.phase.wire} host=$host code=${r.code} error=${r.error}")
                record(outbox, r)
                first.compareAndSet(null, r)
                if (!r.delivered) break         // the network is likely down: do not spend the budget on the next one
            }
        } else {                                                                         // step 5
            FlushJobService.schedule(ctx)
        }
    }

    /**
     * Steps 1 and 3 only, for the flush job. Returns true when the waiting list is still not empty AND a later try can
     * succeed (a valid address), so the job should be rescheduled. Each request was queued under its own sender's switch.
     */
    suspend fun flush(outbox: AlertOutbox, budgetMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        val settings = outbox.readSettings()
        val address = settings.address.trim()
        if (!AlertAddress.isValid(address)) {
            Log.i(TAG, "flush: address invalid, nothing to do")
            return false
        }
        flushQueue(outbox, address, settings.token, AlertAddress.hostOf(address), deadline)
        return outbox.readQueue().isNotEmpty()
    }

    /** Oldest first; each delivered entry is removed at once; stops at the first failure or when the budget is used. */
    private suspend fun flushQueue(
        outbox: AlertOutbox, address: String, token: String, host: String?, deadline: Long, first: AtomicReference<PostResult?>? = null,
    ) {
        if (!flushLock.tryLock()) { Log.i(TAG, "flush: another flush is running"); return }
        try {
            var q = outbox.readQueue()
            while (q.isNotEmpty()) {
                val t = DeliveryPlan.timeoutFor(deadline - SystemClock.elapsedRealtime())
                if (t == null) { Log.i(TAG, "flush: budget used, ${q.size} waiting"); return }
                val head = q.first()
                val r = post(address, token, head, t)
                Log.i(TAG, "flush: host=$host code=${r.code} error=${r.error} waiting=${q.size}")
                record(outbox, r)
                first?.compareAndSet(null, r)
                if (!r.delivered) return
                outbox.dequeueIfHead(head)
                q = outbox.readQueue()
            }
        } finally {
            flushLock.unlock()
        }
    }

    /** recordDelivery for every outcome: success clears the stored error, failure stores its token. */
    private suspend fun record(outbox: AlertOutbox, r: PostResult) {
        val now = System.currentTimeMillis()
        try {
            if (r.delivered) outbox.recordDelivery(now, null, null)
            else outbox.recordDelivery(null, now, DeliveryError.forFailure(r))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "recordDelivery failed", e)
        }
    }
}
