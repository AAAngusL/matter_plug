package app.matterplug.matter

import android.content.Context
import android.util.Log
import chip.devicecontroller.CommissionParameters
import chip.devicecontroller.NetworkCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull

enum class PairingStage { FindingPlug, Connecting, SecuringSession, Commissioning, JoiningWifi, FindingOnNetwork, Finishing }

sealed interface PairingEvent {
    data class Progress(val stage: PairingStage) : PairingEvent
    data class Success(val nodeId: Long) : PairingEvent
    data class Failure(val stage: PairingStage, val errorCode: Long?) : PairingEvent
    /** No plug matched the code, but a Matter device in pairing mode was nearby: the code is for another plug or window. */
    data object CodeMismatch : PairingEvent
}

/** How a pairing attempt reaches the plug. */
sealed interface PairingRoute {
    /** A new or reset plug: found over Bluetooth, then told to join this Wi-Fi network. */
    data class Bluetooth(val ssid: String, val password: String) : PairingRoute
    /** A plug already on Wi-Fi with its pairing window open (shared from another Matter app). */
    data class Network(val plug: NetworkPlug) : PairingRoute
}

/** Runs one pairing attempt (Matter commissioning over Bluetooth then Wi-Fi, or over the network) and reports stages. */
class Commissioner(context: Context) {
    private val context: Context = context.applicationContext

    /**
     * Looks on the Wi-Fi network, for up to [timeoutMs], for a plug waiting to be paired with [setupCode].
     * Null when none is found (the plug then has to be paired over Bluetooth).
     */
    suspend fun findOnNetwork(setupCode: SetupCode, timeoutMs: Long = NETWORK_LOOK_MS): NetworkPlug? {
        val controller = ChipController.get(context)
        try {
            controller.discoverCommissionableNodes()
        } catch (e: Exception) {
            Log.w(TAG, "network discovery could not start", e)
            return null
        }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            delay(NETWORK_POLL_MS)
            val found = (0 until MAX_DISCOVERED).mapNotNull { i ->
                try { controller.getDiscoveredDevice(i) } catch (e: Exception) { null }
            }.map { NetworkPlug(it.ipAddress ?: "", it.port, it.discriminator.toInt()) }
            pickNetworkPlug(setupCode, found)?.let {
                Log.i(TAG, "found on the network: discriminator=${it.discriminator} at ${it.ipAddress}:${it.port}")
                return it
            }
        }
        Log.i(TAG, "not found on the network in $timeoutMs ms")
        return null
    }

    fun pair(setupCode: SetupCode, route: PairingRoute, nodeId: Long): Flow<PairingEvent> = callbackFlow {
        val run = PairingRun(ChipController.get(context)) { trySend(it) }
        val controller = ChipController.get(context)

        trySend(PairingEvent.Progress(PairingStage.FindingPlug))
        try {
            val outcome = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                when (route) {
                    is PairingRoute.Network -> {
                        // Already on Wi-Fi: no Bluetooth and no network credentials.
                        run.advance(PairingStage.Connecting)
                        run.prepareController()
                        val params = CommissionParameters.Builder().setCsrNonce(null).setICDRegistrationInfo(null).build()
                        val plug = route.plug
                        controller.pairDeviceWithAddress(nodeId, plug.ipAddress, plug.port, plug.discriminator, setupCode.passcode, params)
                    }
                    is PairingRoute.Bluetooth -> {
                        val ble = BleTransport()
                        val device = ble.getBluetoothDevice(context, setupCode.discriminator, setupCode.isShortDiscriminator)
                        if (device == null) {
                            val nearby = try { ble.matterDiscriminatorsNearby() } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.w(TAG, "second scan failed", e)
                                emptySet()
                            }
                            if (nearby.isNotEmpty()) run.codeMismatch() else run.fail(PairingStage.FindingPlug, null)
                            return@withTimeoutOrNull run.outcome()
                        }

                        run.advance(PairingStage.Connecting)
                        val gatt = ble.connect(context, device)
                        if (gatt == null) {
                            run.fail(PairingStage.Connecting, null)
                            return@withTimeoutOrNull run.outcome()
                        }
                        val connectionId = ble.connectionId
                        run.prepareController()
                        val params = CommissionParameters.Builder()
                            .setCsrNonce(null)
                            .setNetworkCredentials(
                                NetworkCredentials.forWiFi(NetworkCredentials.WiFiCredentials(route.ssid, route.password))
                            )
                            .setICDRegistrationInfo(null)
                            .build()
                        controller.pairDeviceThroughBLE(gatt, connectionId, nodeId, setupCode.passcode, params)
                    }
                }
                run.outcome()
            }
            trySend(outcome ?: PairingEvent.Failure(run.lastStage, null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "pairing attempt crashed", e)
            trySend(PairingEvent.Failure(run.lastStage, null))
        } finally {
            run.cleanup()
        }
        close()
        awaitClose { run.cleanup() }
    }

    private companion object {
        const val TAG = "Commissioner"
        const val ATTEMPT_TIMEOUT_MS = 120_000L
        const val NETWORK_LOOK_MS = 6_000L
        const val NETWORK_POLL_MS = 500L
        const val MAX_DISCOVERED = 10
    }
}
