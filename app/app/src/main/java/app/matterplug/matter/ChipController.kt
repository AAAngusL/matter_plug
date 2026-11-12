package app.matterplug.matter

import android.content.Context
import android.util.Log
import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.ControllerParams
import chip.devicecontroller.GetConnectedDeviceCallbackJni.GetConnectedDeviceCallback
import chip.platform.AndroidBleManager
import chip.platform.AndroidChipPlatform
import chip.platform.AndroidNfcCommissioningManager
import chip.platform.ChipMdnsCallbackImpl
import chip.platform.DiagnosticDataProviderImpl
import chip.platform.NsdManagerServiceBrowser
import chip.platform.NsdManagerServiceResolver
import chip.platform.PreferencesConfigurationManager
import chip.platform.PreferencesKeyValueStoreManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the one Matter controller instance (the app's fabric lives behind it).
 * Adapted from CHIPTool's ChipClient.
 */
object ChipController {
    private const val TAG = "ChipController"
    private const val VENDOR_ID = 0xFFF4
    private const val CONNECT_TIMEOUT_MS = 10_000L

    private var platform: AndroidChipPlatform? = null
    private var controller: ChipDeviceController? = null

    /** Creates the platform and controller on first use, once per process. Always pass any context; the application context is used. */
    @Synchronized
    fun get(context: Context): ChipDeviceController {
        getAndroidChipPlatform(context)
        // VERIFY: where the default fabric (root key, fabric ID) is stored (PreferencesKeyValueStoreManager = app private SharedPreferences?). Check ControllerParams.java and the default credentials issuer; confirm it survives process restarts and is lost only when app data is cleared.
        return controller ?: ChipDeviceController(
            ControllerParams.newBuilder()
                .setControllerVendorId(VENDOR_ID)
                .setEnableServerInteractions(true)
                .build()
        ).also {
            controller = it
            Log.i(TAG, "Controller created")
        }
    }

    /**
     * The Android platform layer under the controller, created on first use. BleTransport (lifted from CHIPTool,
     * where this lives on ChipClient) uses it to register the Bluetooth connection.
     */
    @Synchronized
    fun getAndroidChipPlatform(context: Context?): AndroidChipPlatform {
        platform?.let { return it }
        val app = requireNotNull(context) { "A context is needed to create the Matter platform" }.applicationContext
        ChipDeviceController.loadJni()
        return AndroidChipPlatform(
            AndroidBleManager(app),
            AndroidNfcCommissioningManager(),
            PreferencesKeyValueStoreManager(app),
            PreferencesConfigurationManager(app),
            NsdManagerServiceResolver(app, NsdManagerServiceResolver.NsdManagerResolverAvailState()),
            NsdManagerServiceBrowser(app),
            ChipMdnsCallbackImpl(),
            DiagnosticDataProviderImpl(app)
        ).also { platform = it }
    }

    /** Returns a device pointer for the node. Throws if the node cannot be reached within 10 seconds. */
    suspend fun connect(context: Context, nodeId: Long): Long {
        val c = get(context)
        val pointer = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Long> { cont ->
                c.getConnectedDevicePointer(
                    nodeId,
                    object : GetConnectedDeviceCallback {
                        override fun onDeviceConnected(devicePointer: Long) {
                            if (cont.isActive) cont.resume(devicePointer)
                        }

                        override fun onConnectionFailure(nodeId: Long, error: Exception) {
                            Log.e(TAG, "Unable to get connected device for node $nodeId", error)
                            if (cont.isActive) {
                                cont.resumeWithException(IllegalStateException("Unable to connect to node $nodeId", error))
                            }
                        }
                    }
                )
            }
        }
        return pointer ?: throw IllegalStateException("Timed out connecting to node $nodeId")
    }

    /** Releases a pointer from [connect]. Settled: ChipDeviceController.releaseConnectedDevicePointer(long) exists at v1.6.1.0. */
    @Synchronized
    fun release(devicePointer: Long) {
        try {
            controller?.releaseConnectedDevicePointer(devicePointer)
        } catch (e: Exception) {
            Log.w(TAG, "release failed", e)
        }
    }
}
