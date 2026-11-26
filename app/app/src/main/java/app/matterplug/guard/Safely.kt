package app.matterplug.guard

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Runs [block]; a failure is logged as "[what] failed" and gives [fallback], so one failing step never ends a check. Cancellation is
 * rethrown. For steps whose failure the check can live without; a step that must change the outcome handles its own failure.
 */
internal inline fun <T> safely(what: String, fallback: T, block: () -> T): T = try {
    block()
} catch (e: Exception) {
    if (e is CancellationException) throw e
    Log.e("Guard", "$what failed", e)
    fallback
}
