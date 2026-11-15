package app.matterplug.cellular

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.matterplug.cellular.logic.DeliveryStatus
import app.matterplug.cellular.logic.LoopbackJudge
import app.matterplug.cellular.logic.LoopbackOutcome
import app.matterplug.cellular.logic.SentStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends one loopback SMS to the phone's own number and records the three steps separately (docs/CELLULAR-CHECK.md
 * section 7): sent (60 s), received back (5 min from sending) and the delivery report (up to 30 s after the message
 * came back, else "no delivery report"). The only place Cellular Check touches SmsManager.
 */
object LoopbackSender {
    private const val TAG = "Cellular"
    private const val ACTION_SENT = "app.matterplug.cellular.SENT"
    private const val ACTION_DELIVERED = "app.matterplug.cellular.DELIVERED"
    private const val SENT_TIMEOUT_MS = 60_000L
    private const val RECEIVE_TIMEOUT_MS = 5 * 60_000L
    private const val DELIVERY_GRACE_MS = 30_000L

    val permissions = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)

    fun canSend(ctx: Context): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun run(ctx: Context, number: String, token: String): LoopbackOutcome {
        val sent = CompletableDeferred<SentStatus>()
        val delivered = CompletableDeferred<DeliveryStatus>()
        val received = CompletableDeferred<Unit>()

        val sentReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getStringExtra(EXTRA_TOKEN) != token) return
                sent.complete(if (resultCode == Activity.RESULT_OK) SentStatus.Ok else SentStatus.Error(resultCode))
            }
        }
        val deliveredReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getStringExtra(EXTRA_TOKEN) != token) return
                val pdu = i.getByteArrayExtra("pdu") ?: return
                val status = try { SmsMessage.createFromPdu(pdu, i.getStringExtra("format")).status } catch (e: Exception) { return }
                LoopbackJudge.deliveryFromStatus(status)?.let { delivered.complete(it) }      // null: still trying
            }
        }
        val smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val body = Telephony.Sms.Intents.getMessagesFromIntent(i).joinToString("") { it.messageBody ?: "" }
                if (LoopbackJudge.isOurs(body, token)) received.complete(Unit)
            }
        }

        ContextCompat.registerReceiver(ctx, sentReceiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(ctx, deliveredReceiver, IntentFilter(ACTION_DELIVERED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // SMS_RECEIVED comes from the system, guarded by its own permission.
        ContextCompat.registerReceiver(ctx, smsReceiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION), ContextCompat.RECEIVER_EXPORTED)
        try {
            try {
                smsManager(ctx).sendTextMessage(number, null, LoopbackJudge.body(token),
                    pending(ctx, ACTION_SENT, token, 1), pending(ctx, ACTION_DELIVERED, token, 2))
            } catch (e: Exception) {
                Log.e(TAG, "sendTextMessage failed", e)
                return LoopbackOutcome(SentStatus.Error(1), DeliveryStatus.NoReport, received = false)
            }
            val start = System.currentTimeMillis()
            val sentStatus = withTimeoutOrNull(SENT_TIMEOUT_MS) { sent.await() } ?: SentStatus.NoAnswer
            if (sentStatus != SentStatus.Ok) return LoopbackOutcome(sentStatus, DeliveryStatus.NoReport, received = false)

            val left = RECEIVE_TIMEOUT_MS - (System.currentTimeMillis() - start)
            val back = withTimeoutOrNull(left.coerceAtLeast(1)) { received.await() } != null
            val delivery = if (delivered.isCompleted) delivered.getCompleted()
            else if (back) withTimeoutOrNull(DELIVERY_GRACE_MS) { delivered.await() } ?: DeliveryStatus.NoReport
            else DeliveryStatus.NoReport
            return LoopbackOutcome(sentStatus, delivery, back)
        } finally {
            for (r in listOf(sentReceiver, deliveredReceiver, smsReceiver)) {
                try { ctx.unregisterReceiver(r) } catch (e: Exception) { Log.w(TAG, "unregister failed", e) }
            }
        }
    }

    /** Mutable, because the system adds the delivery report ("pdu", "format") to it; aimed at this app only. */
    private fun pending(ctx: Context, action: String, token: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, code, Intent(action).setPackage(ctx.packageName).putExtra(EXTRA_TOKEN, token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** The default SMS subscription's SmsManager, or the default one when there is none. */
    @Suppress("DEPRECATION")
    private fun smsManager(ctx: Context): SmsManager {
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = ctx.getSystemService(SmsManager::class.java)
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(subId) else base
        } else {
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    private const val EXTRA_TOKEN = "token"
}
