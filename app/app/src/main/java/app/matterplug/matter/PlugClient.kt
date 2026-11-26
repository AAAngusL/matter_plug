package app.matterplug.matter

import android.content.Context
import android.util.Log
import app.matterplug.data.Plug
import chip.devicecontroller.ReportCallback
import chip.devicecontroller.model.ChipAttributePath
import chip.devicecontroller.model.ChipEventPath
import chip.devicecontroller.model.NodeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import matter.tlv.AnonymousTag
import matter.tlv.ContextSpecificTag
import matter.tlv.TlvReader
import matter.tlv.TlvWriter

sealed interface PlugState {
    data object Connecting : PlugState
    data class Online(val isOn: Boolean) : PlugState
    data object Offline : PlugState
}

/** One instance per plug while its screen is visible: connect, subscribe, send On or Off, drop the fabric. */
class PlugClient(context: Context, private val plug: Plug) {
    private val context: Context = context.applicationContext

    private val _state = MutableStateFlow<PlugState>(PlugState.Connecting)
    val state: StateFlow<PlugState> = _state.asStateFlow()

    @Volatile
    private var devicePointer = 0L

    /** Bumped by every start() and stop(); a start() that finds it changed was stopped while connecting. */
    @Volatile
    private var generation = 0

    // Callbacks arrive on the library's own thread; they only write to the StateFlow.
    private val reportCallback = object : ReportCallback {
        override fun onError(attributePath: ChipAttributePath?, eventPath: ChipEventPath?, e: Exception) {
            Log.w(TAG, "subscription report error", e)
            _state.value = PlugState.Offline
        }

        override fun onReport(nodeState: NodeState) {
            // VERIFY: accessor chain NodeState.getEndpointState(int) -> EndpointState.getClusterState(long) -> ClusterState.getAttributeState(long) -> AttributeState.getTlv() (settled for NodeState only).
            val tlv = nodeState.attributeTlv(plug.endpoint, ON_OFF_CLUSTER, ON_OFF_ATTRIBUTE) ?: return
            try {
                // VERIFY: matter.tlv.TlvReader(ByteArray).getBool(Tag) and AnonymousTag names in libMatterTlv.jar.
                val isOn = TlvReader(tlv).getBool(AnonymousTag)
                _state.value = PlugState.Online(isOn)
            } catch (e: Exception) {
                Log.w(TAG, "could not decode OnOff report", e)
            }
        }
    }

    /** Connects and subscribes. The first report moves the state out of Connecting. */
    suspend fun start() {
        _state.value = PlugState.Connecting
        val myGeneration = ++generation
        val pointer = try {
            ChipController.connect(context, plug.nodeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            _state.value = PlugState.Offline
            return
        }
        if (myGeneration != generation) {      // stop() ran while connecting: do not keep the session or subscribe
            ChipController.release(pointer)
            return
        }
        devicePointer = pointer
        try {
            // Settled: newInstance(int endpointId, long clusterId, long attributeId); the endpoint must be an Int.
            val path = ChipAttributePath.newInstance(plug.endpoint, ON_OFF_CLUSTER, ON_OFF_ATTRIBUTE)
            ChipController.get(context).subscribeToPath(
                { _ -> Log.d(TAG, "subscription established") },
                { _, _ -> _state.value = PlugState.Offline },   // resubscription attempt: the plug stopped answering
                reportCallback,
                pointer,
                listOf(path),
                null,
                0,      // minimum interval, seconds
                30,     // maximum interval, seconds
                false,  // keepSubscriptions
                false,  // isFabricFiltered
                0       // imTimeoutMs
            )
        } catch (e: Exception) {
            Log.w(TAG, "subscribe failed", e)
            _state.value = PlugState.Offline
        }
    }

    /** Sends On or Off (never Toggle). Does not change [state]; only reports do. */
    suspend fun set(on: Boolean): Result<Unit> {
        val pointer = devicePointer
        if (pointer == 0L) return Result.failure(IllegalStateException("not connected"))
        return try {
            sendCommand(pointer, ON_OFF_CLUSTER, if (on) CMD_ON else CMD_OFF, emptyStructure())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Drops the subscription and releases the session. Called when the screen stops. */
    fun stop() {
        generation++
        val pointer = devicePointer
        devicePointer = 0L
        if (pointer == 0L) return
        try {
            // Settled: shutdownSubscriptions() with no arguments exists at v1.6.1.0. VERIFY: it shuts down all subscriptions of this controller (that is all we have).
            ChipController.get(context).shutdownSubscriptions()
        } catch (e: Exception) {
            Log.w(TAG, "shutdownSubscriptions failed", e)
        }
        ChipController.release(pointer)
    }

    /**
     * Asks the plug to remove this app's fabric (A7). Connects by itself if there is no session yet.
     * A session error after RemoveFabric was sent counts as success, because the plug closes the session.
     */
    suspend fun leaveFabric(): Result<Unit> {
        if (devicePointer != 0L) return removeFabric(devicePointer)
        return PlugSession.use(context, plug.nodeId, onNotConnected = { Result.failure(IllegalStateException(it)) }) { removeFabric(it) }
    }

    private suspend fun removeFabric(pointer: Long): Result<Unit> {
        try {
            val fabricIndex = readFabricIndex(pointer)
                ?: return Result.failure(IllegalStateException("could not read CurrentFabricIndex"))
            // VERIFY: Operational Credentials IDs against ClusterIDMapping.OperationalCredentials: cluster 0x003E, command RemoveFabric 0x0A with field 0 = FabricIndex.
            // VERIFY: FabricIndex is uint8; TlvWriter.put(Tag, UInt) must encode an unsigned integer the plug accepts (else try UByte or ULong).
            val writer = TlvWriter()
            writer.startStructure(AnonymousTag)
            writer.put(ContextSpecificTag(0), fabricIndex.toUInt())
            writer.endStructure()
            val result = sendCommand(pointer, OPERATIONAL_CREDENTIALS_CLUSTER, CMD_REMOVE_FABRIC, writer.getEncoded())
            if (result.isFailure) {
                Log.i(TAG, "RemoveFabric ended with an error; treated as success because the plug closes the session", result.exceptionOrNull())
            }
            return Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private suspend fun sendCommand(pointer: Long, clusterId: Long, commandId: Long, tlv: ByteArray): Result<Unit> {
        val endpoint = if (clusterId == OPERATIONAL_CREDENTIALS_CLUSTER) 0 else plug.endpoint
        return invokeCommand(context, pointer, endpoint, clusterId, commandId, tlv)
    }

    private suspend fun readFabricIndex(pointer: Long): Int? = withTimeoutOrNull(READ_TIMEOUT_MS) {
        // VERIFY: TlvReader.getULong(Tag) reads a uint8 value (else use getUByte / getUInt).
        readAttribute(context, pointer, 0, OPERATIONAL_CREDENTIALS_CLUSTER, ATTR_CURRENT_FABRIC_INDEX) {
            TlvReader(it).getULong(AnonymousTag).toInt()
        }
    }

    private companion object {
        const val TAG = "PlugClient"
        const val READ_TIMEOUT_MS = 10_000L
        const val ON_OFF_CLUSTER = 0x0006L
        const val ON_OFF_ATTRIBUTE = 0x0000L
        const val CMD_OFF = 0x00L
        const val CMD_ON = 0x01L
        const val OPERATIONAL_CREDENTIALS_CLUSTER = 0x003EL
        const val ATTR_CURRENT_FABRIC_INDEX = 0x0005L
        const val CMD_REMOVE_FABRIC = 0x0AL
    }
}
