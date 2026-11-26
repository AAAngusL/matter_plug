package app.matterplug.guard.ui

import app.matterplug.alerts.ui.AlertEndpointEditor
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

    /** The alert server's address and token, and the test button. */
    val endpoint = AlertEndpointEditor(application, viewModelScope, needsAddress = settings.map { it != null && (it.alertsEnabled || it.statusEnabled) })

    private val disable = DisableFlow(application, viewModelScope)
    val disableState: StateFlow<DisableState> = disable.state

    private val prompts = PermissionPromptQueue(application, store, viewModelScope)

    /** The permission prompt the screen should launch next; the screen calls [promptConsumed] as it launches it. */
    val prompt: StateFlow<PermissionPrompt?> = prompts.prompt

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
        if (on && !endpoint.requireValidAddress()) return
        viewModelScope.launch {
            // The store queues cancelled for the open alerts and keeps what waits (RECOVERY-SIGNALS.md 2.4).
            GuardControl.afterSwitch(getApplication(), store, store.update { it.copy(alertsEnabled = on) })
        }
    }

    fun setStatusEnabled(on: Boolean) {
        if (on && !endpoint.requireValidAddress()) return
        viewModelScope.launch { store.update { it.copy(statusEnabled = on) } }
    }

    /** Posts a test request to the address in the field (see [AlertEndpointEditor.sendTest]). */
    fun sendTestAlert() = endpoint.sendTest {
        val last = status.value
        (last.lastLevel ?: 0) to Power(last.lastPlugged ?: false, BatteryStatus.Unknown)
    }

    /** The "Check now" button: the check runs through the normal alarm path. Only while Battery Guard is on. */
    fun checkNow() {
        viewModelScope.launch {
            if (store.readSettings().enabled) rearm()
        }
    }
}
