package app.matterplug.guard

import android.content.Context
import app.matterplug.guard.logic.AlertEvent
import app.matterplug.guard.logic.AlertExtra
import app.matterplug.guard.logic.AlertKind
import app.matterplug.guard.logic.AlertPhase
import app.matterplug.guard.logic.BatteryStatus
import app.matterplug.guard.logic.CancelledBy
import app.matterplug.guard.logic.PostResult
import app.matterplug.guard.logic.Power

/** A check that went through delivery: the first push it made, or null when it made none (what the console heartbeat learns from it). */
class Delivery(val firstPush: PostResult?)

/**
 * What a feature (Cellular Check, Fixed IP) uses to put an alert on the wire: build the event with the battery as it reads now, send it,
 * or queue its cancelled behind the waiting requests. The waiting list, the HTTP request and the battery fallback stay behind it.
 */
object AlertChannel {
    /** The battery could not be read: nothing is known about the power. */
    private val UNREAD_POWER = Power(false, BatteryStatus.Unknown)

    /** An event stamped with [battery] (read now unless the caller already has the reading); level 0 and unknown power when it is unreadable. */
    fun event(
        ctx: Context, kind: AlertKind, phase: AlertPhase, atMs: Long, extra: AlertExtra? = null,
        battery: BatteryReading? = BatteryReader.readOrNull(ctx), nextCheckInMin: Int? = null, cancelledBy: CancelledBy? = null,
    ) = AlertEvent(kind, phase, battery?.level ?: 0, battery?.power ?: UNREAD_POWER, null, atMs,
        nextCheckInMin = nextCheckInMin, cancelledBy = cancelledBy, extra = extra)

    /** Sends [events] and the status report under the feature's own switches; returns the first push (see [HttpAlerter.deliver]). Never throws except for cancellation. */
    suspend fun deliver(
        ctx: Context, sender: HttpAlerter.Sender.Feature, events: List<AlertEvent>, status: AlertEvent?, budgetMs: Long,
    ) = HttpAlerter.deliver(ctx, GuardStore(ctx), events, status, budgetMs, sender)

    /** Queues a cancelled for [kind] behind the waiting requests and starts the flush job. Throws on a store failure: the caller logs it. */
    suspend fun queueCancelled(ctx: Context, kind: AlertKind, by: CancelledBy, extra: AlertExtra? = null) {
        val e = event(ctx, kind, AlertPhase.Cancelled, System.currentTimeMillis(), extra, cancelledBy = by)
        GuardStore(ctx).enqueue(listOf(HttpAlerter.body(e)), guardItems = false)
        FlushJobService.schedule(ctx)
    }
}
