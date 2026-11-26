package app.matterplug.alerts

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.matterplug.alerts.logic.AlertAddress
import app.matterplug.alerts.logic.AlertEvent
import app.matterplug.alerts.logic.AlertBody
import app.matterplug.alerts.logic.AlertKind
import app.matterplug.alerts.logic.AlertPhase
import app.matterplug.alerts.logic.DeliveryError
import app.matterplug.alerts.logic.DeliveryPlan
import app.matterplug.alerts.logic.PostResult
import app.matterplug.battery.Power
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends the alert requests (BATTERY-GUARD-IMPLEMENTATION.md 5.6). The decisions (what is queued, what is sent once, the
 * timeouts) are in the pure `DeliveryPlan`; this object only does the I/O. All network calls run on Dispatchers.IO.
 * The address is never logged in full: the host only.
 */
object HttpAlerter {
    private const val TAG = "Alerts"

    /** One flush at a time (the check's own flush and the flush job). A second caller skips its flush. */
    private val flushLock = Mutex()

    /**
     * The test button: posts an AlertEvent(Test, Test, ...). Works whether or not alerts are on; needs a valid address only.
     * Does not touch the waiting list, the alert state or the delivery line.
     */
    suspend fun postTest(outbox: AlertOutbox, level: Int, power: Power): PostResult {
        val endpoint = outbox.readEndpoint()
        val address = endpoint.address
        if (!AlertAddress.isValid(address)) return PostResult(null, DeliveryError.BAD_ADDRESS)
        val e = AlertEvent(AlertKind.Test, AlertPhase.Test, level, power, null, System.currentTimeMillis())
        val r = HttpPost.post(address.trim(), endpoint.token, AlertBody.of(e), DeliveryPlan.MAX_TIMEOUT_MS.toInt())
        Log.i(TAG, "test alert host=${AlertAddress.hostOf(address)} code=${r.code} error=${r.error}")
        return r
    }

    /**
     * Sends [events] and the [status] report under [sender]'s switches, within [budgetMs] in total (the 5 steps of 5.6). Never throws except for
     * cancellation. Returns the first push this call made, or null when it made none: what the console heartbeat (docs/CONSOLE-HEARTBEAT.md)
     * learns from a check.
     * 1. nothing to send -> return (alerts still go on the waiting list when the address is invalid)
     * 2. alerts on: Started and Recovered go on the waiting list
     * 3. alerts on: flush the list, oldest first
     * 4. list empty: reminders once, then the status report once (never queued)
     * 5. list not empty: schedule the flush job
     */
    suspend fun deliver(
        ctx: Context, outbox: AlertOutbox, sender: Sender, events: List<AlertEvent>, status: AlertEvent?, budgetMs: Long,
    ): PostResult? {
        val first = AtomicReference<PostResult?>(null)
        try {
            deliverInner(ctx, outbox, sender, events, status, SystemClock.elapsedRealtime() + budgetMs, first)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "delivery failed", e)
        }
        return first.get()
    }

    private suspend fun deliverInner(
        ctx: Context, outbox: AlertOutbox, sender: Sender, events: List<AlertEvent>, status: AlertEvent?, deadline: Long,
        first: AtomicReference<PostResult?>,
    ) {
        val endpoint = outbox.readEndpoint()
        val switches = sender.switches()
        val alertsOn = switches.alertsOn
        val statusOn = switches.statusOn && status != null
        val valid = AlertAddress.isValid(endpoint.address)

        if (alertsOn) {                                                                  // step 2
            val bodies = DeliveryPlan.toEnqueue(events, true).map(AlertBody::of)
            if (bodies.isNotEmpty()) outbox.enqueue(bodies, sender)
        }
        val waiting = outbox.readQueue().isNotEmpty()
        if (DeliveryPlan.nothingToSend(valid, alertsOn || waiting, statusOn)) {          // step 1
            Log.i(TAG, "deliver: nothing to send (address valid=$valid alerts=$alertsOn status=$statusOn)")
            return
        }
        val address = endpoint.address.trim()
        val host = AlertAddress.hostOf(address)

        // Step 3: every waiting request was queued under its own sender's switch, so the list is sent whoever asks.
        if (waiting || alertsOn) flushQueue(outbox, address, endpoint.token, host, deadline, first)

        if (outbox.readQueue().isEmpty()) {                                               // step 4
            for (e in DeliveryPlan.extras(events, status, alertsOn, statusOn)) {
                val t = DeliveryPlan.timeoutFor(deadline - SystemClock.elapsedRealtime())
                if (t == null) { Log.i(TAG, "deliver: budget used, ${e.phase.wire} skipped"); break }
                val r = HttpPost.post(address, endpoint.token, AlertBody.of(e), t)
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
        val endpoint = outbox.readEndpoint()
        val address = endpoint.address.trim()
        if (!AlertAddress.isValid(address)) {
            Log.i(TAG, "flush: address invalid, nothing to do")
            return false
        }
        flushQueue(outbox, address, endpoint.token, AlertAddress.hostOf(address), deadline)
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
                val r = HttpPost.post(address, token, head, t)
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
