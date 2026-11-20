package app.matterplug.fixedip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.matterplug.R
import app.matterplug.alarm.partialWakeLock
import app.matterplug.guard.GuardStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The resident service of Fixed IP (docs/FIXED-IP.md section 8), running while its switch is on. It holds an ordinary Wi-Fi network
 * callback, so a loss or an address change is seen at once, and keeps the 1-minute wait and the boot grace as timers of its own:
 * Android allows an idle app only about one exact alarm per 9 minutes, which the existing alarm chain already uses.
 * It also reads on its own at the Check interval, best effort; the two alarm chains (Battery Guard and Cellular Check) are the
 * guaranteed backstop and start this service again if Android ended it.
 */
class IpWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<Unit>(Channel.CONFLATED)      // many network events in a row are one read
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            enterForeground()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            register()
            scope.launch { loop() }
        }
        events.trySend(Unit)      // every start reads once
        return START_STICKY
    }

    override fun onDestroy() {
        callback?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (e: Exception) { Log.w(TAG, "callback not removed", e) } }
        scope.cancel()
        super.onDestroy()
    }

    private fun register() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { events.trySend(Unit) }
            override fun onLost(network: Network) { events.trySend(Unit) }
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) { events.trySend(Unit) }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        callback = cb
    }

    /** Waits for a network event, the end of a 1-minute wait or the Check interval, then reads; stops itself once the switch is off. */
    private suspend fun loop() {
        val app = applicationContext
        var nextAt: Long? = null
        while (true) {
            val intervalMs = (try { GuardStore(app).readSettings().intervalMin } catch (e: Exception) { if (e is CancellationException) throw e; 15 }) * 60_000L
            val waitMs = nextAt?.let { (it - System.currentTimeMillis()).coerceIn(1, intervalMs) } ?: intervalMs
            // A timer does not run while the CPU sleeps: hold the CPU only for a short wait (the 1-minute wait, the boot grace).
            val lock = if (nextAt != null) partialWakeLock(app, WAKE_TAG, waitMs + 30_000L, TAG) else null
            try {
                withTimeoutOrNull(waitMs) { events.receive() }
            } finally {
                try { if (lock?.isHeld == true) lock.release() } catch (e: Exception) { Log.w(TAG, "wake lock release failed", e) }
            }
            val work = partialWakeLock(app, WAKE_TAG, 60_000L, TAG)
            try {
                nextAt = try { IpRunner.evaluate(app) } catch (e: Exception) { if (e is CancellationException) throw e; Log.e(TAG, "read failed", e); null }
            } finally {
                try { if (work?.isHeld == true) work.release() } catch (e: Exception) { Log.w(TAG, "wake lock release failed", e) }
            }
            if (!IpStore(app).readSettings().enabled) {
                withContext(NonCancellable) { stopSelf() }
                return
            }
        }
    }

    private fun enterForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.fixedip_channel_watch), NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        nm.createNotificationChannel(channel)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.fixedip_title))
            .setContentText(getString(R.string.fixedip_watching_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val TAG = "FixedIp"
        private const val CHANNEL_ID = "fixedip_watch"
        private const val NOTIFICATION_ID = 4302      // 42xx are Battery Guard alert ids, 4301 is the loopback service
        private const val WAKE_TAG = "matterplug:fixedip"

        /** False when Android refused to start it; calling it again while it runs only asks it to read once more. */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, IpWatchService::class.java))
            true
        } catch (e: Exception) {
            Log.e(TAG, "watch service could not start", e)
            false
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, IpWatchService::class.java))
        }
    }
}
