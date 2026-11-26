package app.matterplug.guard

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * When the app checks: the one interval every check uses, and, while Battery Guard is on, the time of its next check. The features ask here
 * and know nothing of where Battery Guard keeps it.
 */
object CheckSchedule {
    /** Used when the settings cannot be read. */
    const val DEFAULT_INTERVAL_MIN = 15

    /**
     * [guardBringsNextRun]: Battery Guard is on, so its alarm brings every check and [guardNextCheckAt] (null if none is stored) is the next run
     * of every feature too; otherwise a feature keeps its own alarm. [guardCarriesHeartbeat]: Battery Guard's check carries the console
     * heartbeat, which is also assumed when its settings cannot be read, so the heartbeat is not counted twice.
     */
    data class Now(val intervalMin: Int, val guardBringsNextRun: Boolean, val guardNextCheckAt: Long?, val guardCarriesHeartbeat: Boolean)

    /** Never throws except for cancellation. */
    suspend fun now(ctx: Context): Now {
        val store = GuardStore(ctx)
        val settings = try { store.readSettings() } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Now(DEFAULT_INTERVAL_MIN, guardBringsNextRun = false, guardNextCheckAt = null, guardCarriesHeartbeat = true)
        }
        val next = if (!settings.enabled) null else try { store.status.first().nextCheckAt } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        return Now(settings.intervalMin, settings.enabled, next, guardCarriesHeartbeat = settings.enabled)
    }

    /** Whether Battery Guard's "send status" switch is on, for a screen that depends on it (the heartbeat learns from the status push). */
    fun statusReportOnFlow(ctx: Context): Flow<Boolean> = GuardStore(ctx).settings.map { it.statusEnabled }

    /** The interval, for a screen that shows it. */
    fun intervalMinFlow(ctx: Context): Flow<Int> = GuardStore(ctx).settings.map { it.intervalMin }
}
