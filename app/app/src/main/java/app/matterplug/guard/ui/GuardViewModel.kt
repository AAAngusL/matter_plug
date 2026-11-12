package app.matterplug.guard.ui

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.matterplug.data.PlugRepository
import app.matterplug.guard.GuardStatus
import app.matterplug.guard.BatteryReader
import app.matterplug.guard.CheckScheduler
import app.matterplug.guard.FlushJobService
import app.matterplug.guard.GuardPermissions
import app.matterplug.guard.GuardStore
import app.matterplug.guard.HttpAlerter
import app.matterplug.guard.logic.AlertAddress
import app.matterplug.guard.logic.DeliveryPlan
import app.matterplug.guard.logic.GuardSettings
import app.matterplug.guard.logic.PermissionPrompt
import app.matterplug.guard.logic.PermissionPrompts
import app.matterplug.guard.logic.TestOutcome
import app.matterplug.matter.PlugCommander
import app.matterplug.matter.SwitchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What is shown next to the "Send test alert" button. */
sealed interface TestState {
    data object Idle : TestState
    data object Sending : TestState
    data class Done(val outcome: TestOutcome) : TestState
}

/** Where "switch Battery Guard off" stands (BATTERY-GUARD.md section 4, "Manual use of the plug"). */
sealed interface DisableState {
    /** Nothing going on. */
    data object Idle : DisableState
    /** Reading the plug's state; the switch is disabled meanwhile. */
    data object Reading : DisableState
    /** The plug is off: the dialog is shown. */
    data object AskTurnOn : DisableState
    /** "Turn on" was chosen and the plug is being switched on. */
    data object TurningOn : DisableState
    /** "Turn on" did not work; Battery Guard stays on. */
    data object TurnOnFailed : DisableState
}

/**
 * G2/G3: the settings, and the alarm that follows them: on -> check in 1 s, off -> alarm cancelled, a changed
 * threshold or interval while on -> check in 1 s (B3). G6 adds the alert settings rules, the flush job and the test button.
 * G7: the permission prompts at the first enable, "Check now", and the "plug is off" dialog when switching off.
 * The settings screen shows nothing until the stored values are read ([settings] is null before that).
 */
class GuardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = GuardStore(application)

    val settings: StateFlow<GuardSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val status: StateFlow<GuardStatus> = store.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuardStatus())

    /** B10: Battery Guard acts on the first plug. Null until the repository has answered. */
    val hasPlug: StateFlow<Boolean?> = PlugRepository(application).plugs
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What is in the address field. Kept here so typing is not interrupted by the round trip through DataStore. */
    private val _addressText = MutableStateFlow("")
    val addressText: StateFlow<String> = _addressText

    /** What is in the token field (trimmed), for the same reason as [addressText]. Never logged. */
    private val _tokenText = MutableStateFlow("")
    val tokenText: StateFlow<String> = _tokenText

    /** The result of the last press of "Send test alert". Cleared when the address is edited. */
    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState

    private val _disableState = MutableStateFlow<DisableState>(DisableState.Idle)
    val disableState: StateFlow<DisableState> = _disableState

    /** The permission prompt the screen should launch next; the screen calls [promptConsumed] as it launches it. */
    private val _prompt = MutableStateFlow<PermissionPrompt?>(null)
    val prompt: StateFlow<PermissionPrompt?> = _prompt
    private var pendingPrompts: List<PermissionPrompt> = emptyList()

    private var flushJob: Job? = null
    private var addressTouched = false
    private var tokenTouched = false
    private val refusedAddress = MutableStateFlow(false)

    /** True when the address is invalid and something needs it (a refused switch, or alerts or status already on). */
    val addressError: StateFlow<Boolean> = combine(settings, _addressText, refusedAddress) { s, text, refused ->
        !AlertAddress.isValid(text) && (refused || (s != null && (s.alertsEnabled || s.statusEnabled)))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            val saved = store.readSettings()
            if (!addressTouched) _addressText.value = saved.alertAddress
            if (!tokenTouched) _tokenText.value = saved.alertToken
        }
    }

    fun setEnabled(on: Boolean) {
        if (on) enable() else requestDisable()
    }

    private fun enable() {
        if (hasPlug.value != true) return                    // B10: refused without a plug
        if (_disableState.value != DisableState.Idle && _disableState.value != DisableState.TurnOnFailed) return
        _disableState.value = DisableState.Idle
        viewModelScope.launch {
            store.update { it.copy(enabled = true) }
            // The check itself records the next time, so nothing stale is left in the store.
            rearm()
            // 5.9: the prompts never block the switch; they come after it has been turned on.
            queuePrompts()
        }
    }

    /** Works out which prompts are due (each is asked once, 5.9) and starts with the first. */
    private suspend fun queuePrompts() {
        try {
            val app = getApplication<Application>()
            pendingPrompts = PermissionPrompts.due(
                sdkInt = Build.VERSION.SDK_INT,
                notificationsGranted = GuardPermissions.notificationsGranted(app),
                askedNotifications = store.readAskedNotifications(),
                exemptionHeld = GuardPermissions.ignoringBatteryOptimizations(app),
                askedExemption = store.readAskedExemption(),
            )
            showNextPrompt()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("Guard", "permission prompts failed", e)
        }
    }

    private suspend fun showNextPrompt() {
        val next = pendingPrompts.firstOrNull()
        pendingPrompts = pendingPrompts.drop(1)
        when (next) {
            PermissionPrompt.Notifications -> store.markAskedNotifications()
            PermissionPrompt.Exemption -> store.markAskedExemption()
            null -> {}
        }
        _prompt.value = next
    }

    /** The screen has taken the prompt and is launching it; clearing it here means a rotation does not launch it twice. */
    fun promptConsumed() { _prompt.value = null }

    /** The screen's launcher has returned (granted or not) or the intent was started: go on to the next prompt, if any. */
    fun promptDone() {
        viewModelScope.launch {
            try { showNextPrompt() } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("Guard", "next prompt failed", e)
            }
        }
    }

    /**
     * Switch off. First reads the plug (8 s limit, [PlugCommander.readOnOff]); when it is off, the dialog asks
     * ([needsTurnOnDialog]); when it is on or could not be read, Battery Guard is simply disabled.
     */
    private fun requestDisable() {
        val state = _disableState.value
        if (state != DisableState.Idle && state != DisableState.TurnOnFailed) return    // a read is running: ignore a second tap
        _disableState.value = DisableState.Reading
        viewModelScope.launch {
            try {
                val plug = PlugRepository(getApplication<Application>()).plugs.first().firstOrNull()
                val isOn: Boolean? = if (plug == null) null else PlugCommander(getApplication<Application>()).readOnOff(plug)
                Log.i("Guard", "disable: plug state ${isOn ?: "unknown"}")
                if (PermissionPrompts.needsTurnOnDialog(isOn)) {
                    _disableState.value = DisableState.AskTurnOn
                } else {
                    disableNow()
                    _disableState.value = DisableState.Idle
                }
            } catch (e: Exception) {
                if (e is CancellationException) { _disableState.value = DisableState.Idle; throw e }
                Log.e("Guard", "disable failed", e)
                _disableState.value = DisableState.Idle
            }
        }
    }

    /** Dialog: "Turn on". Switches the plug on, then disables. If the plug cannot be switched on, Battery Guard stays on. */
    fun disableTurnOn() {
        if (_disableState.value != DisableState.AskTurnOn) return
        _disableState.value = DisableState.TurningOn
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val plug = PlugRepository(app).plugs.first().firstOrNull()
                val result = if (plug == null) SwitchResult.NotReached("no plug") else PlugCommander(app).switchTo(plug, true)
                Log.i("Guard", "disable: turn on -> $result")
                if (result is SwitchResult.Confirmed) {
                    disableNow()
                    _disableState.value = DisableState.Idle
                } else {
                    _disableState.value = DisableState.TurnOnFailed
                }
            } catch (e: Exception) {
                if (e is CancellationException) { _disableState.value = DisableState.Idle; throw e }
                Log.e("Guard", "turn on before disabling failed", e)
                _disableState.value = DisableState.TurnOnFailed
            }
        }
    }

    /** Dialog: "Leave off". */
    fun disableLeaveOff() {
        if (_disableState.value != DisableState.AskTurnOn) return
        viewModelScope.launch {
            try { disableNow() } finally { _disableState.value = DisableState.Idle }
        }
    }

    /** Dialog: "Cancel" (or dismissed). Battery Guard stays on and the switch stays on. */
    fun disableCancel() {
        if (_disableState.value == DisableState.AskTurnOn || _disableState.value == DisableState.TurnOnFailed) _disableState.value = DisableState.Idle
    }

    /** The store clears the waiting list, the alert state and the next time (5.2); the alarm and the flush job are cancelled here. */
    private suspend fun disableNow() {
        store.update { it.copy(enabled = false) }
        guarded("cancel") { CheckScheduler.cancel(getApplication<Application>()) }
        // The store has cleared the waiting list (5.2): the flush job would find nothing to do.
        guarded("cancel flush job") { FlushJobService.cancel(getApplication<Application>()) }
    }

    fun setLow(value: Int) { updateAndRearm { it.copy(low = value) } }
    fun setHigh(value: Int) { updateAndRearm { it.copy(high = value) } }
    fun setChargingInterval(min: Int) { updateAndRearm { it.copy(chargingIntervalMin = min) } }
    fun setBatteryInterval(min: Int) { updateAndRearm { it.copy(batteryIntervalMin = min) } }

    /** Writes the setting; when Battery Guard is on, moves the next check to one second from now. */
    private fun updateAndRearm(transform: (GuardSettings) -> GuardSettings) {
        viewModelScope.launch {
            store.update(transform)
            if (store.readSettings().enabled) rearm()
        }
    }

    private fun rearm() = guarded("scheduleIn") { CheckScheduler.scheduleIn(getApplication<Application>(), 1_000) }

    /** A failure of the alarm service must not crash the settings screen. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.e("Guard", "$what failed", e) }
    }

    fun setAlertsEnabled(on: Boolean) {
        if (on && !refuseInvalidAddress()) return
        viewModelScope.launch {
            store.update { it.copy(alertsEnabled = on) }      // the store clears the queue when turning off
            if (!on) guarded("cancel flush job") { FlushJobService.cancel(getApplication<Application>()) }
        }
    }

    fun setStatusEnabled(on: Boolean) {
        if (on && !refuseInvalidAddress()) return
        viewModelScope.launch { store.update { it.copy(statusEnabled = on) } }
    }

    /**
     * The text is saved whatever it is; the switches are what need a valid address. When the text is valid and alerts are
     * waiting, the flush job is scheduled (5.9) after the typing has paused for 1.5 s, so a half-typed address
     * ("http://192.168.1.2") is not tried.
     */
    fun setAlertAddress(text: String) {
        addressTouched = true
        _addressText.value = text
        refusedAddress.value = false
        _testState.value = TestState.Idle
        viewModelScope.launch { store.update { it.copy(alertAddress = text) } }
        flushJob?.cancel()
        if (AlertAddress.isValid(text)) {
            flushJob = viewModelScope.launch {
                delay(ADDRESS_SETTLE_MS)
                if (store.readSettings().alertAddress == text && store.readQueue().isNotEmpty()) {
                    guarded("schedule flush job") { FlushJobService.schedule(getApplication<Application>()) }
                }
            }
        }
    }

    /**
     * The token is trimmed (whitespace and newlines) and saved; nothing else happens, the next request reads it (B13).
     * It does not trigger a flush by itself. Never logged.
     */
    fun setAlertToken(text: String) {
        val t = text.trim()
        tokenTouched = true
        _tokenText.value = t
        _testState.value = TestState.Idle
        viewModelScope.launch { store.update { it.copy(alertToken = t) } }
    }

    /** Posts a test request to the address in the field. Needs a valid address only: works with "Send alerts" off. */
    fun sendTestAlert() {
        if (_testState.value == TestState.Sending) return
        if (!AlertAddress.isValid(_addressText.value)) { refusedAddress.value = true; return }
        _testState.value = TestState.Sending
        viewModelScope.launch {
            try {
                // The same text that was typed has been stored by setAlertAddress; make sure of it before the request reads it.
                if (store.readSettings().alertAddress != _addressText.value) store.update { it.copy(alertAddress = _addressText.value) }
                if (store.readSettings().alertToken != _tokenText.value) store.update { it.copy(alertToken = _tokenText.value) }
                val app = getApplication<Application>()
                val reading = try { BatteryReader.read(app) } catch (e: Exception) { null }
                val level = reading?.level ?: status.value.lastLevel ?: 0
                val plugged = reading?.pluggedIn ?: status.value.lastPlugged ?: false
                val result = HttpAlerter.postTest(app, store, level, plugged)
                _testState.value = TestState.Done(DeliveryPlan.describeTest(result))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("Guard", "test alert failed", e)
                _testState.value = TestState.Idle
            }
        }
    }

    /** The "Check now" button: the check runs through the normal alarm path (B3). Only while Battery Guard is on. */
    fun checkNow() {
        viewModelScope.launch {
            if (store.readSettings().enabled) rearm()
        }
    }

    private fun refuseInvalidAddress(): Boolean {
        val ok = AlertAddress.isValid(_addressText.value)
        refusedAddress.value = !ok
        return ok
    }

    private companion object {
        const val ADDRESS_SETTLE_MS = 1_500L
    }
}
