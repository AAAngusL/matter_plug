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
     * [guardOn] is null when Battery Guard's settings could not be read. [guardNextCheckAt] is the time of its next check: set only while it is
     * on and has one stored; while it is on, its alarm brings every check, so that is the next run of every feature too.
     */
    data class Now(val intervalMin: Int, val guardOn: Boolean?, val guardNextCheckAt: Long?)

    /** Never throws except for cancellation. */
    suspend fun now(ctx: Context): Now {
        val store = GuardStore(ctx)
        val settings = try { store.readSettings() } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Now(DEFAULT_INTERVAL_MIN, null, null)
        }
        val next = if (!settings.enabled) null else try { store.status.first().nextCheckAt } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        return Now(settings.intervalMin, settings.enabled, next)
    }

    /** The interval, for a screen that shows it. */
    fun intervalMinFlow(ctx: Context): Flow<Int> = GuardStore(ctx).settings.map { it.intervalMin }
}
