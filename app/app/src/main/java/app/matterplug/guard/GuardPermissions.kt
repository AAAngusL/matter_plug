package app.matterplug.guard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** What Battery Guard needs from Android besides its manifest permissions (BATTERY-GUARD.md section 9, 5.9). */
object GuardPermissions {
    /** True when notifications may be posted: below Android 13 there is no runtime permission, so always true. */
    fun notificationsGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** True when the app is exempt from battery optimisation. */
    fun ignoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * The system dialog "Allow app to always run in the background?". Needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * (declared in the manifest). Starting it may throw ActivityNotFoundException on a phone without that dialog.
     */
    fun ignoreBatteryOptimizationsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))

    fun canScheduleExact(ctx: Context): Boolean = CheckScheduler.canScheduleExact(ctx)
}
