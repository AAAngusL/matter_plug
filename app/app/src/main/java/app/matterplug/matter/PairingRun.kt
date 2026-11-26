package app.matterplug.matter

import android.os.Handler
import android.os.Looper
import android.util.Log
import chip.devicecontroller.ChipDeviceController
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The state of one pairing attempt and the library's callbacks into it: which stage was reached, which step failed first, and the single
 * outcome ([outcome]). [send] receives each new stage as a [PairingEvent.Progress].
 */
internal class PairingRun(private val controller: ChipDeviceController, private val send: (PairingEvent) -> Unit) {
    private val stage = AtomicReference(PairingStage.FindingPlug)
    private val cleanedUp = AtomicBoolean(false)
    // Stage and code of the first failed step, kept until the library reports that commissioning has ended.
    private val failedStep = AtomicReference<Pair<PairingStage, Long>?>(null)
    private val terminal = CompletableDeferred<PairingEvent>()

    /** The stage last reached. */
    val lastStage: PairingStage get() = stage.get()

    /** Completes with the first end of the attempt: success, failure or a code mismatch. */
    suspend fun outcome(): PairingEvent = terminal.await()

    /** Moves forward only; later duplicate or earlier stage reports are ignored. */
    fun advance(next: PairingStage) {
        if (next.ordinal > stage.get().ordinal) {
            stage.set(next)
            send(PairingEvent.Progress(next))
        }
    }

    fun fail(at: PairingStage, errorCode: Long?) {
        terminal.complete(PairingEvent.Failure(at, errorCode))
    }

    fun codeMismatch() {
        terminal.complete(PairingEvent.CodeMismatch)
    }

    /**
     * Ends the Bluetooth connection. Safe to call more than once.
     * The controller itself is kept: the library allows only one controller per process, and creating a second
     * one aborts the app. The attestation delegate is also left installed, because replacing it while the
     * library is still commissioning crashes inside the library.
     */
    fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return
        try {
            controller.close()
        } catch (e: Exception) {
            Log.w(TAG, "controller.close failed", e)
        }
    }

    private val listener = object : GenericChipDeviceListener() {
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
                    failedStep.compareAndSet(null, (mapped ?: lastStage) to errorCode)
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
                fail(step?.first ?: lastStage, step?.second ?: errorCode)
            }
        }

        override fun onError(error: Throwable?) {
            Log.e(TAG, "commissioning error", error)
            fail(lastStage, null)
        }
    }

    /** Shared by both routes, set just before commissioning starts. */
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
        const val ATTESTATION_FAILSAFE_SECONDS = 600
    }
}
