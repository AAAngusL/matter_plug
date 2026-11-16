package app.matterplug.matter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import chip.devicecontroller.CommissionParameters
import chip.devicecontroller.NetworkCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
        val controller = ChipController.get(context)
        val lastStage = AtomicReference(PairingStage.FindingPlug)
        val terminal = CompletableDeferred<PairingEvent>()
        val cleanedUp = AtomicBoolean(false)
        // Stage and code of the first failed step, kept until the library reports that commissioning has ended.
        val failedStep = AtomicReference<Pair<PairingStage, Long>?>(null)

        // Moves forward only; later duplicate or earlier stage reports are ignored.
        fun advance(stage: PairingStage) {
            if (stage.ordinal > lastStage.get().ordinal) {
                lastStage.set(stage)
                trySend(PairingEvent.Progress(stage))
            }
        }

        fun fail(stage: PairingStage, errorCode: Long?) {
            terminal.complete(PairingEvent.Failure(stage, errorCode))
        }

        // Ends the Bluetooth connection. Safe to call more than once.
        // The controller itself is kept: the library allows only one controller per process, and creating a second
        // one aborts the app. The attestation delegate is also left installed, because replacing it while the
        // library is still commissioning crashes inside the library.
        fun cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) return
            try {
                controller.close()
            } catch (e: Exception) {
                Log.w(TAG, "controller.close failed", e)
            }
        }

        val listener = object : GenericChipDeviceListener() {
            override fun onPairingComplete(errorCode: Long) {
                if (errorCode == 0L) advance(PairingStage.Commissioning) else fail(PairingStage.SecuringSession, errorCode)
            }

            override fun onCommissioningStageStart(nodeId: Long, stage: String) {
                Log.d(TAG, "stage start: $stage")
                mapStage(stage)?.let { advance(it) }
            }

            override fun onCommissioningStatusUpdate(nodeId: Long, stage: String, errorCode: Long) {
                Log.d(TAG, "stage status: $stage error=$errorCode")
                val mapped = mapStage(stage)
                if (errorCode != 0L) {
                    // A failed step does not end commissioning: the library either recovers (attestation, which the
                    // delegate below always accepts) or reports the end through onCommissioningComplete. Ending the
                    // attempt here would tear things down while the library is still working.
                    if (!stage.contains("Attestation", ignoreCase = true)) {
                        failedStep.compareAndSet(null, (mapped ?: lastStage.get()) to errorCode)
                    }
                } else if (mapped != null) {
                    advance(mapped)
                }
            }

            override fun onCommissioningComplete(nodeId: Long, errorCode: Long) {
                if (errorCode == 0L) {
                    advance(PairingStage.Finishing)
                    terminal.complete(PairingEvent.Success(nodeId))
                } else {
                    val step = failedStep.get()
                    fail(step?.first ?: lastStage.get(), step?.second ?: errorCode)
                }
            }

            override fun onError(error: Throwable?) {
                Log.e(TAG, "commissioning error", error)
                fail(lastStage.get(), null)
            }
        }

        // Shared by both routes, set just before commissioning starts.
        fun prepareController() {
            controller.setCompletionListener(listener)
            // Device verification is skipped: always continue commissioning.
            // The library calls this on its own thread while it holds its internal lock. Calling
            // continueCommissioning from inside the callback stalls commissioning, so it is posted to the
            // main thread, as CHIPTool does.
            controller.setDeviceAttestationDelegate(ATTESTATION_FAILSAFE_SECONDS) { devicePointer, _, errorCode ->
                Log.d(TAG, "attestation result: error=$errorCode; continuing")
                Handler(Looper.getMainLooper()).post {
                    try {
                        controller.continueCommissioning(devicePointer, true)
                        Log.d(TAG, "continueCommissioning sent")
                    } catch (e: Exception) {
                        Log.e(TAG, "continueCommissioning failed", e)
                    }
                }
            }
            advance(PairingStage.SecuringSession)
        }

        trySend(PairingEvent.Progress(PairingStage.FindingPlug))
        try {
            val outcome = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                when (route) {
                    is PairingRoute.Network -> {
                        // Already on Wi-Fi: no Bluetooth and no network credentials.
                        advance(PairingStage.Connecting)
                        prepareController()
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
                            if (nearby.isNotEmpty()) terminal.complete(PairingEvent.CodeMismatch) else fail(PairingStage.FindingPlug, null)
                            return@withTimeoutOrNull terminal.await()
                        }

                        advance(PairingStage.Connecting)
                        val gatt = ble.connect(context, device)
                        if (gatt == null) {
                            fail(PairingStage.Connecting, null)
                            return@withTimeoutOrNull terminal.await()
                        }
                        val connectionId = ble.connectionId
                        prepareController()
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
                terminal.await()
            }
            trySend(outcome ?: PairingEvent.Failure(lastStage.get(), null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "pairing attempt crashed", e)
            trySend(PairingEvent.Failure(lastStage.get(), null))
        } finally {
            cleanup()
        }
        close()
        awaitClose { cleanup() }
    }

    /**
     * Maps a CommissioningStage name reported by the library to a user-visible stage, or null if it is not one we show.
     * VERIFY: the exact stage strings sent by onCommissioningStatusUpdate / onCommissioningStageStart at v1.6.1.0
     * (expected something like "kWiFiNetworkSetup", "kWiFiNetworkEnable", "kFindOperationalForCommissioningComplete",
     * "kSendComplete"); log them on the first run and adjust. If the callback does not fire, only Commissioning is shown.
     */
    private fun mapStage(stage: String): PairingStage? {
        val s = stage.lowercase()
        return when {
            s.contains("wifi") -> PairingStage.JoiningWifi
            s.contains("findoperational") || s.contains("operational") -> PairingStage.FindingOnNetwork
            s.contains("sendcomplete") || s.contains("cleanup") -> PairingStage.Finishing
            s.isEmpty() -> null
            else -> PairingStage.Commissioning
        }
    }

    private companion object {
        const val TAG = "Commissioner"
        const val ATTEMPT_TIMEOUT_MS = 120_000L
        const val ATTESTATION_FAILSAFE_SECONDS = 600
        const val NETWORK_LOOK_MS = 6_000L
        const val NETWORK_POLL_MS = 500L
        const val MAX_DISCOVERED = 10
    }
}
