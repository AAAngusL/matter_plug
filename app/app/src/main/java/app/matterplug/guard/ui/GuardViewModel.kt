package app.matterplug.guard.ui

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.matterplug.data.PlugRepository
import app.matterplug.guard.GuardStatus
import app.matterplug.battery.BatteryReader
import app.matterplug.battery.BatteryStatus
import app.matterplug.battery.Power
import app.matterplug.alerts.AlertOutbox
import app.matterplug.guard.CheckScheduler
import app.matterplug.alerts.FlushJobService
import app.matterplug.guard.GuardPermissions
import app.matterplug.guard.GuardControl
import app.matterplug.guard.GuardStore
import app.matterplug.alerts.HttpAlerter
import app.matterplug.alerts.logic.AlertAddress
import app.matterplug.alerts.logic.DeliveryPlan
import app.matterplug.guard.logic.GuardSettings
import app.matterplug.guard.logic.PermissionPrompt
import app.matterplug.guard.logic.PermissionPrompts
import app.matterplug.alerts.logic.TestOutcome
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

/**
 * The settings, and the alarm that follows them: on -> check in 1 s, off -> alarm cancelled, a changed
 * threshold or interval while on -> check in 1 s. Also the alert settings rules, the flush job and the test button, the
 * permission prompts at the first enable, "Check now", and the "plug is off" dialog when switching off.
 * The settings screen shows nothing until the stored values are read ([settings] is null before that).
 */
class GuardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = GuardStore(application)

    val settings: StateFlow<GuardSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val status: StateFlow<GuardStatus> = store.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuardStatus())

    /** Battery Guard acts on the Charger plug (the first plug until another is chosen). Null until the repository has answered. */
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

    private val disable = DisableFlow(application, viewModelScope)
    val disableState: StateFlow<DisableState> = disable.state

    private val prompts = PermissionPromptQueue(application, store, viewModelScope)

    /** The permission prompt the screen should launch next; the screen calls [promptConsumed] as it launches it. */
    val prompt: StateFlow<PermissionPrompt?> = prompts.prompt

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
        if (on) enable() else disable.request()
    }

    private fun enable() {
        if (hasPlug.value != true) return                    // refused without a plug
        if (!disable.canStart) return
        disable.reset()
        viewModelScope.launch {
            store.update { it.copy(enabled = true) }
            // The check itself records the next time, so nothing stale is left in the store.
            rearm()
            // 5.9: the prompts never block the switch; they come after it has been turned on.
            prompts.queue()
        }
    }

    /** The screen has taken the prompt and is launching it; clearing it here means a rotation does not launch it twice. */
    fun promptConsumed() = prompts.consumed()

    /** The screen's launcher has returned (granted or not) or the intent was started: go on to the next prompt, if any. */
    fun promptDone() = prompts.done()

    fun disableTurnOn() = disable.turnOn()
    fun disableLeaveOff() = disable.leaveOff()
    fun disableCancel() = disable.cancel()

    fun setLow(value: Int) { updateAndRearm { it.copy(low = value) } }
    fun setHigh(value: Int) { updateAndRearm { it.copy(high = value) } }
    fun setInterval(min: Int) { updateAndRearm { it.copy(intervalMin = min) } }

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
            // The store queues cancelled for the open alerts and keeps what waits (RECOVERY-SIGNALS.md 2.4).
            GuardControl.afterSwitch(getApplication(), store, store.update { it.copy(alertsEnabled = on) })
        }
    }

    fun setStatusEnabled(on: Boolean) {
        if (on && !refuseInvalidAddress()) return
        viewModelScope.launch { store.update { it.copy(statusEnabled = on) } }
    }

    /**
     * The text is saved whatever it is; the switches are what need a valid address. When the text is valid and alerts are
     * waiting, the flush job is scheduled after the typing has paused for 1.5 s, so a half-typed address
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
                if (store.readSettings().alertAddress == text && AlertOutbox(getApplication()).readQueue().isNotEmpty()) {
                    guarded("schedule flush job") { FlushJobService.schedule(getApplication<Application>()) }
                }
            }
        }
    }

    /**
     * The token is trimmed (whitespace and newlines) and saved; nothing else happens, the next request reads it.
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
                val power = reading?.power ?: Power(status.value.lastPlugged ?: false, BatteryStatus.Unknown)
                val result = HttpAlerter.postTest(AlertOutbox(app), level, power)
                _testState.value = TestState.Done(DeliveryPlan.describeTest(result))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("Guard", "test alert failed", e)
                _testState.value = TestState.Idle
            }
        }
    }

    /** The "Check now" button: the check runs through the normal alarm path. Only while Battery Guard is on. */
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
