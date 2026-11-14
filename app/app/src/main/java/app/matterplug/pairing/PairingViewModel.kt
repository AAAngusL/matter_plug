package app.matterplug.pairing

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.matterplug.R
import app.matterplug.data.Plug
import app.matterplug.data.PlugRepository
import app.matterplug.matter.Commissioner
import app.matterplug.matter.EndpointFinder
import app.matterplug.matter.PairingEvent
import app.matterplug.matter.PairingStage
import app.matterplug.matter.PlugClient
import app.matterplug.matter.SetupCode
import app.matterplug.matter.SetupCodeResult
import app.matterplug.matter.parseSetupCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/** Why pairing cannot start yet; the user fixes it and presses Pair again (nothing is lost). */
enum class StartProblem { BluetoothOff, LocationOff, BlePermissionDenied }

sealed interface FailureReason {
    data class AtStage(val stage: PairingStage, val errorCode: Long?) : FailureReason
    data object NoOnOff : FailureReason
    /** A plug in pairing mode was found, but not with this code's discriminator. */
    data object CodeMismatch : FailureReason
    data object Cancelled : FailureReason
}

/** State machine of spec section 7.1. */
sealed interface PairingState {
    data class EnterCode(val typing: Boolean = false, val notMatterCode: Boolean = false) : PairingState
    data class EnterWifi(val problem: StartProblem? = null, val fiveGhzHint: Boolean = false) : PairingState
    data class Running(val stage: PairingStage, val canCancel: Boolean = true) : PairingState
    data class Failed(val reason: FailureReason) : PairingState
    data class NamePlug(val nodeId: Long, val endpoint: Int) : PairingState
}

class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application = application
    private val repository = PlugRepository(app)
    private val commissioner = Commissioner(app)

    private val _state = MutableStateFlow<PairingState>(PairingState.EnterCode())
    val state: StateFlow<PairingState> = _state.asStateFlow()

    // Held in memory only. The password is cleared whenever pairing ends, whatever the outcome.
    private val _ssid = MutableStateFlow("")
    val ssid: StateFlow<String> = _ssid.asStateFlow()
    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()
    private val _plugName = MutableStateFlow(app.getString(R.string.default_plug_name))
    val plugName: StateFlow<String> = _plugName.asStateFlow()

    private var code: SetupCode? = null
    private var pairJob: Job? = null

    fun setSsid(value: String) { _ssid.value = value }
    fun setPassword(value: String) { _password.value = value }
    fun setPlugName(value: String) { _plugName.value = value }

    fun setTyping(typing: Boolean) {
        val current = _state.value
        if (current is PairingState.EnterCode) _state.value = current.copy(typing = typing, notMatterCode = false)
    }

    /** EnterCode: a scanned or typed value goes through parseSetupCode. */
    fun submitCode(text: String) {
        val current = _state.value as? PairingState.EnterCode ?: return
        when (val result = parseSetupCode(text)) {
            is SetupCodeResult.Ok -> {
                code = result.code
                _state.value = PairingState.EnterWifi(fiveGhzHint = isPhoneOn5Ghz())
            }
            SetupCodeResult.NotAMatterCode -> _state.value = current.copy(notMatterCode = true)
        }
    }

    /** A9: prefill the Wi-Fi name on Android 9 to 11 only, after the Location permission is granted. */
    @Suppress("DEPRECATION")
    fun prefillWifiName() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) return
        if (_ssid.value.isNotEmpty()) return
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val name: String? = try {
            wifi.connectionInfo?.ssid
        } catch (e: SecurityException) {
            null
        }
        val clean = name?.trim('"') ?: return
        if (clean.isNotEmpty() && clean != "<unknown ssid>") _ssid.value = clean
    }

    /** Called when the user presses Pair. [permissionsGranted] says whether the Bluetooth (or Location) permissions are held. */
    fun start(permissionsGranted: Boolean) {
        val current = _state.value as? PairingState.EnterWifi ?: return
        val setupCode = code ?: return
        if (_ssid.value.isEmpty() || _password.value.isEmpty()) return

        val problem = checkStartProblem(permissionsGranted)
        if (problem != null) {
            _state.value = current.copy(problem = problem)
            return
        }

        pairJob?.cancel()
        pairJob = viewModelScope.launch {
            _state.value = PairingState.Running(PairingStage.FindingPlug)
            val nodeId = repository.nextNodeId()   // saved before pairing starts, so a failed attempt never reuses it
            var terminal: PairingEvent? = null
            try {
                commissioner.pair(setupCode, _ssid.value, _password.value, nodeId)
                    .takeWhile { event ->
                        if (event is PairingEvent.Progress) {
                            _state.value = PairingState.Running(event.stage, canCancel = event.stage != PairingStage.Finishing)
                            true
                        } else {
                            terminal = event
                            false
                        }
                    }
                    .collect { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "pairing flow failed", e)
                terminal = PairingEvent.Failure(lastStageOrDefault(), null)
            }
            when (val event = terminal) {
                is PairingEvent.Success -> afterSuccess(event.nodeId)
                is PairingEvent.Failure -> fail(FailureReason.AtStage(event.stage, event.errorCode))
                PairingEvent.CodeMismatch -> fail(FailureReason.CodeMismatch)
                else -> fail(FailureReason.AtStage(lastStageOrDefault(), null))
            }
        }
    }

    /** Running: Cancel stops the flow and shows Failed with the reset instruction. */
    fun cancel() {
        pairJob?.cancel()
        pairJob = null
        fail(FailureReason.Cancelled)
    }

    /** NamePlug: renames the plug, which was saved under its default name when pairing succeeded. Blank keeps the default. */
    fun savePlug(onSaved: () -> Unit = {}) {
        val current = _state.value as? PairingState.NamePlug ?: return
        viewModelScope.launch {
            val name = _plugName.value.trim()
            if (name.isNotEmpty()) repository.rename(current.nodeId, name)
            resetAll()
            onSaved()    // the new plug is selected by the repository; the caller leaves the pairing screen
        }
    }

    /** Failed: "Try again" returns to EnterCode with nothing kept (A6). */
    fun tryAgain() {
        pairJob?.cancel()
        pairJob = null
        resetAll()
    }

    private suspend fun afterSuccess(nodeId: Long) {
        _password.value = ""
        _state.value = PairingState.Running(PairingStage.Finishing, canCancel = false)
        val endpoint = try {
            EndpointFinder.find(app, nodeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "could not reach the plug after pairing", e)
            fail(FailureReason.AtStage(PairingStage.FindingOnNetwork, null))
            return
        }
        if (endpoint == null) {
            // No on/off switch: leave the device clean by dropping the fabric.
            PlugClient(app, Plug(nodeId, 0, "")).leaveFabric()
            fail(FailureReason.NoOnOff)
            return
        }
        // Saved now, under its default name: the plug already holds this app's credentials, so a kill before the
        // naming step must not lose it. The naming step only renames it.
        val name = nextDefaultName()
        repository.add(Plug(nodeId, endpoint, name))
        _plugName.value = name
        _state.value = PairingState.NamePlug(nodeId, endpoint)
    }

    private fun fail(reason: FailureReason) {
        _password.value = ""
        _state.value = PairingState.Failed(reason)
    }

    private fun resetAll() {
        code = null
        _ssid.value = ""
        _password.value = ""
        _plugName.value = app.getString(R.string.default_plug_name)
        _state.value = PairingState.EnterCode()
    }

    /** "Plug" for the first plug, "Plug 2", "Plug 3"... after that, so the switcher never lists two identical names. */
    private suspend fun nextDefaultName(): String {
        val base = app.getString(R.string.default_plug_name)
        val count = repository.plugs.first().size
        return if (count == 0) base else "$base ${count + 1}"
    }

    /** Leaves pairing from the "add another plug" entry: drops whatever was entered. */
    fun abandon() {
        pairJob?.cancel()
        pairJob = null
        resetAll()
    }

    private fun lastStageOrDefault(): PairingStage =
        (_state.value as? PairingState.Running)?.stage ?: PairingStage.FindingPlug

    private fun checkStartProblem(permissionsGranted: Boolean): StartProblem? {
        val legacy = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R   // Android 9 to 11
        if (!permissionsGranted) return if (legacy) StartProblem.LocationOff else StartProblem.BlePermissionDenied
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return StartProblem.BluetoothOff
        if (legacy) {
            val location = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (location == null || !location.isLocationEnabled) return StartProblem.LocationOff
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun isPhoneOn5Ghz(): Boolean {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return try { (wifi.connectionInfo?.frequency ?: 0) > 3000 } catch (e: SecurityException) { false }
    }

    override fun onCleared() {
        _password.value = ""
        pairJob?.cancel()
    }

    private companion object {
        const val TAG = "PairingViewModel"
    }
}
