package app.matterplug.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import app.matterplug.battery.BatteryMath
import app.matterplug.battery.BatteryStatus
import app.matterplug.battery.Power

data class BatteryReading(val level: Int, val power: Power) {
    val pluggedIn: Boolean get() = power.pluggedIn
}

object BatteryReader {
    /**
     * Null when the system gives no usable battery state. Passing null as the receiver returns the sticky
     * ACTION_BATTERY_CHANGED value without registering anything, so there is nothing to unregister.
     */
    fun read(context: Context): BatteryReading? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val pct = BatteryMath.percent(
            i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            i.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        ) ?: return null
        val power = Power(
            BatteryMath.isPluggedIn(i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)),
            BatteryStatus.fromAndroid(i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)),
        )
        return BatteryReading(pct, power)
    }

    /** [read], with a failure logged and counted as "no reading". The one place that decides an unreadable battery is not an error. */
    fun readOrNull(context: Context): BatteryReading? = try {
        read(context)
    } catch (e: Exception) {
        Log.w("Guard", "battery read failed", e)
        null
    }
}
