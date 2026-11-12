package app.matterplug.matter

import android.content.Context
import android.util.Log
import app.matterplug.data.Plug
import chip.devicecontroller.InvokeCallback
import chip.devicecontroller.ReportCallback
import chip.devicecontroller.model.ChipAttributePath
import chip.devicecontroller.model.ChipEventPath
import chip.devicecontroller.model.InvokeElement
import chip.devicecontroller.model.NodeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import matter.tlv.AnonymousTag
import matter.tlv.TlvReader
import matter.tlv.TlvWriter
import kotlin.coroutines.resume

sealed interface SwitchResult {
    data object Confirmed : SwitchResult
    data class NotReached(val reason: String) : SwitchResult      // connect or command failed or timed out
    data object Unconfirmed : SwitchResult                        // command accepted, read-back differs or failed
}

/**
 * One switch, then release everything (BATTERY-GUARD-IMPLEMENTATION.md 5.4, B9). Connect, send On or Off (never Toggle),
 * read OnOff back, release the pointer. It does not subscribe and is not tied to a screen. The few helpers it needs are
 * repeated from PlugClient on purpose, so the working pairing and toggle code stays untouched.
 *
 * Timeouts use withTimeoutOrNull: a step that runs out of time gives NotReached("timeout ..."), while a cancellation of
 * the caller (for example the service's own 120 s limit) is never caught here and propagates.
 */
class PlugCommander(context: Context) {
    private val context: Context = context.applicationContext

    suspend fun switchTo(plug: Plug, on: Boolean): SwitchResult {
        var pointer = 0L
        try {
            // 1. Connect.
            val connectedOrNull: Long? = try {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ChipController.connect(context, plug.nodeId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                return SwitchResult.NotReached("connect failed: ${e.message ?: e.javaClass.simpleName}")
            }
            if (connectedOrNull == null) return SwitchResult.NotReached("timeout connecting")
            pointer = connectedOrNull

            // 2. Send On or Off.
            val sentOrNull: Result<Unit>? = try {
                withTimeoutOrNull(INVOKE_TIMEOUT_MS) { sendOnOff(plug, pointer, on) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "command failed", e)
                return SwitchResult.NotReached("command failed: ${e.message ?: e.javaClass.simpleName}")
            }
            if (sentOrNull == null) return SwitchResult.NotReached("timeout sending command")
            val sent: Result<Unit> = sentOrNull
            if (sent.isFailure) {
                val e = sent.exceptionOrNull()
                Log.w(TAG, "command failed", e)
                return SwitchResult.NotReached("command failed: ${e?.message ?: e?.javaClass?.simpleName ?: "unknown"}")
            }

            // 3. Read OnOff back and compare. A failed or timed-out read is Unconfirmed, not NotReached: the command was accepted.
            val actual: Boolean? = try {
                withTimeoutOrNull(READ_TIMEOUT_MS) { readOnOff(plug, pointer) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "read-back failed", e)
                null
            }
            Log.i(TAG, "requested=${if (on) "on" else "off"} readBack=${actual ?: "unknown"}")
            return if (actual == on) SwitchResult.Confirmed else SwitchResult.Unconfirmed
        } finally {
            // 4. Release every pointer obtained.
            if (pointer != 0L) ChipController.release(pointer)
        }
    }

    /**
     * G7: reads the plug's OnOff state alone (connect, read, release), for the "turn it on before disabling?" question.
     * One 8-second limit covers connect and read. Null on any failure or timeout. Does not touch [switchTo].
     * A cancellation of the caller propagates. A pointer that arrives after the timeout is lost, as in ChipController.connect (V3 note in G4).
     */
    suspend fun readOnOff(plug: Plug): Boolean? {
        var pointer = 0L
        try {
            return withTimeoutOrNull(READ_ONLY_TIMEOUT_MS) {
                pointer = ChipController.connect(context, plug.nodeId)
                readOnOff(plug, pointer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "readOnOff failed", e)
            return null
        } finally {
            if (pointer != 0L) ChipController.release(pointer)
        }
    }

    private fun emptyStructure(): ByteArray {
        val writer = TlvWriter()
        writer.startStructure(AnonymousTag)
        writer.endStructure()
        return writer.getEncoded()
    }

    /** Same calls as PlugClient.sendCommand, for the On/Off cluster only. Result.failure on a command error. */
    private suspend fun sendOnOff(plug: Plug, pointer: Long, on: Boolean): Result<Unit> {
        val element = InvokeElement.newInstance(plug.endpoint, ON_OFF_CLUSTER, if (on) CMD_ON else CMD_OFF, emptyStructure(), null)
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

    /** Reads OnOff (0x0006 / 0x0000) like PlugClient.readFabricIndex reads its attribute; null on any failure. */
    private suspend fun readOnOff(plug: Plug, pointer: Long): Boolean? {
        val controller = ChipController.get(context)
        val path = ChipAttributePath.newInstance(plug.endpoint, ON_OFF_CLUSTER, ON_OFF_ATTRIBUTE)
        return suspendCancellableCoroutine { cont ->
            controller.readPath(
                object : ReportCallback {
                    override fun onError(attributePath: ChipAttributePath?, eventPath: ChipEventPath?, e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onReport(nodeState: NodeState) {
                        val tlv = nodeState.getEndpointState(plug.endpoint)
                            ?.getClusterState(ON_OFF_CLUSTER)
                            ?.getAttributeState(ON_OFF_ATTRIBUTE)
                            ?.tlv
                        val value = try {
                            if (tlv == null) null else TlvReader(tlv).getBool(AnonymousTag)
                        } catch (e: Exception) {
                            Log.w(TAG, "could not decode OnOff", e)
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

    private companion object {
        const val TAG = "PlugCommander"
        const val CONNECT_TIMEOUT_MS = 25_000L
        const val INVOKE_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 10_000L
        const val READ_ONLY_TIMEOUT_MS = 8_000L
        const val ON_OFF_CLUSTER = 0x0006L
        const val ON_OFF_ATTRIBUTE = 0x0000L
        const val CMD_OFF = 0x00L
        const val CMD_ON = 0x01L
    }
}
