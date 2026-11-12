package app.matterplug.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.matterplug.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that does the one switch (5.5). Started only from the alarm's receiver path (B3) and only when the
 * decision is TurnOn or TurnOff. It runs GuardRunner.work, then stops itself. It decides nothing.
 */
class ActionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext
        // startForegroundService promises a startForeground call within seconds, so do it first, even for a start we ignore.
        try {
            enterForeground()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            scope.launch {
                try { GuardStore(app).recordRunError(ERR_NOT_STARTED) } catch (e2: Exception) {
                    if (e2 is CancellationException) throw e2
                    Log.e(TAG, "recordRunError failed", e2)
                }
                running.set(false)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "start ignored: a run is going")
            return START_NOT_STICKY
        }

        scope.launch {
            val wakeLock = acquireWakeLock()
            try {
                withTimeout(WORK_TIMEOUT_MS) { GuardRunner.work(app) }
            } catch (e: TimeoutCancellationException) {
                // Only our own withTimeout throws this here; a cancelled scope throws a plain CancellationException.
                Log.w(TAG, "work timed out after ${WORK_TIMEOUT_MS / 1000} s")
                try { GuardStore(app).recordRunError("switching timed out") } catch (e2: Exception) {
                    if (e2 is CancellationException) throw e2
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "work failed", e)
                try { GuardStore(app).recordRunError("check failed") } catch (e2: Exception) {
                    if (e2 is CancellationException) throw e2
                }
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (e: Exception) { Log.w(TAG, "wake lock release failed", e) }
                running.set(false)
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
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.guard_channel_work), NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        nm.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.guard_switching_title))
            .setContentText(getString(R.string.guard_switching_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /** Partial wake lock only while the work runs; the 130 s timeout is a backstop (rule 2 of BATTERY-GUARD.md section 5). */
    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).also {
            it.setReferenceCounted(false)
            it.acquire(WAKE_TIMEOUT_MS)
        }
    } catch (e: Exception) {
        Log.w(TAG, "wake lock not acquired", e)
        null
    }

    companion object {
        private const val TAG = "Guard"
        private const val CHANNEL_ID = "guard_work"
        private const val NOTIFICATION_ID = 4101
        private const val WAKE_TAG = "matterplug:guard"
        private const val WAKE_TIMEOUT_MS = 130_000L
        private const val WORK_TIMEOUT_MS = 120_000L
        private const val ERR_NOT_STARTED = "service could not start"

        private val running = AtomicBoolean(false)

        /**
         * Starts the service. False (and recordRunError) when Android refuses. Suspend because recordRunError is;
         * the callers are suspend functions already.
         */
        suspend fun start(ctx: Context): Boolean {
            val app = ctx.applicationContext
            try {
                ContextCompat.startForegroundService(app, Intent(app, ActionService::class.java))
                return true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) is a subclass of IllegalStateException.
                val notAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException
                Log.w(TAG, "service could not start" + if (notAllowed) " (foreground start not allowed)" else "", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "service could not start (security)", e)
            }
            try {
                GuardStore(app).recordRunError(ERR_NOT_STARTED)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "recordRunError failed", e)
            }
            return false
        }
    }
}
