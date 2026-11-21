package app.matterplug.fixedip

import android.content.Context

/**
 * Fixed IP's watch as one thing (docs/FIXED-IP.md section 8): the foreground service that holds the Wi-Fi callback, and the periodic job that
 * restarts it. Callers turn the watch on or off; neither the service nor the job is theirs to know. Both calls are safe to repeat.
 */
object IpWatch {
    /** Starts the service and makes sure the backstop job is scheduled. False when Android refused to start the service from the background. */
    fun enable(ctx: Context): Boolean {
        IpBackstopJobService.schedule(ctx)
        return IpWatchService.start(ctx)
    }

    fun disable(ctx: Context) {
        IpWatchService.stop(ctx)
        IpBackstopJobService.cancel(ctx)
    }
}
