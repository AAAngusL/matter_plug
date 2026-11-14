package app.matterplug.guard

import android.content.Context
import android.util.Log

/** Switches Battery Guard off. One place for it, used by the Guard switch and by removing the charger plug. */
object GuardControl {
    /** The store clears the waiting list, the alert state and the next time (5.2); the alarm and the flush job are cancelled here. */
    suspend fun disable(ctx: Context) {
        GuardStore(ctx).update { it.copy(enabled = false) }
        guarded("cancel") { CheckScheduler.cancel(ctx) }
        // The store has cleared the waiting list (5.2): the flush job would find nothing to do.
        guarded("cancel flush job") { FlushJobService.cancel(ctx) }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.e("Guard", "$what failed", e) }
    }
}
