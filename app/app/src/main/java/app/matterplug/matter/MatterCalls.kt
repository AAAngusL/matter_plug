package app.matterplug.matter

import android.content.Context
import android.util.Log
import chip.devicecontroller.ReportCallback
import chip.devicecontroller.model.ChipAttributePath
import chip.devicecontroller.model.ChipEventPath
import chip.devicecontroller.InvokeCallback
import chip.devicecontroller.model.InvokeElement
import chip.devicecontroller.model.NodeState
import kotlinx.coroutines.suspendCancellableCoroutine
import matter.tlv.AnonymousTag
import matter.tlv.TlvWriter
import kotlin.coroutines.resume

/** The Matter calls that PlugClient (the screen) and PlugCommander (Battery Guard) both make. */

/** An empty TLV structure: the argument of a command that has no fields (On, Off). */
internal fun emptyStructure(): ByteArray {
    val writer = TlvWriter()
    writer.startStructure(AnonymousTag)
    writer.endStructure()
    return writer.getEncoded()
}

/** Sends one command and waits for its answer. Result.failure on a command or session error. */
internal suspend fun invokeCommand(
    context: Context, pointer: Long, endpoint: Int, clusterId: Long, commandId: Long, tlv: ByteArray,
): Result<Unit> {
    val element = InvokeElement.newInstance(endpoint, clusterId, commandId, tlv, null)
    val controller = ChipController.get(context)
    return suspendCancellableCoroutine { cont ->
        controller.invoke(
            object : InvokeCallback {
                override fun onError(e: Exception) {
                    if (cont.isActive) cont.resume(Result.failure(e))
                }

                override fun onResponse(invokeElement: InvokeElement?, successCode: Long) {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
            },
            pointer, element, 0, 0
        )
    }
}

/** The raw TLV of one attribute in a report, or null when the report does not hold it. */
internal fun NodeState.attributeTlv(endpoint: Int, clusterId: Long, attributeId: Long): ByteArray? =
    getEndpointState(endpoint)?.getClusterState(clusterId)?.getAttributeState(attributeId)?.tlv

/**
 * Reads one attribute and decodes its TLV with [decode]. Null on a read error, a missing attribute or a decode error.
 * No time limit of its own: callers that need one wrap it in withTimeoutOrNull.
 */
internal suspend fun <T : Any> readAttribute(
    context: Context, pointer: Long, endpoint: Int, clusterId: Long, attributeId: Long, decode: (ByteArray) -> T,
): T? {
    val controller = ChipController.get(context)
    val path = ChipAttributePath.newInstance(endpoint, clusterId, attributeId)
    return suspendCancellableCoroutine { cont ->
        controller.readPath(
            object : ReportCallback {
                override fun onError(attributePath: ChipAttributePath?, eventPath: ChipEventPath?, e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onReport(nodeState: NodeState) {
                    val value = try {
                        nodeState.attributeTlv(endpoint, clusterId, attributeId)?.let(decode)
                    } catch (e: Exception) {
                        Log.w("MatterCalls", "could not decode attribute 0x${attributeId.toString(16)}", e)
                        null
                    }
                    if (cont.isActive) cont.resume(value)
                }

                override fun onDone() {
                    if (cont.isActive) cont.resume(null)
                }
            },
            pointer, listOf(path), null, false, 0
        )
    }
}
