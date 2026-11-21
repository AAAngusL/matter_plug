package app.matterplug.heartbeat

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.matterplug.fixedip.IpReader
import app.matterplug.fixedip.IpSms
import app.matterplug.fixedip.IpStore
import app.matterplug.fixedip.logic.IpState
import app.matterplug.fixedip.logic.IpVerdict
import app.matterplug.fixedip.logic.SmsOutcome
import app.matterplug.fixedip.logic.SmsResult
import app.matterplug.guard.logic.PostResult
import app.matterplug.heartbeat.logic.Heartbeat
import app.matterplug.heartbeat.logic.HeartbeatSms
import app.matterplug.heartbeat.logic.Observation
import kotlinx.coroutines.CancellationException

/**
 * The end of a check's delivery (docs/CONSOLE-HEARTBEAT.md section 2): the first push of the check, if there was one, tells whether the
 * autodialer console answered. Moves the count on and sends the SMS when one is due. Never throws except for cancellation.
 */
object HeartbeatRunner {
    private const val TAG = "Heartbeat"

    suspend fun afterCheck(ctx: Context, firstPush: PostResult?) {
        try {
            val store = HeartbeatStore(ctx)
            if (!store.readSettings().enabled) return
            val offline = firstPush != null && !firstPush.delivered && phoneOffline(ctx)
            val obs = Heartbeat.observe(firstPush?.delivered, offline)
            val (sms, limit) = store.apply(obs) ?: return
            Log.i(TAG, "check: obs=$obs sms=${sms?.name}")
            if (sms == null) return
            val numbers = IpStore(ctx).readSettings().recipients
            val sent = IpSms.send(ctx, numbers, Heartbeat.text(sms, limit))
            val outcome = SmsResult.outcome(sent.accepted)
            store.recordSms(System.currentTimeMillis(), outcome, if (outcome == SmsOutcome.Failed) sent.failure else null)
            Log.i(TAG, "sms: ${sms.name} numbers=${numbers.size} accepted=${sent.accepted.count { it }} failure=${sent.failure}")
            if (sms == HeartbeatSms.Start && outcome == SmsOutcome.Failed) store.startNotSent()      // the next missed check tries again
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "heartbeat failed", e)
        }
    }

    /**
     * Section 3: when Fixed IP is on, a phone with no Wi-Fi, or on another network than the pinned one, cannot reach the console, and that
     * is Fixed IP's alert, not this one. With Fixed IP off the app cannot tell, so the failure counts.
     */
    private suspend fun phoneOffline(ctx: Context): Boolean {
        val ip = IpStore(ctx)
        val settings = ip.readSettings()
        val pinned = settings.pinned
        if (!settings.enabled || pinned == null) return false
        val now = System.currentTimeMillis()
        val state = IpVerdict.judge(pinned, IpReader.read(ctx), ip.readNoWifiSince(), now - SystemClock.elapsedRealtime(), now)
        return state is IpState.NoWifi || state is IpState.OtherNetwork
    }
}
