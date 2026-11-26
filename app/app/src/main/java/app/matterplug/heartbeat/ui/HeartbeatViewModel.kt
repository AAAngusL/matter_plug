package app.matterplug.heartbeat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.matterplug.fixedip.IpStore
import app.matterplug.sms.SmsResult
import app.matterplug.guard.GuardStore
import app.matterplug.heartbeat.HeartbeatStore
import app.matterplug.heartbeat.logic.HeartbeatSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The heartbeat controls (docs/CONSOLE-HEARTBEAT.md section 5): the switch and the number of missed checks. */
class HeartbeatViewModel(application: Application) : AndroidViewModel(application) {
    private val store = HeartbeatStore(application)

    val settings: StateFlow<HeartbeatSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The SMS goes to the Fixed IP numbers (decision 3), so the switch cannot turn on without a valid IP alert number. */
    val hasNumber: StateFlow<Boolean> = IpStore(application).settings.map { it.smsNumberValid }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The heartbeat learns from the status push of each check (section 2), so it needs the status switch. */
    val statusOn: StateFlow<Boolean> = GuardStore(application).settings.map { it.statusEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val lastSms: StateFlow<SmsResult.Last?> = store.lastSms.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [hasNumber] and the SMS permission are checked by the caller. */
    fun setEnabled(on: Boolean) {
        viewModelScope.launch { store.update { it.copy(enabled = on) } }
    }

    fun setLimit(limit: Int) {
        viewModelScope.launch { store.update { it.copy(limit = limit) } }
    }
}
