package app.matterplug.heartbeat

import android.content.Context
import android.util.Log
import app.matterplug.guard.logic.PostResult
import app.matterplug.heartbeat.logic.Heartbeat
import app.matterplug.heartbeat.logic.HeartbeatDue
import app.matterplug.heartbeat.logic.HeartbeatSms
import app.matterplug.heartbeat.logic.Observation
import app.matterplug.sms.AlertSms
import app.matterplug.sms.SmsOutcome
import kotlinx.coroutines.CancellationException

/**
 * The end of a check's delivery (docs/CONSOLE-HEARTBEAT.md section 2): the first push of the check, if there was one, tells whether the
 * autodialer console answered. Moves the count on and sends the SMS when one is due. Never throws except for cancellation.
 */
object HeartbeatRunner {
    private const val TAG = "Heartbeat"

    /** What the heartbeat needs from the app's network and SMS settings, given by the caller so the heartbeat knows no other feature. */
    interface PhoneLink {
        /** Whether the phone itself cannot reach the LAN, so a failed push says nothing about the console. */
        suspend fun phoneOffline(): Boolean
        /** The numbers the heartbeat SMS goes to. */
        suspend fun smsNumbers(): List<String>
    }

    suspend fun afterCheck(ctx: Context, firstPush: PostResult?, link: PhoneLink) {
        try {
            val store = HeartbeatStore(ctx)
            if (!store.readSettings().enabled) return
            val offline = firstPush != null && !firstPush.delivered && link.phoneOffline()
            val obs = Heartbeat.observe(firstPush?.delivered, offline)
            val due = store.apply(obs)
            Log.i(TAG, "check: obs=$obs sms=${(due as? HeartbeatDue.Send)?.sms?.name}")
            if (due is HeartbeatDue.Send) send(ctx, store, due, link)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "heartbeat failed", e)
        }
    }

    /**
     * Sends the SMS to the link's numbers and records the result for the screen. A start SMS that reaches no number closes the alert
     * again, so the next missed check sends it again; a failed recovery SMS is not repeated (the alert is already closed).
     */
    private suspend fun send(ctx: Context, store: HeartbeatStore, due: HeartbeatDue.Send, link: PhoneLink) {
        val numbers = link.smsNumbers()
        val (outcome, failure) = AlertSms.sendAndRecord(ctx, numbers, Heartbeat.text(due.sms, due.limit), store.lastSms::record)
        Log.i(TAG, "sms: ${due.sms.name} numbers=${numbers.size} outcome=${outcome.wire} failure=$failure")
        if (due.sms == HeartbeatSms.Start && outcome == SmsOutcome.Failed) store.startNotSent()
    }
}
