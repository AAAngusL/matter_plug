package app.matterplug.guard

import android.content.Context
import android.util.Log
import app.matterplug.cellular.CellularRunner
import app.matterplug.cellular.CellularStore

/** Switches Battery Guard off. One place for it, used by the Guard switch and by removing the charger plug. */
object GuardControl {
    /**
     * The store queues cancelled for the open alerts and clears the alert state and the next time (RECOVERY-SIGNALS.md
     * 2.4); the alarm is cancelled here, and the flush job keeps sending what waits, since no check will.
     */
    suspend fun disable(ctx: Context) {
        val store = GuardStore(ctx)
        val cleared = store.update { it.copy(enabled = false) }
        guarded("cancel") { CheckScheduler.cancel(ctx) }
        afterSwitch(ctx, store, cleared)
        // Battery Guard's alarm also brought Cellular Check's runs; its own alarm takes over now.
        try {
            if (CellularStore(ctx).readSettings().enabled) CellularRunner.startSoon(ctx)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("Guard", "cellular alarm not restarted", e)
        }
    }

    /** After a switch closed alerts: remove their notifications, and start the flush job if anything waits. */
    suspend fun afterSwitch(ctx: Context, store: GuardStore, cleared: List<app.matterplug.guard.logic.AlertKind>) {
        GuardNotifier.cancel(ctx, cleared)
        try {
            if (store.readQueue().isNotEmpty()) FlushJobService.schedule(ctx)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("Guard", "flush job not scheduled", e)
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.e("Guard", "$what failed", e) }
    }
}
