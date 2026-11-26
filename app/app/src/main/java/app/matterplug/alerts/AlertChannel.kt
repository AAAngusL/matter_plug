package app.matterplug.alerts

import android.content.Context
import android.util.Log
import app.matterplug.alerts.logic.AlertEvent
import app.matterplug.alerts.logic.AlertExtra
import app.matterplug.alerts.logic.AlertKind
import app.matterplug.alerts.logic.AlertPhase
import app.matterplug.battery.BatteryStatus
import app.matterplug.alerts.logic.CancelledBy
import app.matterplug.alerts.logic.PostResult
import app.matterplug.battery.Power
import app.matterplug.battery.BatteryReader
import kotlinx.coroutines.CancellationException
import app.matterplug.battery.BatteryReading

/** What a check's delivery step amounted to, which is what the console heartbeat learns from. */
sealed interface Delivery {
    /** The check had nothing to deliver, so there is nothing for the heartbeat to learn. */
    data object Skipped : Delivery

    /** The delivery step ran. [firstPush] is its first request, or null when it made none (no valid address, say). */
    class Ran(val firstPush: PostResult?) : Delivery
}

/**
 * The one way into the alert pipeline: build an event with the battery as it reads now, send it, close an alert, ask whether requests wait,
 * send the test request. The waiting list, the HTTP request and the battery fallback stay behind it.
 */
object AlertChannel {
    private const val TAG = "Alerts"

    /** The battery could not be read: nothing is known about the power. */
    private val UNREAD_POWER = Power(false, BatteryStatus.Unknown)

    /** An event stamped with [battery] (read now unless the caller already has the reading); level 0 and unknown power when it is unreadable. */
    fun event(
        ctx: Context, kind: AlertKind, phase: AlertPhase, atMs: Long, extra: AlertExtra? = null,
        battery: BatteryReading? = BatteryReader.readOrNull(ctx), nextCheckInMin: Int? = null, cancelledBy: CancelledBy? = null,
    ) = AlertEvent(kind, phase, battery?.level ?: 0, battery?.power ?: UNREAD_POWER, null, atMs,
        nextCheckInMin = nextCheckInMin, cancelledBy = cancelledBy, extra = extra)

    /** Sends [events] and the status report under [sender]'s switches; returns the first push (see [HttpAlerter.deliver]). Never throws except for cancellation. */
    suspend fun deliver(
        ctx: Context, sender: Sender, events: List<AlertEvent>, status: AlertEvent?, budgetMs: Long,
    ) = HttpAlerter.deliver(ctx, AlertOutbox(ctx), sender, events, status, budgetMs)

    /** Whether alert requests are waiting for a network or a server. */
    suspend fun hasWaiting(ctx: Context): Boolean = AlertOutbox(ctx).readQueue().isNotEmpty()

    /** Starts the flush job when requests wait; says whether any do. Never throws except for cancellation. */
    suspend fun flushIfWaiting(ctx: Context): Boolean = try {
        val waiting = hasWaiting(ctx)
        if (waiting) FlushJobService.schedule(ctx)
        waiting
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "flush job not scheduled", e)
        false
    }

    /** The test button: one request to the stored address, whether or not alerts are on (see [HttpAlerter.postTest]). */
    suspend fun sendTest(ctx: Context, level: Int, power: Power): PostResult = HttpAlerter.postTest(AlertOutbox(ctx), level, power)

    /**
     * An alert of [kind] was closed by a switch: its phone notification goes and, when [by] is set, a cancelled is queued behind the waiting
     * requests and the flush job started. [extra] builds the feature's part of that cancelled. Never throws except for cancellation.
     */
    suspend fun closeAlert(ctx: Context, kind: AlertKind, by: CancelledBy?, extra: suspend () -> AlertExtra? = { null }) {
        try {
            AlertNotifier.cancel(ctx, listOf(kind))
            if (by == null) return
            val e = event(ctx, kind, AlertPhase.Cancelled, System.currentTimeMillis(), extra(), cancelledBy = by)
            AlertOutbox(ctx).enqueue(listOf(HttpAlerter.body(e)), FeatureSender(alertsOn = true, statusOn = false))
            FlushJobService.schedule(ctx)
            Log.i(TAG, "alert: kind=${kind.wire} phase=cancelled by=${by.wire}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "${kind.wire}: cancelled not queued", e)
        }
    }
}
