package app.matterplug.guard.ui

import android.app.Application
import android.util.Log
import app.matterplug.data.PlugRepository
import app.matterplug.guard.GuardControl
import app.matterplug.guard.logic.PermissionPrompts
import app.matterplug.matter.PlugCommander
import app.matterplug.matter.SwitchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
 * Switching Battery Guard off while the charger plug may be off (BATTERY-GUARD.md section 4): reads the plug, asks whether to turn it on
 * first when it is off, and disables. The screen follows [state].
 */
class DisableFlow(private val app: Application, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<DisableState>(DisableState.Idle)
    val state: StateFlow<DisableState> = _state

    /** Nothing is running that a new tap on the switch must wait for. */
    val canStart: Boolean get() = _state.value == DisableState.Idle || _state.value == DisableState.TurnOnFailed

    /** Turning Battery Guard on again ends any failed attempt. */
    fun reset() { _state.value = DisableState.Idle }

    /**
     * Switch off. First reads the plug (8 s limit, [PlugCommander.readOnOff]); when it is off, the dialog asks
     * ([PermissionPrompts.needsTurnOnDialog]); when it is on or could not be read, Battery Guard is simply disabled.
     */
    fun request() {
        if (!canStart) return    // a read is running: ignore a second tap
        _state.value = DisableState.Reading
        scope.launch {
            try {
                val plug = PlugRepository(app).charger()
                val isOn: Boolean? = if (plug == null) null else PlugCommander(app).readOnOff(plug)
                Log.i("Guard", "disable: plug state ${isOn ?: "unknown"}")
                if (PermissionPrompts.needsTurnOnDialog(isOn)) {
                    _state.value = DisableState.AskTurnOn
                } else {
                    disableNow()
                    _state.value = DisableState.Idle
                }
            } catch (e: Exception) {
                if (e is CancellationException) { _state.value = DisableState.Idle; throw e }
                Log.e("Guard", "disable failed", e)
                _state.value = DisableState.Idle
            }
        }
    }

    /** Dialog: "Turn on". Switches the plug on, then disables. If the plug cannot be switched on, Battery Guard stays on. */
    fun turnOn() {
        if (_state.value != DisableState.AskTurnOn) return
        _state.value = DisableState.TurningOn
        scope.launch {
            try {
                val plug = PlugRepository(app).charger()
                val result = if (plug == null) SwitchResult.NotReached("no plug") else PlugCommander(app).switchTo(plug, true)
                Log.i("Guard", "disable: turn on -> $result")
                if (result is SwitchResult.Confirmed) {
                    disableNow()
                    _state.value = DisableState.Idle
                } else {
                    _state.value = DisableState.TurnOnFailed
                }
            } catch (e: Exception) {
                if (e is CancellationException) { _state.value = DisableState.Idle; throw e }
                Log.e("Guard", "turn on before disabling failed", e)
                _state.value = DisableState.TurnOnFailed
            }
        }
    }

    /** Dialog: "Leave off". */
    fun leaveOff() {
        if (_state.value != DisableState.AskTurnOn) return
        scope.launch {
            try { disableNow() } finally { _state.value = DisableState.Idle }
        }
    }

    /** Dialog: "Cancel" (or dismissed). Battery Guard stays on and the switch stays on. */
    fun cancel() {
        if (_state.value == DisableState.AskTurnOn || _state.value == DisableState.TurnOnFailed) _state.value = DisableState.Idle
    }

    private suspend fun disableNow() = GuardControl.disable(app)
}
