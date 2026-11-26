package app.matterplug.cellular.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.matterplug.cellular.CellularRunner
import app.matterplug.cellular.CellularStatus
import app.matterplug.cellular.CellularStore
import app.matterplug.cellular.logic.CellularSettings
import app.matterplug.checks.CellularRunNow
import app.matterplug.guard.GuardStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Cellular Check screen (docs/CELLULAR-CHECK.md section 10). Shows nothing until the stored values are read. */
class CellularViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CellularStore(application)

    val settings: StateFlow<CellularSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val status: StateFlow<CellularStatus> = store.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CellularStatus())

    /** Battery Guard's Check every value: the passive layers run at it. */
    val intervalMin: StateFlow<Int?> = GuardStore(application).settings.map { it.intervalMin }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What is in the number field, kept here so typing is not interrupted by the round trip through DataStore. */
    private val _numberText = MutableStateFlow("")
    val numberText: StateFlow<String> = _numberText

    init {
        viewModelScope.launch { _numberText.value = store.settings.first().ownNumber }
    }

    /** Called after the phone permission is granted. The first run follows in 1 s. */
    fun enable() {
        viewModelScope.launch {
            store.update { it.copy(enabled = true) }
            CellularRunNow.start(getApplication(), withLoopback = false)      // the first run, now
        }
    }

    fun disable() {
        viewModelScope.launch {
            val closed = store.update { it.copy(enabled = false) }        // ends the alert and forgets the loopback state
            CellularRunner.stop(getApplication())
            CellularRunner.closed(getApplication(), closed)      // cancelled, behind the waiting requests (kept)
        }
    }

    fun setOwnNumber(text: String) {
        _numberText.value = text
        viewModelScope.launch { store.update { it.copy(ownNumber = text) } }
    }

    /** Send cellular alerts. Switching it off drops Cellular Check's waiting alert requests, as Battery Guard's switch does its own. */
    fun setAlerts(on: Boolean) {
        viewModelScope.launch {
            CellularRunner.closed(getApplication(), store.update { it.copy(alertsEnabled = on) })
        }
    }

    fun setStatus(on: Boolean) {
        viewModelScope.launch { store.update { it.copy(statusEnabled = on) } }
    }

    fun setTestTime(hour: Int, minute: Int) {
        viewModelScope.launch { store.update { it.copy(smsTestMinutes = hour * 60 + minute) } }
    }

    /** Run cellular check now: the passive layers and a loopback SMS, whatever the state (section 6). */
    fun runNow() {
        if (settings.value?.enabled != true) return
        CellularRunNow.start(getApplication(), withLoopback = true)
    }
}
