package app.matterplug.checks

import android.content.Context
import app.matterplug.fixedip.IpRunner
import app.matterplug.fixedip.IpStore
import app.matterplug.alerts.Delivery
import app.matterplug.guard.CheckSchedule
import app.matterplug.heartbeat.HeartbeatRunner

/**
 * Hands a check's delivery to the console heartbeat, with the phone link it needs from Fixed IP: whether the phone is offline (read when a
 * push has failed, so it is current) and the Fixed IP numbers the SMS goes to.
 */
object ConsoleHeartbeat {
    private class FixedIpLink(private val ctx: Context) : HeartbeatRunner.PhoneLink {
        override suspend fun phoneOffline() = IpRunner.phoneOffline(ctx)
        override suspend fun smsNumbers() = IpStore(ctx).readSettings().recipients
    }

    /** After a check of Battery Guard's. Never throws except for cancellation. */
    suspend fun afterCheck(ctx: Context, delivery: Delivery) {
        HeartbeatRunner.afterCheck(ctx, delivery.firstPush, FixedIpLink(ctx))
    }

    /**
     * After a Cellular Check run. Battery Guard's own check carries the heartbeat when it is on (its stored switch, not which receiver fired:
     * Run now and a fresh install fire the cellular run too); a cellular run is a check of its own only when Battery Guard is off.
     */
    suspend fun afterCellularRun(ctx: Context, delivery: Delivery?) {
        if (delivery == null) return
        if (!CheckSchedule.now(ctx).guardCarriesHeartbeat) afterCheck(ctx, delivery)
    }
}
