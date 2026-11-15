package app.matterplug.guard

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.matterplug.R
import app.matterplug.guard.logic.AlertEvent
import app.matterplug.guard.logic.AlertKind
import app.matterplug.guard.logic.AlertMessages
import app.matterplug.guard.logic.AlertPhase
import app.matterplug.guard.logic.CheckDecision

/**
 * Posts one notification per alert event (BATTERY-GUARD-IMPLEMENTATION.md 5.7), whether or not "Send alerts" is on.
 * The notification id follows the alert kind, so a reminder replaces the earlier notification and a Recovered event
 * replaces it with the recovery text. The channel `guard_work` of the switching service is created by ActionService.
 * Never throws.
 */
object GuardNotifier {
    private const val TAG = "Guard"
    const val CHANNEL_ALERTS = "guard_alerts"

    @SuppressLint("MissingPermission")      // checked below with ContextCompat.checkSelfPermission
    fun post(ctx: Context, events: List<AlertEvent>) {
        if (events.isEmpty()) return
        try {
            val app = ctx.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "notifications not posted: permission not granted")
                return
            }
            ensureChannel(app)
            val nm = NotificationManagerCompat.from(app)
            for (e in events) {
                val text = AlertMessages.text(e)
                val n = NotificationCompat.Builder(app, CHANNEL_ALERTS)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(app.getString(titleRes(e)))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setOnlyAlertOnce(false)
                    .setAutoCancel(true)
                    .build()
                nm.notify(CheckDecision.notificationId(e.kind), n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "notification failed", e)
        }
    }

    private fun ensureChannel(app: Context) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, app.getString(R.string.guard_channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun titleRes(e: AlertEvent): Int = when {
        e.kind == AlertKind.CellularDown && e.phase == AlertPhase.Recovered -> R.string.cellular_alert_title_recovered
        e.kind == AlertKind.CellularDown -> R.string.cellular_alert_title_down
        e.phase == AlertPhase.Recovered -> R.string.guard_alert_title_recovered
        e.kind == AlertKind.NotCharging -> R.string.guard_alert_title_not_charging
        e.kind == AlertKind.PlugUnreachable -> R.string.guard_alert_title_unreachable
        e.kind == AlertKind.GuardDegraded -> R.string.guard_alert_title_degraded
        else -> R.string.guard_title
    }
}
