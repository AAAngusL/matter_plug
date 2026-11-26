package app.matterplug.plug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.matterplug.data.Plug
import app.matterplug.data.PlugRepository
import app.matterplug.checks.GuardSwitch
import app.matterplug.matter.PlugClient
import app.matterplug.matter.PlugState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The plug's connection state plus the requested value while a press is waiting for its report (A5). */
data class PlugUiState(val plug: PlugState, val pending: Boolean?)

enum class PlugMessage { NotResponding }

/** State machine of spec section 7.2. */
class PlugViewModel(application: Application, private val plug: Plug) : AndroidViewModel(application) {
    private val repository = PlugRepository(application)
    private val client = PlugClient(application, plug)

    private val pending = MutableStateFlow<Boolean?>(null)
    private val _message = MutableStateFlow<PlugMessage?>(null)
    val message: StateFlow<PlugMessage?> = _message.asStateFlow()

    val ui: StateFlow<PlugUiState> = combine(client.state, pending) { s, p -> PlugUiState(s, p) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlugUiState(PlugState.Connecting, null))

    private var startJob: Job? = null
    private var timerJob: Job? = null

    init {
        // A report with isOn == pending clears pending; Offline (or reconnecting) clears it too.
        viewModelScope.launch {
            client.state.collect { s ->
                when (s) {
                    is PlugState.Online -> if (pending.value == s.isOn) clearPending()
                    PlugState.Offline, PlugState.Connecting -> clearPending()
                }
            }
        }
    }

    /** ON_START: connect and subscribe. */
    fun onScreenStart() {
        startJob?.cancel()
        startJob = viewModelScope.launch { client.start() }
    }

    /** ON_STOP: drop the subscription and session; clear pending. */
    fun onScreenStop() {
        startJob?.cancel()
        startJob = null
        client.stop()
        clearPending()
    }

    /** Toggle pressed. Only acts while Online and not pending. */
    fun toggle() {
        val current = ui.value
        val online = current.plug as? PlugState.Online ?: return
        if (current.pending != null) return
        val wanted = !online.isOn
        _message.value = null
        pending.value = wanted
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            delay(PENDING_TIMEOUT_MS)
            if (pending.value != null) {
                pending.value = null
                _message.value = PlugMessage.NotResponding
            }
        }
        viewModelScope.launch {
            val result = client.set(wanted)
            if (result.isFailure && pending.value == wanted) {
                clearPending()
                _message.value = PlugMessage.NotResponding
            }
        }
    }

    fun rename(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { repository.rename(plug.nodeId, clean) }
    }

    /**
     * Remove plug (A7). Tells the plug to drop the fabric if it is online, then forgets it in every case.
     * [onRemoved] gets true when the plug could not be reached, so the caller can show the factory-reset notice.
     */
    fun remove(onRemoved: (unreachable: Boolean) -> Unit) {
        viewModelScope.launch {
            // Battery Guard must not move on to another plug by itself: removing the charger plug switches it off.
            if (repository.charger()?.nodeId == plug.nodeId) GuardSwitch.off(getApplication())
            val wasOnline = ui.value.plug is PlugState.Online
            if (wasOnline) client.leaveFabric()
            client.stop()
            repository.remove(plug.nodeId)
            onRemoved(!wasOnline)
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    private fun clearPending() {
        pending.value = null
        timerJob?.cancel()
    }

    override fun onCleared() {
        client.stop()
    }

    private companion object {
        const val PENDING_TIMEOUT_MS = 5_000L
    }

    class Factory(private val application: Application, private val plug: Plug) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlugViewModel(application, plug) as T
    }
}
