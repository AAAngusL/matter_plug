package app.matterplug.alarm

import android.content.Context
import android.os.PowerManager
import android.util.Log

/** A partial wake lock held at most [timeoutMs], or null when Android refuses one. Used by both foreground services. */
fun partialWakeLock(ctx: Context, name: String, timeoutMs: Long, logTag: String): PowerManager.WakeLock? = try {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, name).also {
        it.setReferenceCounted(false)
        it.acquire(timeoutMs)
    }
} catch (e: Exception) {
    Log.w(logTag, "wake lock not acquired", e)
    null
}
