package app.matterplug.cellular

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.matterplug.R
import app.matterplug.alarm.partialWakeLock
import app.matterplug.cellular.logic.LoopbackTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service for one loopback SMS (it waits up to 5 minutes for the message to come back). Started by
 * CellularRunner after it has marked the loopback as running; it sends, waits, records the result, and stops itself.
 */
class LoopbackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            enterForeground()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        val trigger = LoopbackTrigger.entries.firstOrNull { it.wire == intent?.getStringExtra(EXTRA_TRIGGER) }
        val number = intent?.getStringExtra(EXTRA_NUMBER)
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        if (trigger == null || number.isNullOrEmpty() || token == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val app = applicationContext
        scope.launch {
            val wakeLock = partialWakeLock(app, WAKE_TAG, SERVICE_TIMEOUT_MS + 30_000L, TAG)
            try {
                val outcome = withTimeoutOrNull(SERVICE_TIMEOUT_MS) { LoopbackSender.run(app, number, token) }
                withContext(NonCancellable) { CellularRunner.finishLoopback(app, trigger, outcome) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "loopback failed", e)
                withContext(NonCancellable) { CellularRunner.finishLoopback(app, trigger, null) }
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (e: Exception) { Log.w(TAG, "wake lock release failed", e) }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.cellular_channel_work), NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        nm.createNotificationChannel(channel)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(getString(R.string.cellular_title))
            .setContentText(getString(R.string.cellular_sending_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val TAG = "Cellular"
        private const val CHANNEL_ID = "cellular_work"
        private const val NOTIFICATION_ID = 4201
        private const val WAKE_TAG = "matterplug:cellular"
        /** Sent 60 s, back within 5 min, delivery grace 30 s: 6 minutes covers it. */
        private const val SERVICE_TIMEOUT_MS = 6 * 60_000L
        private const val EXTRA_TRIGGER = "trigger"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_TOKEN = "token"

        /** False when Android refused to start it (the caller releases the loopback without counting it). */
        fun start(ctx: Context, trigger: LoopbackTrigger, number: String, token: String): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LoopbackService::class.java)
                .putExtra(EXTRA_TRIGGER, trigger.wire).putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_TOKEN, token))
            true
        } catch (e: Exception) {
            Log.e(TAG, "loopback service could not start", e)
            false
        }
    }
}
