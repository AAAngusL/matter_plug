package app.matterplug.matter

import android.content.Context
import android.util.Log
import chip.devicecontroller.ReportCallback
import chip.devicecontroller.model.ChipAttributePath
import chip.devicecontroller.model.ChipEventPath
import chip.devicecontroller.model.ChipPathId
import chip.devicecontroller.model.NodeState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Finds which endpoint of a freshly paired node has the On/Off cluster (A2: never assume endpoint 1). */
object EndpointFinder {
    private const val TAG = "EndpointFinder"
    private const val FALLBACK_ENDPOINT = 1
    private const val READ_TIMEOUT_MS = 10_000L

    private const val ON_OFF_CLUSTER = 0x0006L
    private const val ON_OFF_ATTRIBUTE = 0x0000L

    /**
     * Returns the lowest endpoint that has the OnOff attribute, or null if the node reports none.
     * Throws if the node cannot be reached. If the wildcard read itself fails, falls back to endpoint 1.
     */
    suspend fun find(context: Context, nodeId: Long): Int? {
        return PlugSession.use(context, nodeId, onNotConnected = { throw IllegalStateException("Unable to reach node $nodeId: $it") }) { pointer ->
            val controller = ChipController.get(context)
            // Settled: ChipAttributePath.newInstance(ChipPathId, ChipPathId, ChipPathId) exists at v1.6.1.0.
            // VERIFY: ChipPathId.forWildcard() and ChipPathId.forId(long) factory names in model/ChipPathId.java.
            val path = ChipAttributePath.newInstance(
                ChipPathId.forWildcard(),
                ChipPathId.forId(ON_OFF_CLUSTER),
                ChipPathId.forId(ON_OFF_ATTRIBUTE)
            )
            val found: Result<Int?>? = withTimeoutOrNull(READ_TIMEOUT_MS) {
                suspendCancellableCoroutine<Result<Int?>> { cont ->
                    controller.readPath(
                        object : ReportCallback {
                            override fun onError(attributePath: ChipAttributePath?, eventPath: ChipEventPath?, e: Exception) {
                                Log.w(TAG, "wildcard read failed", e)
                                if (cont.isActive) cont.resume(Result.failure(e))
                            }

                            override fun onReport(nodeState: NodeState) {
                                // Settled: NodeState.getEndpointStates(): Map<Integer, EndpointState>.
                                val lowest = nodeState.endpointStates.keys.minOrNull()
                                if (cont.isActive) cont.resume(Result.success(lowest))
                            }

                            override fun onDone() {
                                if (cont.isActive) cont.resume(Result.success(null))
                            }
                        },
                        pointer, listOf(path), null, false, 0
                    )
                }
            }
            if (found == null || found.isFailure) {
                Log.w(TAG, "Wildcard read did not work; falling back to endpoint $FALLBACK_ENDPOINT")
                FALLBACK_ENDPOINT
            } else {
                found.getOrNull()
            }
        }
    }
}
