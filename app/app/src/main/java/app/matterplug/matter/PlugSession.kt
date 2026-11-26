package app.matterplug.matter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One connection to a plug: connect, run [use]'s block with the device pointer, release the pointer however the block ends (also when it
 * is cancelled). The pointer is never handed out, so a caller cannot forget to release it.
 */
internal object PlugSession {
    private const val TAG = "PlugSession"

    /**
     * When the plug cannot be reached (an error, or no answer within [connectTimeoutMs] when given), [onNotConnected] makes the result from
     * a short reason and the block does not run. A cancellation of the caller always propagates.
     */
    suspend fun <T> use(
        context: Context,
        nodeId: Long,
        connectTimeoutMs: Long? = null,
        onNotConnected: (reason: String) -> T,
        block: suspend (pointer: Long) -> T,
    ): T {
        val pointer: Long = try {
            if (connectTimeoutMs == null) ChipController.connect(context, nodeId)
            else withTimeoutOrNull(connectTimeoutMs) { ChipController.connect(context, nodeId) } ?: return onNotConnected("timeout connecting")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            return onNotConnected("connect failed: ${e.message ?: e.javaClass.simpleName}")
        }
        try {
            return block(pointer)
        } finally {
            ChipController.release(pointer)
        }
    }
}
