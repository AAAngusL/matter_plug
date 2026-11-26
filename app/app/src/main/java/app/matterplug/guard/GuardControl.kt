package app.matterplug.guard

import app.matterplug.alerts.AlertChannel
import android.content.Context
import android.util.Log
import app.matterplug.alerts.AlertNotifier
import app.matterplug.alerts.AlertOutbox
import app.matterplug.alerts.FlushJobService

/** Switches Battery Guard off (its part only; [app.matterplug.checks.GuardSwitch.off] is what the app calls). */
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
    }

    /** After a switch closed alerts: remove their notifications, and start the flush job if anything waits. */
    suspend fun afterSwitch(ctx: Context, store: GuardStore, cleared: List<app.matterplug.alerts.logic.AlertKind>) {
        AlertNotifier.cancel(ctx, cleared)
        AlertChannel.flushIfWaiting(ctx)
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.e("Guard", "$what failed", e) }
    }
}
