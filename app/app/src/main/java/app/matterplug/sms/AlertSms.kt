package app.matterplug.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Sends an alert SMS (docs/FIXED-IP.md section 6, docs/CONSOLE-HEARTBEAT.md section 4): the same text to every number, one message each, in parallel, so a slow
 * or failed send to one number never holds back or stops another. Only SEND_SMS is needed.
 */
object AlertSms {
    private const val TAG = "Sms"
    private const val ACTION_SENT = "app.matterplug.sms.SMS_SENT"
    private const val SENT_TIMEOUT_MS = 30_000L
    private const val EXTRA_TOKEN = "token"
    private const val EXTRA_INDEX = "index"

    fun canSend(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** What the send learned: per number, accepted or not, and the first failure's reason for the screen. */
    data class Sent(val accepted: List<Boolean>, val failure: String?)

    suspend fun send(ctx: Context, numbers: List<String>, text: String): Sent {
        if (numbers.isEmpty()) return Sent(emptyList(), "no number set")
        if (!canSend(ctx)) return Sent(numbers.map { false }, "SMS permission missing")
        val token = UUID.randomUUID().toString().filter { it.isLetterOrDigit() }.take(8)
        val results = numbers.map { CompletableDeferred<Pair<Boolean, String?>>() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getStringExtra(EXTRA_TOKEN) != token) return
                val index = i.getIntExtra(EXTRA_INDEX, -1)
                if (index !in results.indices) return
                results[index].complete(
                    if (resultCode == Activity.RESULT_OK) true to null else false to (SmsSupport.resultName(resultCode) ?: "error $resultCode"))
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sms = SmsSupport.smsManager(ctx)
            numbers.forEachIndexed { i, number ->
                try {
                    sms.sendTextMessage(number, null, text, pending(ctx, token, i), null)
                } catch (e: Exception) {      // permission revoked, a bad number: this number failed, the others go on
                    Log.w(TAG, "SMS to number ${i + 1} not handed to the radio", e)
                    results[i].complete(false to (e.javaClass.simpleName))
                }
            }
            val all = withTimeoutOrNull(SENT_TIMEOUT_MS) { results.awaitAll() }
                ?: results.map { if (it.isCompleted) it.getCompleted() else false to "no answer" }
            return Sent(all.map { it.first }, all.firstOrNull { !it.first }?.second)
        } finally {
            try { ctx.unregisterReceiver(receiver) } catch (e: Exception) { Log.w(TAG, "unregister failed", e) }
        }
    }

    /** Immutable: the sent result needs only our two extras. Its request code is unique per call and number, so a late result keeps its own token. */
    private fun pending(ctx: Context, token: String, index: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, (token.hashCode() and 0x3fffffff) * 4 + index, Intent(ACTION_SENT).setPackage(ctx.packageName)
            .putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_INDEX, index),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * [send], then judge the result and hand it to [record] (the caller's store, for its "Last SMS" line). Returns the outcome and the first
     * failure's reason; the caller logs it under its own tag.
     */
    suspend fun sendAndRecord(
        ctx: Context, numbers: List<String>, text: String, record: suspend (atMs: Long, outcome: SmsOutcome, detail: String?) -> Unit,
    ): Pair<SmsOutcome, String?> {
        val sent = send(ctx, numbers, text)
        val outcome = SmsResult.outcome(sent.accepted)
        record(System.currentTimeMillis(), outcome, if (outcome == SmsOutcome.Failed) sent.failure else null)
        return outcome to sent.failure
    }
}
